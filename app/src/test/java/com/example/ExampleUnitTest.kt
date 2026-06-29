package com.example

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testPostRequestToYarwins() {
    println("--- STARTING POST REQUEST TEST ---")
    val url = "https://yaarwins.xyz/admi907/itachi_musp_set.php"
    
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val formBody = FormBody.Builder()
        .add("username", "4")
        .build()

    val request = Request.Builder()
        .url(url)
        .post(formBody)
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .build()

    try {
        val response = client.newCall(request).execute()
        val code = response.code
        val body = response.body?.string() ?: "Empty body"
        println("Response Code: $code")
        println("Response Body: $body")
        assertTrue(response.isSuccessful)
    } catch (e: Exception) {
        println("Request failed: ${e.message}")
        e.printStackTrace()
        fail(e.message)
    }
  }
}

