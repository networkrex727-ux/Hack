package com.example

import android.content.Context
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object ApiRepository {

    private val client: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // --- Prediction Fetch & Status Parsing ---
    suspend fun getPrediction(context: Context, userId: String): PredictionData? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "https://yaarwins.xyz/admi907/itachi_musp_set.php"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""

                    // Robust Regex for active prediction: "Active Prediction:.*?<b>(\d+)</b>"
                    val predictionRegex = Regex("""Active Prediction:.*?<b>(\d+)</b>""", RegexOption.IGNORE_CASE)
                    val periodRegex = Regex("""for period.*?<b>(\d+)</b>""", RegexOption.IGNORE_CASE)

                    val numberMatch = predictionRegex.find(html)?.groups?.get(1)?.value?.toIntOrNull()
                    val periodMatch = periodRegex.find(html)?.groups?.get(1)?.value

                    if (numberMatch != null && periodMatch != null) {
                        val color = when (numberMatch) {
                            0, 5 -> "violet"
                            1, 3, 7, 9 -> "green"
                            else -> "red"
                        }
                        val size = if (numberMatch >= 5) "big" else "small"
                        
                        return@withContext PredictionData(
                            number = numberMatch,
                            color = color,
                            size = size,
                            strategy = "Server Decrypted Feed",
                            periodId = periodMatch
                        )
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // --- Fetch Live Period from wingo10min.php ---
    suspend fun getLivePeriod(context: Context): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "https://yaarwins.xyz/admi907/wingo10min.php"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    // Match value="PERIOD_ID" in the response HTML
                    val periodRegex = Regex("""value="(\d{10,15})"""", RegexOption.IGNORE_CASE)
                    val match = periodRegex.find(html)
                    if (match != null) {
                        return@withContext match.groups[1]?.value
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // --- Set Prediction on Server ---
    suspend fun setPredictionOnServer(context: Context, number: Int): Boolean {
        val session = SessionManager(context)
        if (!session.isAdminMode) return true // Protect: Only Admin can set on server

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "https://yaarwins.xyz/admi907/itachi_musp_set.php"
                val formBody = FormBody.Builder()
                    .add("username", number.toString())
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    // --- Unset Prediction on Server ---
    suspend fun unsetPredictionOnServer(context: Context): Boolean {
        val session = SessionManager(context)
        if (!session.isAdminMode) return true

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "https://yaarwins.xyz/admi907/sonu_itach_unset.php"
                val formBody = FormBody.Builder().build() // Empty post body

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    // --- Stub Methods for Old/Deleted API calls to keep callers happy ---
    suspend fun getBalance(context: Context, userId: String): Double {
        return 3000.0 // Hardcoded successful user balance
    }

    suspend fun checkDeposit(context: Context, userId: String): DepositCheckResponse {
        return DepositCheckResponse(
            status = "success",
            required = 3000.0,
            totalDeposit = 3000.0,
            remaining = 0.0,
            unlocked = true
        )
    }

    suspend fun getHackStatus(context: Context, userId: String): HackStatusResponse {
        return HackStatusResponse(
            status = "success",
            hackActive = 1,
            expires = "Unlimited"
        )
    }

    suspend fun registerUser(context: Context, userId: String, deviceId: String): Boolean {
        return true
    }

    suspend fun activateHack(context: Context, userId: String): Boolean {
        return true
    }
}
