package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DeepSeekClient(private val apiKey: String) {
    private data class Bar(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val ticks: Int)
    private data class Pivot(val index: Int, val level: Double)
    private data class Zone(val kind: String, val side: String, val low: Double, val high: Double, val active: Boolean, val score: Int) {
        fun area(): String = String.format(Locale.US, "%.2f - %.2f", low, high)
        fun label(): String = "${if (active) "ACTIVE" else "CONTEXT"} ${area()}"
    }

    suspend fun analyzeChart(prompt: String): String = withContext(Dispatchers.Default) { compile(prompt) }

    private fun compile(prompt: String): String {
        val timeframe = readText(prompt, "Requested timeframe") ?: "M1"
        val session = readText(prompt, "Session") ?: "Auto Session"
        val bars = parseBars(prompt)
        val price = MarketPriceCache.latestPrice ?: readNumber(prompt, "Current price") ?: bars.lastOrNull()?.close ?: 0.0
        if (bars.size < 12) return waitJson(timeframe, session, price, "Candle belum cukup.")

        val atr = atr(bars).coerceAtLeast(0.50)
        val distance = max(atr * 14.0, 6.0)
        val pivH = pivotsHigh(bars, 3, 1)
        val pivL = pivotsLow(bars, 3, 1)
        val buySide = pivH.map { it.level }.filter { it > price }.minOrNull() ?: bars.maxOf { it.high }
        val sellSide = pivL.map { it.level }.filter { it < price }.maxOrNull() ?: bars.minOf { it.low }
        val rangeHigh = bars.takeLast(80).maxOf { it.high }
        val rangeLow = bars.takeLast(80).minOf { it.low }
        val eq = (rangeHigh + rangeLow) / 2.0
        val currentZone = if (price > eq) "PREMIUM" else if (price < eq) "DISCOUNT" else "EQUILIBRIUM"
        val structure = structureText(bars, pivH, pivL)
        val liquidity = liquidityText(bars, buySide, sellSide, atr)
        val sweep = liquidity.contains("swept", true)
        val fvgZones = detectFvg(bars, price, distance, atr)
        val obZones = detectOb(bars, price, distance)
        val bullFvg = pick(fvgZones, "bullish")
        val bearFvg = pick(fvgZones, "bearish")
        val bullOb = pick(obZones, "bullish")
        val bearOb = pick(obZones, "bearish")
        val bias = when {
            structure.contains("Bullish") -> "BULLISH"
            structure.contains("Bearish") -> "BEARISH"
            bars.takeLast(3).sumOf { it.close - it.open } > 0 -> "BULLISH"
            bars.takeLast(3).sumOf { it.close - it.open } < 0 -> "BEARISH"
            else -> "NEUTRAL"
        }
        val activeBullFvg = bullFvg?.takeIf { it.active }
        val activeBearFvg = bearFvg?.takeIf { it.active }
        val activeBullOb = bullOb?.takeIf { it.active }
        val activeBearOb = bearOb?.takeIf { it.active }
        val setup = setupJson(bias, currentZone, price, sellSide, buySide, rangeLow, rangeHigh, atr, activeBullFvg, activeBearFvg, activeBullOb, activeBearOb)
        val confidence = (45 + if (sweep) 10 else 0 + if (setup.optString("status") == "valid") 20 else 0 + if (listOf(activeBullFvg, activeBearFvg, activeBullOb, activeBearOb).any { it != null }) 15 else 0).coerceIn(25, 90)
        val fvgDesc = listOfNotNull(bullFvg?.let { "Bullish FVG ${it.label()}" }, bearFvg?.let { "Bearish FVG ${it.label()}" }).joinToString(" | ").ifBlank { "Tidak ada FVG" }
        val obDesc = listOfNotNull(bullOb?.let { "Bullish OB ${it.label()}" }, bearOb?.let { "Bearish OB ${it.label()}" }).joinToString(" | ").ifBlank { "Tidak ada OB" }

        return JSONObject()
            .put("bias", bias)
            .put("confidence_score", confidence)
            .put("timeframe", timeframe)
            .put("session_context", session)
            .put("current_price", price)
            .put("daily_bias_summary", "Bias $bias. Harga di $currentZone. BSL ${fmt(buySide)}, SSL ${fmt(sellSide)}. POI jauh tetap tampil sebagai CONTEXT.")
            .put("trade_setup", setup)
            .put("market_structure", JSONObject().put("trend", if (bias == "BULLISH") "Bullish" else if (bias == "BEARISH") "Bearish" else "Range").put("last_bos", structure).put("choch", if (structure.contains("MSS")) structure else "Belum ada CHoCH/MSS baru").put("swing_high", buySide).put("swing_low", sellSide).put("liquidity", liquidity).put("fvg", fvgDesc).put("order_block", obDesc).put("premium_discount", "Range ${fmt(rangeLow)} - ${fmt(rangeHigh)} | EQ ${fmt(eq)} | $currentZone"))
            .put("order_blocks", JSONObject().put("bullish_ob", bullOb?.label() ?: "-").put("bearish_ob", bearOb?.label() ?: "-").put("description", obDesc))
            .put("fvg", JSONObject().put("bullish_fvg", bullFvg?.label() ?: "-").put("bearish_fvg", bearFvg?.label() ?: "-").put("description", fvgDesc))
            .put("liquidity", JSONObject().put("buy_side", fmt(buySide)).put("sell_side", fmt(sellSide)).put("sweep_occurred", sweep).put("description", liquidity))
            .put("premium_discount", JSONObject().put("equilibrium", eq).put("current_zone", currentZone).put("ote_zone", oteZone(bias, rangeLow, rangeHigh)))
            .put("active_poi", poiArray(listOfNotNull(activeBullFvg, activeBearFvg, activeBullOb, activeBearOb)))
            .put("context_poi", poiArray(listOfNotNull(bullFvg, bearFvg, bullOb, bearOb).filter { !it.active }))
            .put("key_notes", JSONArray().put("ACTIVE = dekat harga live.").put("CONTEXT = valid tetapi masih jauh, tetap ditampilkan."))
            .put("warnings", JSONArray().apply { if (setup.optString("status") == "wait") put("Setup masih WAIT.") })
            .toString()
    }

    private fun detectFvg(bars: List<Bar>, price: Double, distance: Double, atr: Double): List<Zone> {
        val out = mutableListOf<Zone>()
        for (i in 2 until bars.size) {
            val a = bars[i - 2]
            val b = bars[i - 1]
            val c = bars[i]
            val impulse = abs(b.close - b.open) / (b.high - b.low).coerceAtLeast(0.0001) >= 0.45 || b.high - b.low >= atr
            if (impulse && c.low > a.high) out += Zone("FVG", "bullish", a.high, c.low, abs(((a.high + c.low) / 2.0) - price) <= distance, fresh(i, bars.lastIndex))
            if (impulse && c.high < a.low) out += Zone("FVG", "bearish", c.high, a.low, abs(((c.high + a.low) / 2.0) - price) <= distance, fresh(i, bars.lastIndex))
        }
        return out.sortedWith(compareByDescending<Zone> { it.active }.thenByDescending { it.score })
    }

    private fun detectOb(bars: List<Bar>, price: Double, distance: Double): List<Zone> {
        val recent = bars.takeLast(60)
        val out = mutableListOf<Zone>()
        recent.minByOrNull { it.low }?.let { out += Zone("OB", "bullish", min(it.open, it.close), max(it.open, it.close), abs(((it.open + it.close) / 2.0) - price) <= distance, 50) }
        recent.maxByOrNull { it.high }?.let { out += Zone("OB", "bearish", min(it.open, it.close), max(it.open, it.close), abs(((it.open + it.close) / 2.0) - price) <= distance, 50) }
        return out
    }

    private fun setupJson(bias: String, pd: String, price: Double, ssl: Double, bsl: Double, low: Double, high: Double, atr: Double, bf: Zone?, sf: Zone?, bo: Zone?, so: Zone?): JSONObject {
        return when (bias) {
            "BULLISH" -> { val valid = pd == "DISCOUNT" && (bf != null || bo != null); JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", bo?.area() ?: bf?.area() ?: "Tunggu discount/OTE").put("tp1", if (valid) max(bsl, price + atr) else 0.0).put("tp2", if (valid) max(high, price + atr * 2) else 0.0).put("stop_loss", if (valid) min(ssl, low) - atr else 0.0).put("risk_reward", if (valid) "1:2" else "-").put("invalidation", if (valid) "Close di bawah SL" else "Tunggu POI bullish aktif") }
            "BEARISH" -> { val valid = pd == "PREMIUM" && (sf != null || so != null); JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", so?.area() ?: sf?.area() ?: "Tunggu premium/OTE").put("tp1", if (valid) min(ssl, price - atr) else 0.0).put("tp2", if (valid) min(low, price - atr * 2) else 0.0).put("stop_loss", if (valid) max(bsl, high) + atr else 0.0).put("risk_reward", if (valid) "1:2" else "-").put("invalidation", if (valid) "Close di atas SL" else "Tunggu POI bearish aktif") }
            else -> JSONObject().put("status", "wait").put("entry_zone", "-").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", "Tunggu struktur")
        }
    }

    private fun parseBars(prompt: String): List<Bar> {
        val re = Regex("(\\d+):\\s*O=([-0-9.]+),\\s*H=([-0-9.]+),\\s*L=([-0-9.]+),\\s*C=([-0-9.]+),\\s*ticks=(\\d+)")
        return re.findAll(prompt).mapNotNull { val v = it.groupValues; val t = v[1].toLongOrNull(); val o = v[2].toDoubleOrNull(); val h = v[3].toDoubleOrNull(); val l = v[4].toDoubleOrNull(); val c = v[5].toDoubleOrNull(); val n = v[6].toIntOrNull() ?: 1; if (t != null && o != null && h != null && l != null && c != null) Bar(t, o, h, l, c, n) else null }.toList()
    }

    private fun pivotsHigh(b: List<Bar>, left: Int, right: Int): List<Pivot> = (left until b.size - right).filter { i -> (1..left).all { b[i - it].high < b[i].high } && (1..right).all { b[i + it].high < b[i].high } }.map { Pivot(it, b[it].high) }
    private fun pivotsLow(b: List<Bar>, left: Int, right: Int): List<Pivot> = (left until b.size - right).filter { i -> (1..left).all { b[i - it].low > b[i].low } && (1..right).all { b[i + it].low > b[i].low } }.map { Pivot(it, b[it].low) }
    private fun structureText(b: List<Bar>, h: List<Pivot>, l: List<Pivot>): String { val last = b.last(); val ph = h.lastOrNull { it.index < b.lastIndex - 1 }; val pl = l.lastOrNull { it.index < b.lastIndex - 1 }; return when { ph != null && last.close > ph.level -> "Bullish BOS @ ${fmt(ph.level)}"; pl != null && last.close < pl.level -> "Bearish BOS @ ${fmt(pl.level)}"; else -> "None" } }
    private fun liquidityText(b: List<Bar>, bsl: Double, ssl: Double, atr: Double): String { val last = b.last(); val tol = max(atr * 0.1, 0.05); return when { last.high > bsl + tol && last.close < bsl -> "Buy-side liquidity swept @ ${fmt(bsl)}"; last.low < ssl - tol && last.close > ssl -> "Sell-side liquidity swept @ ${fmt(ssl)}"; else -> "BSL ${fmt(bsl)} • SSL ${fmt(ssl)}" } }
    private fun poiArray(zones: List<Zone>): JSONArray = JSONArray().apply { zones.forEach { put(JSONObject().put("type", it.kind).put("side", it.side).put("zone", it.label()).put("score", it.score)) } }
    private fun pick(z: List<Zone>, side: String): Zone? = z.filter { it.side == side }.maxByOrNull { it.score + if (it.active) 100 else 0 }
    private fun atr(b: List<Bar>): Double = b.takeLast(14).map { it.high - it.low }.average().takeIf { !it.isNaN() } ?: 0.5
    private fun fresh(i: Int, last: Int): Int = when ((last - i).coerceAtLeast(0)) { in 0..5 -> 40; in 6..15 -> 25; in 16..35 -> 10; else -> 5 }
    private fun oteZone(bias: String, low: Double, high: Double): String { val r = high - low; return if (bias == "BULLISH") "${fmt(high - r * 0.79)} - ${fmt(high - r * 0.62)}" else if (bias == "BEARISH") "${fmt(low + r * 0.62)} - ${fmt(low + r * 0.79)}" else "-" }
    private fun waitJson(tf: String, s: String, p: Double, reason: String): String = JSONObject().put("bias", "NEUTRAL").put("confidence_score", 25).put("timeframe", tf).put("session_context", s).put("current_price", p).put("daily_bias_summary", reason).put("trade_setup", JSONObject().put("status", "wait").put("entry_zone", "-").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", reason)).put("market_structure", JSONObject().put("trend", "Range").put("last_bos", "None").put("choch", "-").put("liquidity", reason).put("fvg", "-").put("order_block", "-").put("premium_discount", "-")).put("order_blocks", JSONObject().put("bullish_ob", "-").put("bearish_ob", "-").put("description", "-")).put("fvg", JSONObject().put("bullish_fvg", "-").put("bearish_fvg", "-").put("description", "-")).put("liquidity", JSONObject().put("buy_side", "-").put("sell_side", "-").put("sweep_occurred", false).put("description", reason)).put("premium_discount", JSONObject().put("equilibrium", 0.0).put("current_zone", "-").put("ote_zone", "-")).put("active_poi", JSONArray()).put("context_poi", JSONArray()).put("key_notes", JSONArray()).put("warnings", JSONArray().put(reason)).toString()
    private fun readText(prompt: String, label: String): String? = Regex("$label:\\s*([^\\n]+)").find(prompt)?.groupValues?.get(1)?.trim()?.takeIf { it != "-" }
    private fun readNumber(prompt: String, label: String): Double? = readText(prompt, label)?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }
    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
