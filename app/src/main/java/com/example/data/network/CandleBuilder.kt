package com.example.data.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class CandleBuilder {
    private val _candles = mutableListOf<Candle>()
    val candles: List<Candle> get() = _candles.map { it.copy() }

    private var currentCandle: Candle? = null

    // Emit event when current candle updates
    private val _candleUpdated = MutableSharedFlow<Candle>(extraBufferCapacity = 64)
    val candleUpdated = _candleUpdated.asSharedFlow()

    // Emit event when a candle is closed
    private val _candleClosed = MutableSharedFlow<Candle>(extraBufferCapacity = 64)
    val candleClosed = _candleClosed.asSharedFlow()

    fun processTick(price: Double, timestamp: Long) {
        if (price.isNaN() || timestamp <= 0) return

        // Floor to the start of the minute (UTC)
        // timestamp is assumed to be in seconds
        val minuteTs = (timestamp / 60) * 60

        if (currentCandle == null || minuteTs > currentCandle!!.time) {
            // Close out the previous candle
            currentCandle?.let { closedCandle ->
                val copy = closedCandle.copy()
                _candles.add(copy)
                _candleClosed.tryEmit(copy)
            }

            // Open a brand-new candle
            currentCandle = Candle(
                time = minuteTs,
                open = price,
                high = price,
                low = price,
                close = price,
                tickCount = 1
            )
        } else {
            // Update in place
            currentCandle?.let {
                it.high = maxOf(it.high, price)
                it.low = minOf(it.low, price)
                it.close = price
                it.tickCount++
            }
        }

        // Notify listeners of the update
        currentCandle?.let {
            _candleUpdated.tryEmit(it.copy())
        }
    }

    fun getCurrentCandle(): Candle? {
        return currentCandle?.copy()
    }
}
