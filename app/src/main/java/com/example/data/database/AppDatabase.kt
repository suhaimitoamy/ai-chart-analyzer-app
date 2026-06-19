package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TradingMethodEntity::class, TradeHistoryEntity::class, WalletEntity::class, IctAnalysisEntity::class, CandleEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun methodDao(): TradingMethodDao
    abstract fun tradeDao(): TradeHistoryDao
    abstract fun walletDao(): WalletDao
    abstract fun ictAnalysisDao(): IctAnalysisDao
    abstract fun candleDao(): CandleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trading_bot_database"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
