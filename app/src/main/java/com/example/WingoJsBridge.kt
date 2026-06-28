package com.example

import android.webkit.JavascriptInterface
import android.util.Log

/**
 * Injected into WebView so website's JavaScript can send the user_id
 * and login status to the Android side.
 */
class WingoJsBridge(
    private val onUserLoggedIn: (userId: String) -> Unit,
    private val onUserLoggedOut: () -> Unit
) {

    @JavascriptInterface
    fun onLogin(userId: String) {
        Log.d("WingoBridge", "User logged in: $userId")
        onUserLoggedIn(userId)
    }

    @JavascriptInterface
    fun onLogout() {
        Log.d("WingoBridge", "User logged out")
        onUserLoggedOut()
    }

    @JavascriptInterface
    fun getUserId(): String {
        return ""
    }
}
