package com.example.data.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class CandleBuilder {
    private val timeframeSeconds = linkedMapOf(
        "M1" to 60L,
        "M5" to 300L,
        "M15" to 900L,
        "H1" to 3600L,
        "H4" to 14400L,
        "D1" to 86400L
    )

    private val _candles = mutableListOf<Candle>()
    val candles: List<Candle> get() = _candles.map { it.copy() }

    private val currentCandles = mutableMapOf<String, Candle>()

    private val _candleUpdated = MutableSharedFlow<Candle>(extraBufferCapacity = 64)
    val candleUpdated = _candleUpdated.asSharedFlow()

    private val _candleClosed = MutableSharedFlow<Candle>(extraBufferCapacity = 64)
    val candleClosed = _candleClosed.asSharedFlow()

    fun processTick(symbol: String, price: Double, timestamp: Long) {
        if (price.isNaN() || timestamp <= 0) return

        timeframeSeconds.forEach { (timeframe, seconds) ->
            val openTs = (timestamp / seconds) * seconds
            val current = currentCandles[timeframe]

            if (current == null || openTs > current.time) {
                current?.let { closing ->
                    val closed = closing.copy(
                        closeTime = closing.time + seconds - 1,
                        isClosed = true
                    )
                    _candles.add(closed)
                    _candleClosed.tryEmit(closed)
                }

                currentCandles[timeframe] = Candle(
                    symbol = symbol,
                    timeframe = timeframe,
                    time = openTs,
                    closeTime = null,
                    open = price,
                    high = price,
                    low = price,
                    close = price,
                    tickCount = 1,
                    isClosed = false
                )
            } else {
                current.high = maxOf(current.high, price)
                current.low = minOf(current.low, price)
                current.close = price
                current.tickCount++
            }

            currentCandles[timeframe]?.let {
                _candleUpdated.tryEmit(it.copy())
            }
        }
    }

    fun getCurrentCandle(timeframe: String = "M1"): Candle? {
        return currentCandles[timeframe]?.copy()
    }
}
