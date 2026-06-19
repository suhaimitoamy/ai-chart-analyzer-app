package com.example.data.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TelegramClient(private val botToken: String, private val chatId: String) {
    private val client = OkHttpClient()
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private var lastUpdateId = 0L

    suspend fun sendMessage(text: String) = withContext(Dispatchers.IO) {
        if(botToken.isBlank() || chatId.isBlank()) return@withContext
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/sendMessage")
            .post(body)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                // Log result if needed
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun pollUpdates(): List<JSONObject> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<JSONObject>()
        if(botToken.isBlank()) return@withContext updates
        
        val request = Request.Builder()
            .url("$baseUrl/getUpdates?offset=${lastUpdateId + 1}&timeout=30")
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = response.body?.string()
                    val json = JSONObject(respBody ?: "{}")
                    if (json.optBoolean("ok")) {
                        val results = json.optJSONArray("result")
                        for (i in 0 until (results?.length() ?: 0)) {
                            val item = results!!.getJSONObject(i)
                            lastUpdateId = item.optLong("update_id", lastUpdateId)
                            updates.add(item)
                        }
                    }
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
        updates
    }
}
