package com.example.data.network

object MarketPriceCache {
    @Volatile var latestPrice: Double? = null
    @Volatile var latestTimestamp: Long = 0L

    fun update(price: Double, timestamp: Long) {
        if (!price.isNaN() && price in 1000.0..5000.0) {
            latestPrice = price
            latestTimestamp = timestamp
        }
    }
}
