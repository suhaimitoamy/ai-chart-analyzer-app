package com.example.data.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DeepSeekClient(private val apiKey: String) {
    private val client = OkHttpClient()

    suspend fun analyzeChart(prompt: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "DeepSeek API key kosong. Isi API key di Settings."

        val json = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an ICT/SMC XAUUSD mapping assistant. Return valid JSON only. Do not add markdown, explanations outside JSON, or non-JSON text.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.2)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val resStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext when (response.code) {
                        400 -> "DeepSeek API error 400: format request salah atau model tidak diterima."
                        401 -> "DeepSeek API error 401: API key DeepSeek salah, expired, atau bukan key DeepSeek."
                        402 -> "DeepSeek API error 402: saldo/credit DeepSeek tidak cukup."
                        429 -> "DeepSeek API error 429: rate limit DeepSeek tercapai. Coba lagi nanti."
                        500, 502, 503, 504 -> "DeepSeek API error ${response.code}: server DeepSeek sedang bermasalah."
                        else -> "DeepSeek API error ${response.code}: ${resStr.take(180)}"
                    }
                }

                if (resStr.isBlank()) return@withContext "DeepSeek response kosong."

                val resJson = JSONObject(resStr)
                val choices = resJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    return@withContext choices.getJSONObject(0)
                        .optJSONObject("message")
                        ?.optString("content", "")
                        ?.trim()
                        .orEmpty()
                }

                "DeepSeek response tidak punya choices."
            }
        } catch (e: Exception) {
            "Network error ke DeepSeek: ${e.message ?: "unknown"}"
        }
    }
}
