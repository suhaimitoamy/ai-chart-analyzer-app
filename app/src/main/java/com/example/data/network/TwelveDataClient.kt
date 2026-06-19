package com.example.data.network

import okhttp3.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.example.data.database.CandleEntity

class TwelveDataClient(private val apiKey: String) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    
    private val _ticks = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val ticks = _ticks.asSharedFlow()

    fun connect(symbol: String) {
        val request = Request.Builder()
            .url("wss://ws.twelvedata.com/v1/quotes/price?apikey=$apiKey")
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("TwelveData", "Connected")
                val subscribeMsg = """{"action": "subscribe", "params": {"symbols": "$symbol"}}"""
                webSocket.send(subscribeMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("price")) { 
                         _ticks.tryEmit(json)
                    }
                } catch(e: Exception) {
                     Log.e("TwelveData", "Parse error", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("TwelveData", "WS Failure", t)
            }
        })
    }
    
    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
    }

    suspend fun fetchHistoricalCandles(symbol: String, interval: String = "1min", outputSize: Int = 100): List<CandleEntity> {
        return withContext(Dispatchers.IO) {
            val url = "https://api.twelvedata.com/time_series?symbol=${symbol.replace("/", "")}&interval=$interval&outputsize=$outputSize&apikey=$apiKey"
            val request = Request.Builder().url(url).build()
            val candles = mutableListOf<CandleEntity>()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrEmpty()) {
                            val json = JSONObject(bodyStr)
                            val values = json.optJSONArray("values")
                            if (values != null) {
                                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                sdf.timeZone = TimeZone.getTimeZone("UTC")
                                for (i in 0 until values.length()) {
                                    val item = values.getJSONObject(i)
                                    val datetimeStr = item.getString("datetime")
                                    val timeMs = sdf.parse(datetimeStr)?.time ?: 0L
                                    val open = item.getString("open").toDouble()
                                    val high = item.getString("high").toDouble()
                                    val low = item.getString("low").toDouble()
                                    val close = item.getString("close").toDouble()
                                    candles.add(CandleEntity(
                                        time = timeMs / 1000,
                                        symbol = symbol,
                                        open = open,
                                        high = high,
                                        low = low,
                                        close = close,
                                        tickCount = 1 // default for history
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TwelveData", "Fetch historical error", e)
            }
            // Twelve Data returns newest first. Reverse to get oldest first (chronological)
            candles.reversed()
        }
    }
}
