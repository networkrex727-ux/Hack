package com.example

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiRepository {

    private var cachedBaseUrl: String? = null
    private var cachedApi: ApiService? = null

    @Synchronized
    fun getApiService(context: Context): ApiService {
        val session = SessionManager(context)
        val currentBaseUrl = session.baseUrl
        
        // If baseUrl hasn't changed and we have a cached API, return it
        if (currentBaseUrl == cachedBaseUrl && cachedApi != null) {
            return cachedApi!!
        }

        // Rebuild Retrofit
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(currentBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val apiService = retrofit.create(ApiService::class.java)
        cachedBaseUrl = currentBaseUrl
        cachedApi = apiService
        return apiService
    }

    // --- Prediction ---
    suspend fun getPrediction(context: Context, userId: String): PredictionData? {
        return try {
            val api = getApiService(context)
            val response = api.getPrediction(GenericRequest("predict", userId))
            if (response.isSuccessful && response.body()?.status == "success") {
                response.body()?.prediction
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- Balance ---
    suspend fun getBalance(context: Context, userId: String): Double {
        return try {
            val api = getApiService(context)
            val response = api.getBalance(GenericRequest("balance", userId))
            if (response.isSuccessful) {
                response.body()?.balance ?: 0.0
            } else 0.0
        } catch (e: Exception) {
            e.printStackTrace()
            0.0
        }
    }

    // --- Deposit Check ---
    suspend fun checkDeposit(context: Context, userId: String): DepositCheckResponse? {
        return try {
            val api = getApiService(context)
            val response = api.checkDeposit(GenericRequest("check_deposit", userId))
            if (response.isSuccessful) response.body()
            else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- Hack Status ---
    suspend fun getHackStatus(context: Context, userId: String): HackStatusResponse? {
        return try {
            val api = getApiService(context)
            val response = api.getHackStatus(GenericRequest("status", userId))
            if (response.isSuccessful) response.body()
            else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- Register User ---
    suspend fun registerUser(context: Context, userId: String, deviceId: String): Boolean {
        return try {
            val api = getApiService(context)
            val response = api.registerUser(RegisterRequest(userId = userId, deviceId = deviceId))
            response.isSuccessful && response.body()?.status == "success"
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- Activate Hack ---
    suspend fun activateHack(context: Context, userId: String): Boolean {
        return try {
            val api = getApiService(context)
            val response = api.activateHack(GenericRequest("activate", userId))
            response.isSuccessful && response.body()?.status == "success"
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- Set Prediction on Server ---
    suspend fun setPredictionOnServer(context: Context, number: Int): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val session = SessionManager(context)
                val baseUrl = session.baseUrl
                val url = if (baseUrl.endsWith("/")) {
                    "${baseUrl}itticina_geluvu_zehn.php"
                } else {
                    "$baseUrl/itticina_geluvu_zehn.php"
                }

                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie(session.websiteUrl) ?: cookieManager.getCookie(baseUrl) ?: ""

                val loggingInterceptor = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }

                val client = okhttp3.OkHttpClient.Builder()
                    .addInterceptor(loggingInterceptor)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val formBody = okhttp3.FormBody.Builder()
                    .add("username", number.toString())
                    .build()

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .post(formBody)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")

                if (cookies.isNotEmpty()) {
                    requestBuilder.addHeader("Cookie", cookies)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
