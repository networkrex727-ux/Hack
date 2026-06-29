package com.example

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "wingo_session", Context.MODE_PRIVATE
    )

    companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_LOGGED_IN = "is_logged_in"
        const val KEY_HACK_ACTIVE = "hack_active"
        const val KEY_BALANCE = "balance"
        const val KEY_WEBSITE_URL = "website_url"
        const val KEY_BASE_URL = "base_url"
        const val KEY_PERIOD_OFFSET = "period_offset"
        const val KEY_TIME_OFFSET = "time_offset"
        const val KEY_IS_ADMIN_MODE = "is_admin_mode"
        
        const val DEFAULT_WEBSITE_URL = "https://yaarwins.xyz/"
        const val DEFAULT_BASE_URL = "https://yaarwins.xyz/admi907/"
    }

    var isAdminMode: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ADMIN_MODE, value).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGGED_IN, value).apply()

    var hackActive: Boolean
        get() = prefs.getBoolean(KEY_HACK_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_HACK_ACTIVE, value).apply()

    var balance: Double
        get() = prefs.getFloat(KEY_BALANCE, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_BALANCE, value.toFloat()).apply()

    var websiteUrl: String
        get() = prefs.getString(KEY_WEBSITE_URL, DEFAULT_WEBSITE_URL) ?: DEFAULT_WEBSITE_URL
        set(value) = prefs.edit().putString(KEY_WEBSITE_URL, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var periodOffset: Int
        get() = prefs.getInt(KEY_PERIOD_OFFSET, 0)
        set(value) = prefs.edit().putInt(KEY_PERIOD_OFFSET, value).apply()

    var timeOffset: Int
        get() = prefs.getInt(KEY_TIME_OFFSET, 0)
        set(value) = prefs.edit().putInt(KEY_TIME_OFFSET, value).apply()

    fun getBalance(userId: String): Double {
        return prefs.getFloat("balance_$userId", 0.0f).toDouble()
    }

    fun setBalance(userId: String, balance: Double) {
        prefs.edit().putFloat("balance_$userId", balance.toFloat()).apply()
    }

    fun getTotalDeposit(userId: String): Double {
        return prefs.getFloat("total_deposit_$userId", 0.0f).toDouble()
    }

    fun setTotalDeposit(userId: String, total: Double) {
        prefs.edit().putFloat("total_deposit_$userId", total.toFloat()).apply()
    }

    fun getDepositLogs(userId: String): List<DepositLog> {
        val raw = prefs.getString("deposit_logs_$userId", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").mapNotNull {
            val parts = it.split("|||")
            if (parts.size >= 4) {
                DepositLog(
                    amount = parts[0].toDoubleOrNull() ?: 0.0,
                    utr = parts[1],
                    timestamp = parts[2],
                    status = parts[3]
                )
            } else null
        }
    }

    fun addDepositLog(userId: String, log: DepositLog) {
        val currentLogs = getDepositLogs(userId).toMutableList()
        currentLogs.add(0, log)
        val serialized = currentLogs.joinToString(";;;") {
            "${it.amount}|||${it.utr}|||${it.timestamp}|||${it.status}"
        }
        prefs.edit().putString("deposit_logs_$userId", serialized).apply()
        
        // Update balance and total deposits
        val newTotalDeposit = getTotalDeposit(userId) + log.amount
        setTotalDeposit(userId, newTotalDeposit)
        
        val newBalance = getBalance(userId) + log.amount
        setBalance(userId, newBalance)
    }

    fun clear() = prefs.edit().clear().apply()
}
