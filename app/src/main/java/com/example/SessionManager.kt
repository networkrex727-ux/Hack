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
        
        const val DEFAULT_WEBSITE_URL = "https://yaarwins.xyz/"
        const val DEFAULT_BASE_URL = "https://yaarwins.xyz/admin90/"
    }

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

    fun clear() = prefs.edit().clear().apply()
}
