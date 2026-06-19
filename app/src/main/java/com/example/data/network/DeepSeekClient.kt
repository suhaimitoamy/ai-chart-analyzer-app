package com.example.data.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class DeepSeekClient(private val apiKey: String) {
    private val client = OkHttpClient()

    suspend fun analyzeChart(prompt: String): String = withContext(Dispatchers.IO) {
        if(apiKey.isBlank()) return@withContext "API key not found."
        val json = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert crypto/forex trading bot. You analyze market data, tick by tick, create methods based on candlestick patterns (Price Action, Break & Retest, Following the Trend, Momentum, Reversal, SMC, ICT, CRT) and give specific entry positions with Take Profit and Stop Loss.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.7)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                     return@withContext "Error compiling analysis."
                }
                val resStr = response.body?.string() ?: "{}"
                val resJson = JSONObject(resStr)
                val choices = resJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    return@withContext choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
                }
                "No response"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Network error communicating with DeepSeek API."
        }
    }
}
