package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trading_method")
data class TradingMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String,
    val winRate: Float = 0f,
    val totalTrades: Int = 0,
    val rewardPoints: Int = 0
)

@Entity(tableName = "trade_history")
data class TradeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pair: String,
    val methodId: Int,
    val type: String, // "BUY", "SELL"
    val entryPrice: Double,
    val takeProfit: Double,
    val stopLoss: Double,
    val result: String, // "WIN", "LOSS", "PENDING"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "wallet")
data class WalletEntity(
    @PrimaryKey val id: Int = 1,
    val balance: Double = 10000.0 // Default 10k virtual balance
)

@Entity(tableName = "ict_analysis")
data class IctAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timeframe: String,
    val session: String,
    val bias: String, // "BULLISH", "BEARISH", "NEUTRAL"
    val confidence: Int,
    val price: String,
    val date: String,
    val rawResult: String // JSON string
)

@Entity(tableName = "candle_history")
data class CandleEntity(
    @PrimaryKey val time: Long, // Unix timestamp in seconds
    val symbol: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val tickCount: Int
)
