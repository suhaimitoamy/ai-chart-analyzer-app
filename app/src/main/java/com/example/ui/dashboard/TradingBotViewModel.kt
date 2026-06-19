package com.example.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.SettingsManager
import com.example.data.database.IctAnalysisEntity
import com.example.data.database.TradeHistoryEntity
import com.example.data.database.WalletEntity
import com.example.data.database.TradingMethodEntity
import com.example.data.network.DeepSeekClient
import com.example.data.network.TwelveDataClient
import com.example.data.network.CandleBuilder
import com.example.data.database.CandleEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class TradingBotViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    
    val settings = SettingsManager(application)
    
    private var twelveClient: TwelveDataClient? = null
    private var aiClient = DeepSeekClient("")
    private val candleBuilder = CandleBuilder()

    val wallet: StateFlow<WalletEntity> = db.walletDao().getWallet()
        .map { it ?: WalletEntity() }
        .stateIn(viewModelScope, SharingStarted.Lazily, WalletEntity())

    val trades: StateFlow<List<TradeHistoryEntity>> = db.tradeDao().getAllTrades()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        
    val methods: StateFlow<List<TradingMethodEntity>> = db.methodDao().getAllMethods()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val ictAnalyses: StateFlow<List<IctAnalysisEntity>> = db.ictAnalysisDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dbCandles: StateFlow<List<CandleEntity>> = db.candleDao().getAllCandles("XAU/USD", "M1")
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing = _isAnalyzing.asStateFlow()

    private val _analysisResultJson = MutableStateFlow<String?>(null)
    val analysisResultJson = _analysisResultJson.asStateFlow()

    private val _analysisErrorText = MutableStateFlow<String?>(null)
    val analysisErrorText = _analysisErrorText.asStateFlow()

    private val _botStatus = MutableStateFlow("Disconnected")
    val botStatus = _botStatus.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private var m1ClosedCount = 0

    init {
        viewModelScope.launch {
            if (db.walletDao().getWallet().firstOrNull() == null) {
                db.walletDao().insert(WalletEntity())
            }
            if (db.methodDao().getAllMethods().firstOrNull()?.isEmpty() == true) {
                db.methodDao().insert(TradingMethodEntity(name = "Price Action", description = "Standard PA"))
                db.methodDao().insert(TradingMethodEntity(name = "SMC / ICT", description = "Smart Money Concepts"))
            }
        }
    }

    fun log(msg: String) {
        _logs.update { (listOf(msg) + it).take(100) }
    }

    fun saveKeys(twelve: String, deepseek: String) {
        settings.twelveApiKey = twelve
        settings.deepseekApiKey = deepseek
    }

    fun startBot() {
        if (_botStatus.value == "Running") return
        if (!settings.areKeysSet()) {
            _botStatus.value = "Error: API Keys not set"
            log("Menunggu API Key di Settings.")
            return
        }

        twelveClient = TwelveDataClient(settings.twelveApiKey)
        aiClient = DeepSeekClient(settings.deepseekApiKey)

        _botStatus.value = "Running"
        viewModelScope.launch {
            log("Fetching Historical Candles for XAU/USD...")
            fetchAndSaveHistoricalCandles()

            log("Connecting to TwelveData WebSocket (XAU/USD)...")
            twelveClient?.connect("XAU/USD")
            log("Real CandleBuilder multi-timeframe aktif. AI hanya berjalan saat tombol Analisis ICT Sekarang diklik.")
             
            launch {
                candleBuilder.candleClosed.collect { candle ->
                    val entity = CandleEntity(
                        time = candle.time,
                        symbol = candle.symbol,
                        timeframe = candle.timeframe,
                        open = candle.open,
                        high = candle.high,
                        low = candle.low,
                        close = candle.close,
                        tickCount = candle.tickCount,
                        closeTime = candle.closeTime,
                        isClosed = candle.isClosed
                    )
                    db.candleDao().insert(entity)

                    if (candle.timeframe != "M1") {
                        log("${candle.timeframe} closed. C:${formatPrice(candle.close)} Ticks:${candle.tickCount}")
                    } else {
                        m1ClosedCount++
                        if (m1ClosedCount % 5 == 0) {
                            log("M1 #$m1ClosedCount closed. C:${formatPrice(candle.close)}")
                        }
                    }
                }
            }

            launch {
                var lastPrice = 0.0
                var lastWarningTime = 0L
                twelveClient?.ticks?.collect { tick ->
                    val priceStr = tick.optString("price")
                    val price = priceStr.toDoubleOrNull()
                    val timestamp = tick.optLong("timestamp", System.currentTimeMillis() / 1000)
                    if (price != null && isValidXauPrice(price)) {
                        candleBuilder.processTick("XAU/USD", price, timestamp)
                        
                        val currentTime = System.currentTimeMillis()
                        if (lastPrice > 0) {
                            val diff = price - lastPrice
                            if (Math.abs(diff) >= 3.0 && currentTime - lastWarningTime >= 300_000) {
                                lastWarningTime = currentTime
                                val dirStr = if (diff > 0) "MELONJAK NAIK 🚀" else "ANJLOK TURUN 🩸"
                                val msg = """
                                    🚨 WARNING: IMPULSIVE MOVE DETECTED!
                                    XAU/USD $dirStr tajam.
                                    Pergerakan: ${String.format(Locale.US, "%.2f", Math.abs(diff))} poin
                                    Harga saat ini: $price
                                """.trimIndent()
                                log(msg)
                            }
                        }
                        lastPrice = price
                    }
                }
            }
        }
    }

    private suspend fun fetchAndSaveHistoricalCandles() {
        val client = twelveClient ?: return
        val requests = listOf(
            Triple("1min", "M1", 500),
            Triple("5min", "M5", 300),
            Triple("15min", "M15", 250),
            Triple("30min", "M30", 200),
            Triple("1h", "H1", 200),
            Triple("4h", "H4", 120),
            Triple("1day", "D1", 90),
            Triple("1week", "W1", 40)
        )

        var totalSaved = 0
        requests.forEach { (interval, timeframe, outputSize) ->
            try {
                val history = client.fetchHistoricalCandles("XAU/USD", interval, outputSize)
                if (history.isNotEmpty()) {
                    db.candleDao().insertAll(history)
                    totalSaved += history.size
                    log("REST $timeframe: ${history.size} candle tersimpan.")
                } else {
                    log("REST $timeframe: 0 candle.")
                }
            } catch (e: Exception) {
                log("REST $timeframe gagal: ${e.message}")
            }
        }

        if (totalSaved > 0) {
            log("Historical candle siap: $totalSaved candle tersimpan ke database.")
        } else {
            log("Historical candle belum masuk. Cek TwelveData API Key / limit / symbol XAU/USD.")
        }
    }
    
    fun stopBot() {
        _botStatus.value = "Disconnected"
        twelveClient?.disconnect()
        log("Bot stopped.")
    }
    
    fun deleteIctAnalysis(id: Int) {
        viewModelScope.launch {
            db.ictAnalysisDao().delete(id)
        }
    }
    
    fun analyzeIct(timeframe: String, session: String, notes: String) {
        if (_isAnalyzing.value) return
        _isAnalyzing.value = true
        _analysisResultJson.value = null
        _analysisErrorText.value = null
        viewModelScope.launch {
            try {
                if (settings.deepseekApiKey.isBlank()) {
                    _analysisErrorText.value = "DeepSeek API Key belum diisi. Buka Settings untuk mengisi API key."
                    log("Analysis failed: API key kosong.")
                    return@launch
                }

                aiClient = DeepSeekClient(settings.deepseekApiKey)
                val selectedTimeframe = normalizeTimeframe(timeframe)
                val mtfTimeframe = getMtfTimeframe(selectedTimeframe)
                val htfTimeframe = getHtfTimeframe(selectedTimeframe)
                
                val ltfCandles = loadCandlesForAnalysis(selectedTimeframe)
                val mtfCandles = loadCandlesForAnalysis(mtfTimeframe)
                val htfCandles = loadCandlesForAnalysis(htfTimeframe)
                
                if (ltfCandles.isEmpty() || mtfCandles.isEmpty() || htfCandles.isEmpty()) {
                    _analysisErrorText.value = "Candle belum tersedia secara lengkap (Butuh $selectedTimeframe, $mtfTimeframe, $htfTimeframe). Tunggu sinkronisasi."
                    log("Analysis failed: candle belum lengkap untuk MTF.")
                    return@launch
                }

                val candlesMap = mapOf("LTF" to ltfCandles, "MTF" to mtfCandles, "HTF" to htfCandles)
                val prompt = buildIctPrompt(selectedTimeframe, session, notes, candlesMap)
                val responseStr = aiClient.analyzeChart(prompt)

                if (responseStr.startsWith("DeepSeek API error") || responseStr.startsWith("Network error") || responseStr.startsWith("DeepSeek response") || responseStr.startsWith("DeepSeek API key kosong")) {
                    _analysisErrorText.value = responseStr
                    log("Analysis error. Lihat tab Analyze.")
                    return@launch
                }

                val cleanJson = extractJsonObject(responseStr)
                if (cleanJson == null) {
                    _analysisErrorText.value = "AI tidak mengembalikan JSON valid.\nResponse: ${responseStr.take(200)}"
                    log("Analysis failed: response bukan JSON.")
                    return@launch
                }

                val json = JSONObject(cleanJson)
                val currentPrice = json.optDouble("current_price", recentCandles.last().close)
                val newAnalysis = IctAnalysisEntity(
                    timeframe = json.optString("timeframe", selectedTimeframe),
                    session = json.optString("session_context", session),
                    bias = json.optString("bias", "NEUTRAL"),
                    confidence = json.optInt("confidence_score", 0),
                    price = "$${formatPrice(currentPrice)}",
                    date = java.text.SimpleDateFormat("dd MMM yyyy, HH.mm", Locale.getDefault()).format(java.util.Date()),
                    rawResult = cleanJson
                )
                db.ictAnalysisDao().insert(newAnalysis)
                _analysisResultJson.value = cleanJson
                log("ICT Analysis saved.")
            } catch (e: Exception) {
                _analysisErrorText.value = "Terjadi kesalahan: ${e.message}"
                log("Analysis exception. Lihat tab Analyze.")
            } finally {
                _isAnalyzing.value = false
            }
        }
    }
    
    fun uploadDataFile(uri: android.net.Uri) {
        log("Uploading data file: ${uri.lastPathSegment}...")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val reader = inputStream?.bufferedReader()
                var lineCount = 0
                reader?.useLines { lines ->
                    lineCount = lines.count()
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    log("Parsed $lineCount lines from ${uri.lastPathSegment}.")
                    log("AI tidak dipanggil otomatis dari upload file. Klik Analisis ICT Sekarang untuk menjalankan AI manual.")
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    log("Error reading file: ${e.message}")
                }
            }
        }
    }

    private suspend fun loadCandlesForAnalysis(timeframe: String): List<CandleEntity> {
        val target = normalizeTimeframe(timeframe)
        val stored = db.candleDao().getRecentCandles("XAU/USD", target, 120).reversed()
        if (stored.isNotEmpty()) return stored

        val sourceOrder = when (target) {
            "M1" -> listOf("M1")
            "M5" -> listOf("M1")
            "M15" -> listOf("M1", "M5")
            "M30" -> listOf("M1", "M5", "M15")
            "H1" -> listOf("M1", "M5", "M15", "M30")
            "H4" -> listOf("M1", "M5", "M15", "M30", "H1")
            "D1" -> listOf("M5", "M15", "M30", "H1", "H4")
            "W1" -> listOf("H1", "H4", "D1")
            else -> listOf("M1")
        }

        for (sourceTimeframe in sourceOrder) {
            val sourceSeconds = timeframeToSeconds(sourceTimeframe)
            val targetSeconds = timeframeToSeconds(target)
            val multiplier = (targetSeconds / sourceSeconds).toInt().coerceAtLeast(1)
            val limit = (120 * multiplier + multiplier * 5).coerceAtMost(15000)
            val sourceCandles = db.candleDao().getRecentCandles("XAU/USD", sourceTimeframe, limit).reversed()
            if (sourceCandles.isNotEmpty()) {
                val aggregated = if (sourceTimeframe == target) sourceCandles else aggregateCandles(sourceCandles, target)
                if (aggregated.isNotEmpty()) return aggregated.takeLast(120)
            }
        }

        return emptyList()
    }

    private fun aggregateCandles(sourceCandles: List<CandleEntity>, timeframe: String): List<CandleEntity> {
        if (sourceCandles.isEmpty()) return emptyList()
        val seconds = timeframeToSeconds(timeframe)
        return sourceCandles
            .groupBy { (it.time / seconds) * seconds }
            .toSortedMap()
            .map { (openTs, rows) ->
                val sorted = rows.sortedBy { it.time }
                CandleEntity(
                    time = openTs,
                    symbol = "XAU/USD",
                    timeframe = timeframe,
                    open = sorted.first().open,
                    high = sorted.maxOf { it.high },
                    low = sorted.minOf { it.low },
                    close = sorted.last().close,
                    tickCount = sorted.sumOf { it.tickCount },
                    closeTime = openTs + seconds - 1,
                    isClosed = true
                )
            }
    }

    private fun buildIctPrompt(
        timeframe: String,
        session: String,
        notes: String,
        candlesMap: Map<String, List<CandleEntity>>
    ): String {
        val marketSnapshot = buildLocalMarketSnapshot(candlesMap, timeframe, session)
        return """
Kamu adalah ICT (Inner Circle Trader) expert analyst profesional khusus XAUUSD (Gold/USD).

AI hanya menerima ringkasan dari Local Engine agar token ringan. Jangan minta data tambahan.
Gunakan ringkasan candle real berikut untuk mapping chart:

$marketSnapshot

${if (notes.isNotEmpty()) "Catatan trader: $notes" else "Catatan trader: -"}

Tugas:
1. Tentukan bias: BULLISH, BEARISH, atau NEUTRAL.
2. Jelaskan struktur market secara ringkas.
3. Tentukan zona penting: liquidity, FVG, order block, premium/discount.
4. Berikan setup hanya jika valid; jika belum valid, tulis wait.
5. Sertakan market_structure, order_blocks, fvg, liquidity, premium_discount, trade_setup, key_notes, dan warnings.

Output wajib JSON valid saja, tanpa markdown, tanpa teks tambahan, dengan struktur:
{
  "bias": "BULLISH/BEARISH/NEUTRAL",
  "confidence_score": 0,
  "timeframe": "$timeframe",
  "session_context": "$session",
  "current_price": 0.0,
  "daily_bias_summary": "ringkasan",
  "trade_setup": {
    "status": "valid/wait",
    "entry_zone": "area harga",
    "tp1": 0.0,
    "tp2": 0.0,
    "stop_loss": 0.0,
    "risk_reward": "rasio",
    "invalidation": "level invalidasi"
  },
  "market_structure": {
    "trend": "Bullish/Bearish/Range",
    "last_bos": "BOS terakhir",
    "choch": "CHoCH/MSS terakhir",
    "swing_high": 0.0,
    "swing_low": 0.0,
    "liquidity": "ringkasan",
    "fvg": "ringkasan",
    "order_block": "ringkasan",
    "premium_discount": "ringkasan"
  },
  "order_blocks": {
    "bullish_ob": "area bullish OB",
    "bearish_ob": "area bearish OB",
    "description": "catatan OB"
  },
  "fvg": {
    "bullish_fvg": "area bullish FVG",
    "bearish_fvg": "area bearish FVG",
    "description": "catatan FVG"
  },
  "liquidity": {
    "buy_side": "BSL terdekat",
    "sell_side": "SSL terdekat",
    "sweep_occurred": false,
    "description": "catatan liquidity"
  },
  "premium_discount": {
    "equilibrium": 0.0,
    "current_zone": "PREMIUM/DISCOUNT/EQUILIBRIUM",
    "ote_zone": "62-79% zone"
  },
  "key_notes": ["catatan penting"],
  "warnings": ["peringatan risiko"]
}
""".trimIndent()
    }

    private fun buildLocalMarketSnapshot(
        candlesMap: Map<String, List<CandleEntity>>,
        timeframe: String,
        session: String
    ): String {
        val ltfCandles = candlesMap["LTF"] ?: emptyList()
        val mtfCandles = candlesMap["MTF"] ?: emptyList()
        val htfCandles = candlesMap["HTF"] ?: emptyList()
        
        val recentLtf = ltfCandles.takeLast(60)
        val recentMtf = mtfCandles.takeLast(60)
        val recentHtf = htfCandles.takeLast(60)
        
        val latest = recentLtf.last()
        val currentPrice = latest.close
        
        val bias = calculateBias(recentHtf)
        val htfHigh = recentHtf.maxOf { it.high }
        val htfLow = recentHtf.minOf { it.low }
        val equilibrium = (htfHigh + htfLow) / 2.0
        val premiumDiscount = when {
            currentPrice > equilibrium -> "Premium"
            currentPrice < equilibrium -> "Discount"
            else -> "Equilibrium"
        }

        val mtfSwings = getSwings(recentMtf)
        val structure = buildStructureState(recentMtf, mtfSwings.first, mtfSwings.second, bias, null, null, isChoppy(recentMtf))

        val ltfSwings = getSwings(recentLtf)
        val nearestSupport = ltfSwings.second.map { it.low }.filter { it < currentPrice }.maxOrNull()
        val nearestResistance = ltfSwings.first.map { it.high }.filter { it > currentPrice }.minOrNull()
        val atr = calculateAtr(recentLtf)
        val choppy = isChoppy(recentLtf)
        val high20 = ltfSwings.first.takeLast(2).maxOfOrNull { it.high } ?: recentLtf.takeLast(20).maxOf { it.high }
        val low20 = ltfSwings.second.takeLast(2).minOfOrNull { it.low } ?: recentLtf.takeLast(20).minOf { it.low }
        val ltfHigh60 = recentLtf.maxOf { it.high }
        val ltfLow60 = recentLtf.minOf { it.low }
        
        val candlePack = recentLtf.takeLast(20).joinToString(separator = "\n") { candle ->
            "${candle.time}: O=${formatPrice(candle.open)}, H=${formatPrice(candle.high)}, L=${formatPrice(candle.low)}, C=${formatPrice(candle.close)}, ticks=${candle.tickCount}"
        }

        return """
Symbol: XAU/USD
Requested timeframe: $timeframe
Session: $session
Closed candles used: ${recentLtf.size}
Current price: ${formatPrice(currentPrice)}
Market bias: $bias
Market phase: ${structure["phase"]}
Break / MSS: ${structure["break"]}
Retest mode: ${structure["retest"]}
Momentum: ${structure["momentum"]}
ATR-like range: ${formatPrice(atr)}
Choppy: $choppy
Nearest resistance: ${formatPriceOrDash(nearestResistance)}
Nearest support: ${formatPriceOrDash(nearestSupport)}
Last 20 swing high: ${formatPrice(high20)}
Last 20 swing low: ${formatPrice(low20)}
60 candle range high: ${formatPrice(ltfHigh60)}
60 candle range low: ${formatPrice(ltfLow60)}
HTF Range high: ${formatPrice(htfHigh)}
HTF Range low: ${formatPrice(htfLow)}
Equilibrium: ${formatPrice(equilibrium)}
Current zone: $premiumDiscount
Liquidity: ${structure["liquidity"]}
FVG: ${findLatestFvg(recentLtf)}
Order block reference: ${findOrderBlockReference(recentLtf)}

Last 20 closed candles:
$candlePack
""".trimIndent()
    }

    private fun getSwings(candles: List<CandleEntity>, left: Int = 2, right: Int = 2): Pair<List<CandleEntity>, List<CandleEntity>> {
        if (candles.size < left + right + 1) return Pair(emptyList(), emptyList())
        val highs = mutableListOf<CandleEntity>()
        val lows = mutableListOf<CandleEntity>()
        for (i in left until candles.size - right) {
            var isHigh = true
            var isLow = true
            for (j in 1..left) {
                if (candles[i - j].high >= candles[i].high) isHigh = false
                if (candles[i - j].low <= candles[i].low) isLow = false
            }
            for (j in 1..right) {
                if (candles[i + j].high >= candles[i].high) isHigh = false
                if (candles[i + j].low <= candles[i].low) isLow = false
            }
            if (isHigh) highs.add(candles[i])
            if (isLow) lows.add(candles[i])
        }
        return Pair(highs, lows)
    }

    private fun calculateBias(candles: List<CandleEntity>): String {
        if (candles.size < 10) return "neutral"
        val (highs, lows) = getSwings(candles)
        if (highs.size >= 2 && lows.size >= 2) {
            val h2 = highs[highs.size - 2].high
            val h1 = highs.last().high
            val l2 = lows[lows.size - 2].low
            val l1 = lows.last().low
            val hh = h1 > h2
            val hl = l1 > l2
            val lh = h1 < h2
            val ll = l1 < l2
            if (hh && hl) return "bullish"
            if (lh && ll) return "bearish"
            if (hh && ll) return "expanding"
            if (lh && hl) return "choppy"
        }
        val closes = candles.takeLast(5).map { it.close }
        val avg = closes.average()
        return if (candles.last().close > avg) "bullish" else "bearish"
    }

    private fun isChoppy(candles: List<CandleEntity>): Boolean {
        if (candles.size < 5) return false
        val dojiCount = candles.takeLast(5).count { candle ->
            val body = Math.abs(candle.open - candle.close)
            val range = candle.high - candle.low
            range > 0 && body / range < 0.3
        }
        return dojiCount >= 3
    }

    private fun calculateAtr(candles: List<CandleEntity>): Double {
        val ranges = candles.takeLast(14).map { it.high - it.low }
        return if (ranges.isEmpty()) 0.0 else ranges.average()
    }

    private fun buildStructureState(
        candles: List<CandleEntity>,
        swingHighs: List<CandleEntity>,
        swingLows: List<CandleEntity>,
        bias: String,
        nearestSupport: Double?,
        nearestResistance: Double?,
        choppy: Boolean
    ): Map<String, String> {
        val latest = candles.last()
        val body = Math.abs(latest.open - latest.close)
        val range = latest.high - latest.low
        val momentum = if (range > 0 && body / range >= 0.5) {
            if (latest.close > latest.open) "bullish" else "bearish"
        } else {
            "neutral"
        }

        var liquidity = "No fresh liquidity sweep on latest closed candle"
        if (swingLows.isNotEmpty()) {
            val recentLow = swingLows.last().low
            if (latest.low < recentLow && latest.close > recentLow) {
                liquidity = "Sell-side liquidity swept at ${formatPrice(recentLow)}, reclaimed at ${formatPrice(latest.close)}"
            }
        }
        if (swingHighs.isNotEmpty() && liquidity.startsWith("No fresh")) {
            val recentHigh = swingHighs.last().high
            if (latest.high > recentHigh && latest.close < recentHigh) {
                liquidity = "Buy-side liquidity swept at ${formatPrice(recentHigh)}, reclaimed at ${formatPrice(latest.close)}"
            }
        }

        var breakType = "None"
        var breakLevel: Double? = null
        if (swingHighs.isNotEmpty() && latest.close > swingHighs.last().high) {
            breakLevel = swingHighs.last().high
            breakType = if (bias == "bearish") "MSS_BULLISH" else "BOS_BULLISH"
        } else if (swingLows.isNotEmpty() && latest.close < swingLows.last().low) {
            breakLevel = swingLows.last().low
            breakType = if (bias == "bullish") "MSS_BEARISH" else "BOS_BEARISH"
        }

        val middleOfRange = if (nearestSupport != null && nearestResistance != null) {
            val fullRange = nearestResistance - nearestSupport
            val position = if (fullRange > 0) (latest.close - nearestSupport) / fullRange else 0.0
            position in 0.4..0.6
        } else {
            false
        }

        val phase = when {
            choppy -> "CHOPPY"
            breakType != "None" -> "EXPANSION"
            middleOfRange -> "RANGING"
            bias == "bullish" -> "PULLBACK_OR_MARKUP"
            bias == "bearish" -> "PULLBACK_OR_MARKDOWN"
            else -> "RANGING"
        }

        val retest = if (breakType != "None" && breakLevel != null) {
            val distance = Math.abs(latest.close - breakLevel)
            if (distance > 3.0) "WAIT_PULLBACK_TO_${formatPrice(breakLevel)}" else "ACTIVE_RETEST_$breakType"
        } else {
            "NONE"
        }

        return mapOf(
            "phase" to phase,
            "break" to if (breakLevel != null) "$breakType at ${formatPrice(breakLevel)}" else breakType,
            "retest" to retest,
            "momentum" to momentum,
            "liquidity" to liquidity
        )
    }

    private fun findLatestFvg(candles: List<CandleEntity>): String {
        if (candles.size < 3) return "Not enough candles"
        for (i in candles.size - 1 downTo 2) {
            val left = candles[i - 2]
            val right = candles[i]
            if (left.high < right.low) {
                return "Bullish FVG ${formatPrice(left.high)} - ${formatPrice(right.low)}"
            }
            if (left.low > right.high) {
                return "Bearish FVG ${formatPrice(right.high)} - ${formatPrice(left.low)}"
            }
        }
        return "No clear FVG in recent candles"
    }

    private fun findOrderBlockReference(candles: List<CandleEntity>): String {
        val recent = candles.takeLast(20)
        val bullishOb = recent.lastOrNull { it.close < it.open }
        val bearishOb = recent.lastOrNull { it.close > it.open }
        val bullishText = bullishOb?.let { "Bullish OB ref ${formatPrice(it.low)} - ${formatPrice(it.high)}" } ?: "Bullish OB not found"
        val bearishText = bearishOb?.let { "Bearish OB ref ${formatPrice(it.low)} - ${formatPrice(it.high)}" } ?: "Bearish OB not found"
        return "$bullishText | $bearishText"
    }

    private fun extractJsonObject(raw: String): String? {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private fun isValidXauPrice(price: Double): Boolean {
        return price in 1000.0..5000.0
    }

    private fun getMtfTimeframe(ltf: String): String {
        return when (ltf) {
            "M1" -> "M15"
            "M5" -> "M30"
            "M15" -> "H1"
            "M30" -> "H4"
            "H1" -> "H4"
            "H4", "D1", "W1" -> "D1"
            else -> "M15"
        }
    }

    private fun getHtfTimeframe(ltf: String): String {
        return when (ltf) {
            "M1", "M5" -> "H1"
            "M15" -> "H4"
            "M30", "H1" -> "D1"
            "H4", "D1", "W1" -> "W1"
            else -> "H1"
        }
    }

    private fun normalizeTimeframe(timeframe: String): String {
        return when (timeframe.uppercase(Locale.US)) {
            "M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1" -> timeframe.uppercase(Locale.US)
            else -> "M1"
        }
    }

    private fun timeframeToSeconds(timeframe: String): Long {
        return when (normalizeTimeframe(timeframe)) {
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

    private fun formatPrice(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun formatPriceOrDash(value: Double?): String {
        return value?.let { formatPrice(it) } ?: "-"
    }

    fun generatePdfReport(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val allTrades = db.tradeDao().getAllTrades().firstOrNull() ?: emptyList()
            val wins = allTrades.count { it.result == "WIN" }
            val losses = allTrades.count { it.result == "LOSS" }
            val total = wins + losses
            val winRate = if (total > 0) (wins.toFloat() / total) * 100 else 0f
            var grossProfit = 0.0
            var grossLoss = 0.0
            for (trade in allTrades) {
                if (trade.result == "WIN") {
                    grossProfit += Math.abs(trade.takeProfit - trade.entryPrice)
                } else if (trade.result == "LOSS") {
                    grossLoss += Math.abs(trade.entryPrice - trade.stopLoss)
                }
            }
            val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 99.99 else 0.0
            val avgDrawdown = if (losses > 0) grossLoss / losses else 0.0
            try {
                val file = PdfGenerator.createReport(
                    context, wins, losses, winRate, profitFactor, avgDrawdown, allTrades.size
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    log("PDF Report generated: ${file.name}")
                    log("📊 Performance PDF Report generated locally: ${file.name}")
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    log("Failed to generate PDF: ${e.message}")
                }
            }
        }
    }
}
