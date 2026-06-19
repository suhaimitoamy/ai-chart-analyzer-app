package com.example.data.network

import com.example.data.database.CandleEntity

object MarketEventScanner {
    data class Event(val key: String, val priority: Int, val text: String)

    fun scan(timeframe: String, candles: List<CandleEntity>, livePrice: Double? = null): List<Event> {
        if (candles.isEmpty()) return emptyList()
        val last = candles.last()
        return listOf(Event("${timeframe}-${last.time}", 10, "[$timeframe] Candle closed C:${last.close}"))
    }
}
