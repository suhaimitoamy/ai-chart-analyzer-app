package com.example.data.network

import okhttp3.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import android.util.Log
import java.net.URLEncoder
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
                } catch (e: Exception) {
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
            val encodedSymbol = URLEncoder.encode(symbol, "UTF-8")
            val url = "https://api.twelvedata.com/time_series?symbol=$encodedSymbol&interval=$interval&outputsize=$outputSize&apikey=$apiKey"
            val request = Request.Builder().url(url).build()
            val candles = mutableListOf<CandleEntity>()
            val timeframe = intervalToTimeframe(interval)
            val seconds = timeframeToSeconds(timeframe)

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("TwelveData HTTP ${response.code}: ${bodyStr.take(160)}")
                }
                if (bodyStr.isBlank()) {
                    throw IllegalStateException("TwelveData response kosong untuk $symbol $interval")
                }

                val json = JSONObject(bodyStr)
                if (json.optString("status").equals("error", true) || json.has("code") && json.optJSONArray("values") == null) {
                    val message = json.optString("message", bodyStr.take(160))
                    throw IllegalStateException("TwelveData error: $message")
                }

                val values = json.optJSONArray("values")
                    ?: throw IllegalStateException("TwelveData tidak mengirim values untuk $symbol $interval")

                for (i in 0 until values.length()) {
                    val item = values.getJSONObject(i)
                    val datetimeStr = item.getString("datetime")
                    val timeSec = parseDatetimeToSeconds(datetimeStr)
                    val open = item.getString("open").toDouble()
                    val high = item.getString("high").toDouble()
                    val low = item.getString("low").toDouble()
                    val close = item.getString("close").toDouble()
                    candles.add(
                        CandleEntity(
                            time = timeSec,
                            symbol = symbol,
                            timeframe = timeframe,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            tickCount = 1,
                            closeTime = timeSec + seconds - 1,
                            isClosed = true
                        )
                    )
                }
            }

            candles.reversed()
        }
    }

    private fun parseDatetimeToSeconds(datetime: String): Long {
        val timezone = TimeZone.getTimeZone("UTC")
        val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = timezone
                return (sdf.parse(datetime)?.time ?: 0L) / 1000L
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis() / 1000L
    }

    private fun intervalToTimeframe(interval: String): String {
        return when (interval.lowercase(Locale.US)) {
            "1min" -> "M1"
            "5min" -> "M5"
            "15min" -> "M15"
            "30min" -> "M30"
            "1h" -> "H1"
            "4h" -> "H4"
            "1day" -> "D1"
            "1week" -> "W1"
            else -> "M1"
        }
    }

    private fun timeframeToSeconds(timeframe: String): Long {
        return when (timeframe.uppercase(Locale.US)) {
            "M1" -> 60L
            "M5" -> 300L
            "M15" -> 900L
            "M30" -> 1800L
            "H1" -> 3600L
            "H4" -> 14400L
            "D1" -> 86400L
            "W1" -> 604800L
            else -> 60L
        }
    }
}
