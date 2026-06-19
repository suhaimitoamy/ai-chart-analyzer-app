package com.example.data.network

data class Candle(
    val time: Long,
    var open: Double,
    var high: Double,
    var low: Double,
    var close: Double,
    var tickCount: Int
) {
    fun copy(): Candle {
        return Candle(time, open, high, low, close, tickCount)
    }
}
