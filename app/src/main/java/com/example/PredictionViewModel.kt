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
    private val session = SessionManager(context)

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
    private var predictionPeriodId: String? = null

    // --- Load all data after login ---
    fun loadUserData(userId: String, forceHackActive: Boolean = false) {
        if (userId.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Balance
            _balance.value = ApiRepository.getBalance(context, userId)
            
            // 2. Hack status
            val status = ApiRepository.getHackStatus(context, userId)
            _hackActive.value = (status?.hackActive == 1) || forceHackActive
            
            // 3. Deposit info
            _depositInfo.value = ApiRepository.checkDeposit(context, userId)
            
            // 4. Period calculation
            updatePeriod()
            
            // 5. Start countdown (handles fetching on new period changes)
            startCountdown(userId)
            
            // 6. If hack is active, perform initial single fetch
            if (_hackActive.value) {
                fetchPrediction(userId)
            }
            
            _isLoading.value = false
        }
    }

    // --- Wingo 30-second period calculation ---
    fun updatePeriod() {
        val now = System.currentTimeMillis() // current UTC epoch in ms
        
        // 1. Convert to IST (UTC + 5:30) which is standard for Wingo games
        val istOffsetMs = 5.5 * 60 * 60 * 1000 // 19800000 ms
        val istTimeMs = now + istOffsetMs.toLong()
        
        // 2. Incorporate time calibration offset (in seconds)
        val calibratedIstTimeMs = istTimeMs + (session.timeOffset * 1000L)
        val calibratedIstSeconds = calibratedIstTimeMs / 1000L
        
        val secondsInDay = calibratedIstSeconds % 86400
        
        // 3. 30-second period calculation (2880 periods per day)
        val basePeriodNumber = (secondsInDay / 30) + 1 // period number of the day starting from 1
        
        // Apply period calibration offset
        val finalPeriodNumber = (basePeriodNumber + session.periodOffset).toInt()
        
        val secsInPeriod = (calibratedIstSeconds % 30).toInt()
        val remaining = 30 - secsInPeriod

        // 4. Period ID format: YYYYMMDD + 4-digit period number (e.g., 202606280123)
        // Format date in IST timezone to avoid mismatch with local phone timezone
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+5:30")
        }
        val dateStr = sdf.format(Date(now))
        
        // Ensure period number wraps around correctly (within 1 to 2880 for a 30s game)
        val formattedPeriodNumber = when {
            finalPeriodNumber <= 0 -> 2880 + (finalPeriodNumber % 2880)
            finalPeriodNumber > 2880 -> (finalPeriodNumber - 1) % 2880 + 1
            else -> finalPeriodNumber
        }
        
        _currentPeriod.value = "$dateStr${formattedPeriodNumber.toString().padStart(4, '0')}"
        _timeLeft.value = remaining
    }

    // --- 30-second countdown ---
    private fun startCountdown(userId: String) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPeriodId = _currentPeriod.value
            while (isActive) {
                updatePeriod()
                val newPeriodId = _currentPeriod.value
                
                // If period ID changed, fetch the new prediction exactly ONCE!
                if (newPeriodId.isNotEmpty() && newPeriodId != lastPeriodId) {
                    lastPeriodId = newPeriodId
                    if (_hackActive.value) {
                        // Clear old prediction so UI shows loading state
                        _prediction.value = null
                        // Fetch the prediction exactly once in background IO thread
                        launch(Dispatchers.IO) {
                            fetchPrediction(userId)
                        }
                    }
                }
                
                delay(1000L)
                
                // When timer reaches 0/1, wait briefly for API update
                if (_timeLeft.value <= 1) {
                    delay(800L) // short wait for new period to reflect on server
                }
            }
        }
    }

    suspend fun fetchPrediction(userId: String) {
        val currentPeriodId = _currentPeriod.value
        if (currentPeriodId.isEmpty()) return

        // If we already have a locked prediction for the current period, do not fetch a new one!
        if (predictionPeriodId == currentPeriodId && _prediction.value != null) {
            return
        }

        // If the period has changed, clear the old prediction to show the loading spinner for the new period
        if (predictionPeriodId != currentPeriodId) {
            _prediction.value = null
        }

        val result = ApiRepository.getPrediction(context, userId)
        if (result != null) {
            _prediction.value = result
            predictionPeriodId = currentPeriodId
            _errorMessage.value = null
        } else {
            // Check if hack expired
            val status = ApiRepository.getHackStatus(context, userId)
            if (status?.hackActive != 1) {
                _hackActive.value = false
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
    }
}
