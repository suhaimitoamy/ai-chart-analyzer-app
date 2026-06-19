package com.example.data.network

data class Candle(
    val symbol: String,
    val timeframe: String,
    val time: Long,
    val closeTime: Long?,
    var open: Double,
    var high: Double,
    var low: Double,
    var close: Double,
    var tickCount: Int,
    val isClosed: Boolean = false
)
