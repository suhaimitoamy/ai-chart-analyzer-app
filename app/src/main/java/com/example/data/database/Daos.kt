package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TradingMethodDao {
    @Query("SELECT * FROM trading_method")
    fun getAllMethods(): Flow<List<TradingMethodEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(method: TradingMethodEntity): Long

    @Update
    suspend fun update(method: TradingMethodEntity)
}

@Dao
interface TradeHistoryDao {
    @Query("SELECT * FROM trade_history ORDER BY timestamp DESC")
    fun getAllTrades(): Flow<List<TradeHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trade: TradeHistoryEntity)

    @Update
    suspend fun update(trade: TradeHistoryEntity)
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet WHERE id = 1")
    fun getWallet(): Flow<WalletEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wallet: WalletEntity)
}

@Dao
interface IctAnalysisDao {
    @Query("SELECT * FROM ict_analysis ORDER BY id DESC")
    fun getAll(): Flow<List<IctAnalysisEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: IctAnalysisEntity)
    
    @Query("DELETE FROM ict_analysis WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface CandleDao {
    @Query("SELECT * FROM candle_history WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY time ASC")
    fun getAllCandles(symbol: String, timeframe: String): Flow<List<CandleEntity>>

    @Query("SELECT * FROM candle_history WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY time DESC LIMIT :limit")
    suspend fun getRecentCandles(symbol: String, timeframe: String, limit: Int): List<CandleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(candle: CandleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candles: List<CandleEntity>)
}
