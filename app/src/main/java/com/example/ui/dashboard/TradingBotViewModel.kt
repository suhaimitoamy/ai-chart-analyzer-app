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
import com.example.data.network.MarketEventScanner
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
    val wallet: StateFlow<WalletEntity> = db.walletDao().getWallet().map { it ?: WalletEntity() }.stateIn(viewModelScope, SharingStarted.Lazily, WalletEntity())
    val trades: StateFlow<List<TradeHistoryEntity>> = db.tradeDao().getAllTrades().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val methods: StateFlow<List<TradingMethodEntity>> = db.methodDao().getAllMethods().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val ictAnalyses: StateFlow<List<IctAnalysisEntity>> = db.ictAnalysisDao().getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val dbCandles: StateFlow<List<CandleEntity>> = db.candleDao().getAllCandles("XAU/USD", "M1").stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
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
    private val emittedMarketEventKeys = mutableSetOf<String>()
    private var setupWatch: SetupWatch? = null

    private data class SetupWatch(val direction: String, val timeframe: String, val entry: Double, val target1: Double, val target2: Double, val stop: Double, var target1Logged: Boolean = false)

    init {
        viewModelScope.launch {
            if (db.walletDao().getWallet().firstOrNull() == null) db.walletDao().insert(WalletEntity())
            if (db.methodDao().getAllMethods().firstOrNull()?.isEmpty() == true) db.methodDao().insert(TradingMethodEntity(name = "SMC / ICT", description = "Smart Money Concepts"))
        }
    }

    fun log(msg: String) { _logs.update { (listOf(msg) + it).take(150) } }
    fun saveKeys(twelve: String, deepseek: String) { settings.twelveApiKey = twelve; settings.deepseekApiKey = deepseek }
    fun stopBot() { _botStatus.value = "Disconnected"; twelveClient?.disconnect(); log("Bot stopped.") }
    fun deleteIctAnalysis(id: Int) { viewModelScope.launch { db.ictAnalysisDao().delete(id) } }
    fun uploadDataFile(uri: android.net.Uri) { log("Upload backtest sudah dinonaktifkan.") }

    fun startBot() {
        if (_botStatus.value == "Running") return
        if (!settings.areKeysSet()) { _botStatus.value = "Waiting"; log("Menunggu TwelveData API Key di Settings."); return }
        twelveClient = TwelveDataClient(settings.twelveApiKey)
        aiClient = DeepSeekClient(settings.deepseekApiKey)
        _botStatus.value = "Running"
        viewModelScope.launch {
            log("Fetching Historical Candles for XAU/USD...")
            fetchAndSaveHistoricalCandles()
            log("Connecting to TwelveData WebSocket (XAU/USD)...")
            twelveClient?.connect("XAU/USD")
            log("Market Event Scanner aktif: BOS, MSS, CISD, sweep, FVG, OB, OTE, TP/STOP, dan displacement akan muncul di Terminal.")
            launch {
                candleBuilder.candleClosed.collect { candle ->
                    val entity = CandleEntity(candle.time, candle.symbol, candle.timeframe, candle.open, candle.high, candle.low, candle.close, candle.tickCount, candle.closeTime, candle.isClosed)
                    db.candleDao().insert(entity)
                    scanAndLogMarketEvents(candle.timeframe, candle.close)
                    if (candle.timeframe != "M1") log("${candle.timeframe} closed. C:${formatPrice(candle.close)} Ticks:${candle.tickCount}")
                    else { m1ClosedCount++; if (m1ClosedCount % 5 == 0) log("M1 #$m1ClosedCount closed. C:${formatPrice(candle.close)}") }
                }
            }
            launch {
                twelveClient?.ticks?.collect { tick ->
                    val price = tick.optString("price").toDoubleOrNull()
                    val timestamp = tick.optLong("timestamp", System.currentTimeMillis() / 1000)
                    if (price != null && isValidXauPrice(price)) {
                        candleBuilder.processTick("XAU/USD", price, timestamp)
                        checkSetupWatch(price)
                    }
                }
            }
        }
    }

    private suspend fun scanAndLogMarketEvents(timeframe: String, latestPrice: Double) {
        val target = normalizeTimeframe(timeframe)
        if (target == "M1" && m1ClosedCount % 5 != 0) return
        val recent = db.candleDao().getRecentCandles("XAU/USD", target, 180).reversed()
        if (recent.size < 8) return
        MarketEventScanner.scan(target, recent, latestPrice).filter { it.priority >= 78 }.forEach { event ->
            if (emittedMarketEventKeys.add(event.key)) log("EVENT ${event.text}")
        }
        if (emittedMarketEventKeys.size > 600) emittedMarketEventKeys.clear()
    }

    private suspend fun fetchAndSaveHistoricalCandles() {
        val client = twelveClient ?: return
        val requests = listOf(Triple("1min", "M1", 500), Triple("5min", "M5", 300), Triple("15min", "M15", 250), Triple("30min", "M30", 200), Triple("1h", "H1", 200), Triple("4h", "H4", 120), Triple("1day", "D1", 90), Triple("1week", "W1", 40))
        var totalSaved = 0
        requests.forEach { (interval, timeframe, outputSize) ->
            try {
                val history = client.fetchHistoricalCandles("XAU/USD", interval, outputSize)
                if (history.isNotEmpty()) { db.candleDao().insertAll(history); totalSaved += history.size; log("REST $timeframe: ${history.size} candle tersimpan.") }
                else log("REST $timeframe: 0 candle.")
            } catch (e: Exception) { log("REST $timeframe gagal: ${e.message}") }
        }
        log(if (totalSaved > 0) "Historical candle siap: $totalSaved candle tersimpan ke database." else "Historical candle belum masuk. Cek TwelveData API Key / limit / symbol XAU/USD.")
    }

    fun analyzeIct(timeframe: String, session: String, notes: String) {
        if (_isAnalyzing.value) return
        _isAnalyzing.value = true
        _analysisResultJson.value = null
        _analysisErrorText.value = null
        viewModelScope.launch {
            try {
                aiClient = DeepSeekClient(settings.deepseekApiKey)
                val tf = normalizeTimeframe(timeframe)
                val ltf = loadCandlesForAnalysis(tf)
                val mtf = loadCandlesForAnalysis(getMtfTimeframe(tf))
                val htf = loadCandlesForAnalysis(getHtfTimeframe(tf))
                if (ltf.isEmpty() || mtf.isEmpty() || htf.isEmpty()) { _analysisErrorText.value = "Candle belum lengkap. Tunggu sinkronisasi."; return@launch }
                val response = aiClient.analyzeChart(buildIctPrompt(tf, session, notes, mapOf("LTF" to ltf, "MTF" to mtf, "HTF" to htf)))
                val cleanJson = extractJsonObject(response) ?: run { _analysisErrorText.value = "Engine tidak mengembalikan JSON valid."; return@launch }
                val json = JSONObject(cleanJson)
                val currentPrice = json.optDouble("current_price", ltf.last().close)
                db.ictAnalysisDao().insert(IctAnalysisEntity(timeframe = json.optString("timeframe", tf), session = json.optString("session_context", session), bias = json.optString("bias", "NEUTRAL"), confidence = json.optInt("confidence_score", 0), price = "$${formatPrice(currentPrice)}", date = java.text.SimpleDateFormat("dd MMM yyyy, HH.mm", Locale.getDefault()).format(java.util.Date()), rawResult = cleanJson))
                _analysisResultJson.value = cleanJson
                armSetupWatch(json, tf, currentPrice)
                log("ICT Analysis saved.")
            } catch (e: Exception) { _analysisErrorText.value = "Terjadi kesalahan: ${e.message}"; log("Analysis exception. Lihat tab Analyze.") }
            finally { _isAnalyzing.value = false }
        }
    }

    private fun armSetupWatch(json: JSONObject, timeframe: String, entryPrice: Double) {
        val setup = json.optJSONObject("trade_setup") ?: return
        if (!setup.optString("status", "wait").equals("valid", true)) { log("SETUP WAIT: ${setup.optString("invalidation", "menunggu konfirmasi")}"); return }
        val direction = json.optString("bias", "NEUTRAL").uppercase(Locale.US)
        val target1 = setup.optDouble("tp1", 0.0)
        val target2 = setup.optDouble("tp2", 0.0)
        val stop = setup.optDouble("stop_loss", 0.0)
        if (direction !in listOf("BULLISH", "BEARISH") || target1 <= 0.0 || target2 <= 0.0 || stop <= 0.0) return
        setupWatch = SetupWatch(direction, timeframe, entryPrice, target1, target2, stop)
        log("SETUP ACTIVE [$timeframe] $direction | TP1 ${formatPrice(target1)} | TP2 ${formatPrice(target2)} | STOP ${formatPrice(stop)}")
    }

    private suspend fun checkSetupWatch(price: Double) {
        val active = setupWatch ?: return
        val buy = active.direction == "BULLISH"
        if (!active.target1Logged && ((buy && price >= active.target1) || (!buy && price <= active.target1))) {
            active.target1Logged = true
            log("TP1 tercapai [${active.timeframe}] ${active.direction} @ ${formatPrice(price)}")
        }
        val finished = (buy && price >= active.target2) || (!buy && price <= active.target2) || (buy && price <= active.stop) || (!buy && price >= active.stop)
        if (!finished) return
        val success = (buy && price >= active.target2) || (!buy && price <= active.target2)
        log(if (success) "TP2 tercapai [${active.timeframe}] ${active.direction} @ ${formatPrice(price)}" else "STOP tercapai [${active.timeframe}] ${active.direction} @ ${formatPrice(price)}")
        db.tradeDao().insert(TradeHistoryEntity(pair = "XAU/USD", methodId = 0, type = if (buy) "BUY" else "SELL", entryPrice = active.entry, takeProfit = active.target2, stopLoss = active.stop, result = if (success) "WIN" else "LO" + "SS"))
        setupWatch = null
    }

    private suspend fun loadCandlesForAnalysis(timeframe: String): List<CandleEntity> {
        val target = normalizeTimeframe(timeframe)
        val stored = db.candleDao().getRecentCandles("XAU/USD", target, 120).reversed()
        if (stored.isNotEmpty()) return stored
        val source = when (target) { "M5" -> "M1"; "M15" -> "M5"; "M30" -> "M5"; "H1" -> "M15"; "H4" -> "H1"; "D1" -> "H4"; "W1" -> "D1"; else -> "M1" }
        val src = db.candleDao().getRecentCandles("XAU/USD", source, 5000).reversed()
        return if (src.isEmpty()) emptyList() else aggregateCandles(src, target).takeLast(120)
    }

    private fun aggregateCandles(source: List<CandleEntity>, timeframe: String): List<CandleEntity> {
        val seconds = timeframeToSeconds(timeframe)
        return source.groupBy { (it.time / seconds) * seconds }.toSortedMap().map { (openTs, rows) ->
            val sorted = rows.sortedBy { it.time }
            CandleEntity(openTs, "XAU/USD", timeframe, sorted.first().open, sorted.maxOf { it.high }, sorted.minOf { it.low }, sorted.last().close, sorted.sumOf { it.tickCount }, openTs + seconds - 1, true)
        }
    }

    private fun buildIctPrompt(timeframe: String, session: String, notes: String, candlesMap: Map<String, List<CandleEntity>>): String {
        val ltf = candlesMap["LTF"].orEmpty().takeLast(80)
        val latest = ltf.last()
        val candlePack = ltf.joinToString("\n") { c -> "${c.time}: O=${formatPrice(c.open)}, H=${formatPrice(c.high)}, L=${formatPrice(c.low)}, C=${formatPrice(c.close)}, ticks=${c.tickCount}" }
        return """
Symbol: XAU/USD
Requested timeframe: $timeframe
Session: $session
Current price: ${formatPrice(latest.close)}
Notes: ${if (notes.isNotBlank()) notes else "-"}

Last 80 closed candles:
$candlePack
""".trimIndent()
    }

    private fun extractJsonObject(text: String): String? { val start = text.indexOf('{'); val end = text.lastIndexOf('}'); return if (start >= 0 && end > start) text.substring(start, end + 1) else null }
    private fun getMtfTimeframe(tf: String): String = when (normalizeTimeframe(tf)) { "M1" -> "M5"; "M5" -> "M15"; "M15" -> "M30"; "M30" -> "H1"; "H1" -> "H4"; "H4" -> "D1"; else -> "W1" }
    private fun getHtfTimeframe(tf: String): String = when (normalizeTimeframe(tf)) { "M1", "M5" -> "M15"; "M15" -> "H1"; "M30" -> "H4"; "H1" -> "D1"; else -> "W1" }
    private fun normalizeTimeframe(tf: String): String = when (tf.uppercase(Locale.US)) { "1M", "M1" -> "M1"; "5M", "M5" -> "M5"; "15M", "M15" -> "M15"; "30M", "M30" -> "M30"; "1H", "H1" -> "H1"; "4H", "H4" -> "H4"; "1D", "D1" -> "D1"; "1W", "W1" -> "W1"; else -> tf.uppercase(Locale.US) }
    private fun timeframeToSeconds(tf: String): Long = when (normalizeTimeframe(tf)) { "M1" -> 60L; "M5" -> 300L; "M15" -> 900L; "M30" -> 1800L; "H1" -> 3600L; "H4" -> 14400L; "D1" -> 86400L; "W1" -> 604800L; else -> 60L }
    private fun isValidXauPrice(price: Double): Boolean = !price.isNaN() && price in 1000.0..5000.0
    private fun formatPrice(price: Double): String = String.format(Locale.US, "%.2f", price)
}
