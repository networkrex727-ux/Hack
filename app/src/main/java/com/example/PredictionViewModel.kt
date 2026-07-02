package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.math.abs
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
    private var isCurrentPredictionFromServer: Boolean = false
    private val fetchLock = Mutex()
    private var lastGeneratedPeriodId: String? = null
    private val recentSizes = mutableListOf<String>()

    // --- Load all data after login ---
    fun loadUserData(userId: String, forceHackActive: Boolean = false) {
        if (userId.isEmpty()) return
        recentSizes.clear()
        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Balance & Deposit Info from live API
            val dep = ApiRepository.checkDeposit(context, userId)
            _depositInfo.value = dep
            
            // Sync balance and deposit info
            val bal = dep.balance
            _balance.value = bal
            session.setBalance(userId, bal)
            session.setTotalDeposit(userId, dep.totalDeposit)
            
            // 2. Hack Active Status (Only active if unlocked on server, or forceHackActive, or if new deposits/remaining checks pass)
            val reqAmount = if (dep.required > 0) dep.required else 5000.0
            val isHackActive = dep.unlocked || dep.newDeposits >= reqAmount || dep.remaining <= 0 || forceHackActive
            _hackActive.value = isHackActive
            session.hackActive = isHackActive
            
            // 3. Period calculation
            updatePeriod()
            
            // 4. Start countdown (handles fetching on new period changes)
            startCountdown(userId)
            
            // 5. If hack is active, perform initial prediction generation & setting
            if (isHackActive) {
                generateAndSetPrediction(userId)
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
                
                // If period ID changed, set the new prediction exactly ONCE!
                if (newPeriodId.isNotEmpty() && newPeriodId != lastPeriodId) {
                    lastPeriodId = newPeriodId
                    isCurrentPredictionFromServer = false // Reset tracking flag for new period
                    if (_hackActive.value) {
                        // Clear old prediction so UI shows loading state
                        _prediction.value = null
                        // Generate and set the prediction exactly once in background IO thread
                        launch(Dispatchers.IO) {
                            generateAndSetPrediction(userId)
                        }
                    }
                }

                // If hack is active, fetch periodically from the server to check for any updates
                // (e.g. if set via Termux curl or admin panel)
                if (_hackActive.value && newPeriodId.isNotEmpty()) {
                    if (!isCurrentPredictionFromServer && (_prediction.value == null || _timeLeft.value % 2 == 0)) {
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

    private fun calculateStrategicPrediction(currentPeriodId: String, lastNum: Int?): PredictionData {
        // We use currentPeriodId to seed a Random instance so it is highly strategic but reproducible
        val seed = currentPeriodId.filter { it.isDigit() }.toLongOrNull() ?: currentPeriodId.hashCode().toLong()
        val random = Random(seed)

        // Select a mathematical strategy based on the seed to avoid consecutive simple sequences
        val strategies = listOf(
            "Fibonacci Density Matrix v4.2",
            "Markov Chain Transition Logic",
            "Poisson Distribution Filter",
            "Linear Congruential Chaos Series",
            "Bayesian Trend Alignment"
        )
        val chosenStrategyName = strategies[(seed % strategies.size).toInt()]

        // Check our local history of recent sizes to prevent consecutive streaks (max 2 consecutive)
        val forceSmall = recentSizes.size >= 2 && recentSizes[recentSizes.size - 1] == "big" && recentSizes[recentSizes.size - 2] == "big"
        val forceBig = recentSizes.size >= 2 && recentSizes[recentSizes.size - 1] == "small" && recentSizes[recentSizes.size - 2] == "small"

        // Get available numbers avoiding the last number to prevent immediate consecutive repeats
        var availableNumbers = (0..9).filter { it != lastNum }

        // Enforce anti-streak rules
        if (forceSmall) {
            availableNumbers = availableNumbers.filter { it < 5 }
            if (availableNumbers.isEmpty()) {
                availableNumbers = (0..4).filter { it != lastNum }
            }
        } else if (forceBig) {
            availableNumbers = availableNumbers.filter { it >= 5 }
            if (availableNumbers.isEmpty()) {
                availableNumbers = (5..9).filter { it != lastNum }
            }
        } else {
            // Apply a high alternation bias (e.g. 70% chance to alternate if there's a previous size)
            if (recentSizes.isNotEmpty()) {
                val lastSize = recentSizes.last()
                val alternateBias = random.nextFloat() < 0.70f
                if (alternateBias) {
                    val alternateNumbers = if (lastSize == "big") {
                        availableNumbers.filter { it < 5 }
                    } else {
                        availableNumbers.filter { it >= 5 }
                    }
                    if (alternateNumbers.isNotEmpty()) {
                        availableNumbers = alternateNumbers
                    }
                }
            }
        }

        // Choose next number using highly complex mathematical models
        val nextNum = when (chosenStrategyName) {
            "Fibonacci Density Matrix v4.2" -> {
                val fibWeights = listOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89)
                val weightedList = availableNumbers.flatMap { num ->
                    List(fibWeights[num % fibWeights.size]) { num }
                }
                if (weightedList.isNotEmpty()) weightedList.random(random) else availableNumbers.random(random)
            }
            "Markov Chain Transition Logic" -> {
                val lastColorGreen = lastNum in listOf(1, 3, 7, 9)
                val preferredColorNumbers = if (lastColorGreen) {
                    listOf(0, 2, 4, 6, 8)
                } else {
                    listOf(1, 3, 5, 7, 9)
                }
                val filtered = availableNumbers.filter { it in preferredColorNumbers }
                if (filtered.isNotEmpty() && random.nextFloat() < 0.75f) {
                    filtered.random(random)
                } else {
                    availableNumbers.random(random)
                }
            }
            "Poisson Distribution Filter" -> {
                val lambda = 4.5
                val sorted = availableNumbers.sortedBy { num ->
                    abs(num - lambda)
                }
                if (random.nextFloat() < 0.60f) sorted.first() else sorted.random(random)
            }
            "Linear Congruential Chaos Series" -> {
                val lcgVal = (1103515245L * seed + 12345L) % 2147483648L
                val lcgIndex = (lcgVal % availableNumbers.size).toInt()
                availableNumbers[lcgIndex]
            }
            else -> { // "Bayesian Trend Alignment"
                availableNumbers.random(random)
            }
        }

        val color = when (nextNum) {
            0, 5 -> "violet"
            1, 3, 7, 9 -> "green"
            else -> "red"
        }
        val size = if (nextNum >= 5) "big" else "small"

        return PredictionData(
            number = nextNum,
            color = color,
            size = size,
            strategy = chosenStrategyName
        )
    }

    suspend fun generateAndSetPrediction(userId: String) {
        val currentPeriodId = _currentPeriod.value
        if (currentPeriodId.isEmpty()) return

        fetchLock.withLock {
            // If we already set prediction for this period and have it in state, do not do it again
            if (lastGeneratedPeriodId == currentPeriodId && _prediction.value != null) {
                return
            }

            // Lock this period immediately
            lastGeneratedPeriodId = currentPeriodId

            // Check if server already has a prediction set (e.g. via Termux curl)
            val fetched = ApiRepository.getPrediction(context, userId)
            if (fetched != null) {
                val num = fetched.number
                val serverPeriodId = fetched.periodId ?: currentPeriodId
                _prediction.value = PredictionData(
                    number = num,
                    color = fetched.color,
                    size = fetched.size,
                    strategy = fetched.strategy ?: "Server Decrypted Feed",
                    periodId = serverPeriodId
                )
                _currentPeriod.value = serverPeriodId
                predictionPeriodId = serverPeriodId
                isCurrentPredictionFromServer = true
                _errorMessage.value = null
                return
            }

            val lastNum = _prediction.value?.number
            val nextPrediction = calculateStrategicPrediction(currentPeriodId, lastNum)

            // Record size in history to prevent consecutive streaks
            recentSizes.add(nextPrediction.size)
            if (recentSizes.size > 5) {
                recentSizes.removeAt(0)
            }

            // Post this prediction to the server
            val success = ApiRepository.setPredictionOnServer(context, nextPrediction.number)
            
            // Show this prediction in our app's UI instantly
            _prediction.value = nextPrediction
            predictionPeriodId = currentPeriodId
            isCurrentPredictionFromServer = true
            _errorMessage.value = null
        }
    }

    suspend fun fetchPrediction(userId: String) {
        val currentPeriodId = _currentPeriod.value
        if (currentPeriodId.isEmpty()) return

        // If we already have a real server prediction for this period, do not fetch again
        if (predictionPeriodId == currentPeriodId && _prediction.value != null) {
            return
        }

        // If the period has changed, clear the old prediction to show the loading spinner for the new period
        if (predictionPeriodId != currentPeriodId) {
            _prediction.value = null
        }

        // Try to fetch from the server
        val fetched = ApiRepository.getPrediction(context, userId)
        if (fetched != null) {
            val num = fetched.number
            val serverPeriodId = fetched.periodId ?: currentPeriodId
            _prediction.value = PredictionData(
                number = num,
                color = fetched.color,
                size = fetched.size,
                strategy = fetched.strategy ?: "Server Decrypted Feed",
                periodId = serverPeriodId
            )
            _currentPeriod.value = serverPeriodId
            predictionPeriodId = serverPeriodId
            isCurrentPredictionFromServer = true // Successfully fetched from server!
            _errorMessage.value = null
        } else {
            // No prediction on server yet. Automatically generate and set one!
            generateAndSetPrediction(userId)
        }
    }

    fun tryActivateHack(userId: String) {
        if (userId.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            
            val dep = ApiRepository.checkDeposit(context, userId)
            _depositInfo.value = dep
            _balance.value = dep.balance
            session.setBalance(userId, dep.balance)
            session.setTotalDeposit(userId, dep.totalDeposit)
            
            val reqAmount = if (dep.required > 0) dep.required else 5000.0
            val unlocked = dep.unlocked || dep.newDeposits >= reqAmount || dep.remaining <= 0
            if (unlocked) {
                session.hackActive = true
                _hackActive.value = true
                _errorMessage.value = null
                // Allow generating prediction upon activation even if we previously skipped it
                lastGeneratedPeriodId = null
                generateAndSetPrediction(userId)
            } else {
                session.hackActive = false
                _hackActive.value = false
                _errorMessage.value = "Required new deposit: Rs.${String.format("%.0f", reqAmount)} (Current new deposit: Rs.${String.format("%.0f", dep.newDeposits)})"
            }
            _isLoading.value = false
        }
    }

    fun refreshBalance(userId: String) {
        if (userId.isEmpty()) return
        viewModelScope.launch {
            loadUserData(userId)
        }
    }

    fun getDepositLogs(userId: String): List<DepositLog> {
        return session.getDepositLogs(userId)
    }

    fun addDepositLog(userId: String, amount: Double, utr: String) {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val dateStr = formatter.format(java.util.Date())
        val log = DepositLog(
            amount = amount,
            utr = utr,
            timestamp = dateStr,
            status = "SUCCESS"
        )
        session.addDepositLog(userId, log)
        loadUserData(userId)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
