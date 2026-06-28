package com.example

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    // Prediction fetch
    @POST("predict_api.php")
    suspend fun getPrediction(@Body body: GenericRequest): Response<PredictionResponse>

    // Balance check
    @POST("predict_api.php")
    suspend fun getBalance(@Body body: GenericRequest): Response<BalanceResponse>

    // Deposit check (hack unlock condition)
    @POST("predict_api.php")
    suspend fun checkDeposit(@Body body: GenericRequest): Response<DepositCheckResponse>

    // Hack status check
    @POST("predict_api.php")
    suspend fun getHackStatus(@Body body: GenericRequest): Response<HackStatusResponse>

    // User register
    @POST("predict_api.php")
    suspend fun registerUser(@Body body: RegisterRequest): Response<ActionResponse>

    // Hack activate
    @POST("predict_api.php")
    suspend fun activateHack(@Body body: GenericRequest): Response<ActionResponse>
}
