package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

class PredictionViewModel(application: Application) : AndroidViewModel(application) {

    private val context = getApplication<Application>().applicationContext

    // State flows for Jetpack Compose UI
    private val _prediction = MutableStateFlow<PredictionData?>(null)
    val prediction: StateFlow<PredictionData?> = _prediction.asStateFlow()

    private val _balance = MutableStateFlow(0.0)
    val balance: StateFlow<Double> = _balance.asStateFlow()

    private val _hackActive = MutableStateFlow(false)
    val hackActive: StateFlow<Boolean> = _hackActive.asStateFlow()

    private val _depositInfo = MutableStateFlow<DepositCheckResponse?>(null)
    val depositInfo: StateFlow<DepositCheckResponse?> = _depositInfo.asStateFlow()

    private val _currentPeriod = MutableStateFlow("")
    val currentPeriod: StateFlow<String> = _currentPeriod.asStateFlow()

    private val _timeLeft = MutableStateFlow(30) // 30-second countdown
    val timeLeft: StateFlow<Int> = _timeLeft.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var countdownJob: Job? = null
    private var autoRefreshJob: Job? = null

    // --- Load all data after login ---
    fun loadUserData(userId: String) {
        if (userId.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Balance
            _balance.value = ApiRepository.getBalance(context, userId)
            
            // 2. Hack status
            val status = ApiRepository.getHackStatus(context, userId)
            _hackActive.value = status?.hackActive == 1
            
            // 3. Deposit info
            _depositInfo.value = ApiRepository.checkDeposit(context, userId)
            
            // 4. Period calculation
            updatePeriod()
            
            // 5. Start countdown
            startCountdown()
            
            // 6. If hack is active, start prediction auto-refresh
            if (_hackActive.value) {
                startAutoPrediction(userId)
            } else {
                autoRefreshJob?.cancel()
            }
            
            _isLoading.value = false
        }
    }

    // --- Wingo 30-second period calculation ---
    private fun updatePeriod() {
        val now = System.currentTimeMillis() / 1000L  // Unix seconds
        val secondsInDay = now % 86400
        val periodNumber = secondsInDay / 30           // Every 30 seconds is a period
        val secsInPeriod = (secondsInDay % 30).toInt()
        val remaining = 30 - secsInPeriod

        // Period ID format: YYYYMMDD + 4-digit number
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        _currentPeriod.value = "$dateStr${periodNumber.toString().padStart(4, '0')}"
        _timeLeft.value = remaining
    }

    // --- 30-second countdown ---
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                updatePeriod()
                delay(1000L)
                
                // When timer reaches 0/1, wait briefly for API update, then refresh prediction if hack active
                if (_timeLeft.value <= 1) {
                    delay(800L) // short wait for new period to reflect on server
                }
            }
        }
    }

    // --- Auto-refresh prediction every period ---
    private fun startAutoPrediction(userId: String) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                fetchPrediction(userId)
                
                // Wait for the next period, dynamic delay
                val tl = _timeLeft.value
                val waitMs = if (tl > 4) {
                    (tl - 2) * 1000L
                } else {
                    (tl + 28) * 1000L
                }
                delay(waitMs)
            }
        }
    }

    suspend fun fetchPrediction(userId: String) {
        val result = ApiRepository.getPrediction(context, userId)
        if (result != null) {
            _prediction.value = result
            _errorMessage.value = null
        } else {
            // Check if hack expired
            val status = ApiRepository.getHackStatus(context, userId)
            if (status?.hackActive != 1) {
                _hackActive.value = false
                autoRefreshJob?.cancel()
                _errorMessage.value = "Hack expired or inactive. Please activate."
            }
        }
    }

    fun tryActivateHack(userId: String) {
        if (userId.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            val success = ApiRepository.activateHack(context, userId)
            if (success) {
                _hackActive.value = true
                startAutoPrediction(userId)
                _errorMessage.value = null
                // Refresh prediction immediately
                fetchPrediction(userId)
            } else {
                val dep = ApiRepository.checkDeposit(context, userId)
                _depositInfo.value = dep
                val remainingAmount = dep?.remaining ?: 0.0
                _errorMessage.value = "Deposit ₹${String.format("%.0f", remainingAmount)} more to activate"
            }
            _isLoading.value = false
        }
    }

    fun refreshBalance(userId: String) {
        if (userId.isEmpty()) return
        viewModelScope.launch {
            _balance.value = ApiRepository.getBalance(context, userId)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        autoRefreshJob?.cancel()
    }
}
