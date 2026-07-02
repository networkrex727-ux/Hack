package com.example

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PredictionResponse(
    val status: String,
    val prediction: PredictionData? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class PredictionData(
    val number: Int,       // 0-9
    val color: String,     // "red", "green", "violet"
    val size: String,       // "big", "small"
    val strategy: String? = null,
    val periodId: String? = null
)

@JsonClass(generateAdapter = true)
data class BalanceResponse(
    val status: String,
    val balance: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class DepositCheckResponse(
    val status: String,
    val required: Double = 0.0,
    @Json(name = "total_deposit") val totalDeposit: Double = 0.0,
    val remaining: Double = 0.0,
    val unlocked: Boolean = false,
    val balance: Double = 0.0,
    @Json(name = "new_deposits") val newDeposits: Double = 0.0,
    val baseline: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class HackStatusResponse(
    val status: String,
    @Json(name = "hack_active") val hackActive: Int = 0,
    val expires: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val action: String = "register",
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String
)

@JsonClass(generateAdapter = true)
data class GenericRequest(
    val action: String,
    @Json(name = "user_id") val userId: String
)

data class WingoPeriod(
    val periodId: String,   // e.g. "20240628001234"
    val timeLeft: Int       // seconds remaining (0–30)
)

@JsonClass(generateAdapter = true)
data class DepositLog(
    val amount: Double,
    val utr: String,
    val timestamp: String,
    val status: String = "SUCCESS"
)

@JsonClass(generateAdapter = true)
data class ActionResponse(
    val status: String,
    val message: String? = null
)
