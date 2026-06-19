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
    private data class Bar(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val ticks: Int
    )

    private data class Pivot(val index: Int, val level: Double)

    private data class PoiZone(
        val side: String,
        val low: Double,
        val high: Double,
        val label: String,
        val score: Int,
        val active: Boolean
    ) {
        val mid: Double get() = (low + high) / 2.0
        fun area(): String = String.format(Locale.US, "%.2f - %.2f", low, high)
    }

    suspend fun analyzeChart(prompt: String): String = withContext(Dispatchers.Default) {
        compileLocalAnalysis(prompt)
    }

    private fun compileLocalAnalysis(prompt: String): String {
        val timeframe = readText(prompt, "Requested timeframe") ?: "M1"
        val session = readText(prompt, "Session") ?: "-"
        val bars = parseBars(prompt)
        val promptPrice = readNumber(prompt, "Current price") ?: bars.lastOrNull()?.close ?: 0.0
        val price = MarketPriceCache.latestPrice ?: promptPrice

        if (bars.size < 8) {
            return minimalWaitJson(timeframe, session, price, "Candle belum cukup untuk mapping POI yang valid.")
        }

        val atr = calculateAtr(bars).coerceAtLeast(0.50)
        val maxDistance = activeDistance(timeframe, atr)
        val internalHighs = pivotsHigh(bars, 2, 2)
        val internalLows = pivotsLow(bars, 2, 2)
        val externalHighs = pivotsHigh(bars, 5, 5).ifEmpty { internalHighs }
        val externalLows = pivotsLow(bars, 5, 5).ifEmpty { internalLows }
        val dealingHigh = externalHighs.lastOrNull()?.level ?: bars.maxOf { it.high }
        val dealingLow = externalLows.lastOrNull()?.level ?: bars.minOf { it.low }
        val rangeHigh = max(dealingHigh, dealingLow)
        val rangeLow = min(dealingHigh, dealingLow)
        val equilibrium = (rangeHigh + rangeLow) / 2.0
        val currentZone = when {
            price > equilibrium -> "PREMIUM"
            price < equilibrium -> "DISCOUNT"
            else -> "EQUILIBRIUM"
        }

        val structure = detectStructure(bars, externalHighs, externalLows, price)
        val fvgZones = detectFvgs(bars, atr, price, maxDistance)
        val activeBullFvg = fvgZones.filter { it.side == "bullish" && it.active }.maxByOrNull { it.score }
        val activeBearFvg = fvgZones.filter { it.side == "bearish" && it.active }.maxByOrNull { it.score }
        val liquidity = detectLiquidity(bars, internalHighs, internalLows, atr, price)
        val bias = calculateBias(bars, externalHighs, externalLows, structure, price, equilibrium)
        val ob = detectQualifiedOb(bars, atr, price, maxDistance, currentZone, bias, structure, liquidity.sweepOccurred, activeBullFvg, activeBearFvg)

        val bullishFvg = activeBullFvg?.area() ?: "-"
        val bearishFvg = activeBearFvg?.area() ?: "-"
        val bullishOb = ob.takeIf { it?.side == "bullish" && it.active }?.area() ?: "-"
        val bearishOb = ob.takeIf { it?.side == "bearish" && it.active }?.area() ?: "-"
        val hasFvg = (bias == "BULLISH" && bullishFvg != "-") || (bias == "BEARISH" && bearishFvg != "-")
        val hasOb = (bias == "BULLISH" && bullishOb != "-") || (bias == "BEARISH" && bearishOb != "-")
        val setup = tradeSetup(bias, currentZone, price, liquidity.sellSide, liquidity.buySide, rangeHigh, rangeLow, atr, activeBullFvg, activeBearFvg, ob)
        val confidence = confidence(bias, bars, liquidity.sweepOccurred, hasFvg, hasOb, setup.optString("status") == "valid")
        val zoneText = when (currentZone) {
            "PREMIUM" -> "premium"
            "DISCOUNT" -> "discount"
            else -> "equilibrium"
        }
        val phase = structure.phase
        val summary = when (bias) {
            "BULLISH" -> "Market dalam fase $phase dengan bias bullish. Harga live berada di zona $zoneText, sell-side liquidity terdekat ada di ${fmt(liquidity.sellSide)}, buy-side liquidity terdekat ada di ${fmt(liquidity.buySide)}. Skenario buy hanya valid jika POI aktif tidak berada terlalu jauh dari harga."
            "BEARISH" -> "Market dalam fase $phase dengan bias bearish. Harga live berada di zona $zoneText, buy-side liquidity terdekat ada di ${fmt(liquidity.buySide)}, sell-side liquidity terdekat ada di ${fmt(liquidity.sellSide)}. Skenario sell hanya valid jika POI aktif tidak berada terlalu jauh dari harga."
            else -> "Market dalam fase $phase dengan bias netral. Harga live berada di zona $zoneText dan rule engine belum menemukan rangkaian sweep, displacement, dan POI aktif yang cukup kuat."
        }

        val fvgDescription = when {
            activeBullFvg != null && activeBearFvg != null -> "Bullish FVG ${activeBullFvg.area()} | Bearish FVG ${activeBearFvg.area()}"
            activeBullFvg != null -> "Bullish FVG ${activeBullFvg.area()}"
            activeBearFvg != null -> "Bearish FVG ${activeBearFvg.area()}"
            else -> "Tidak ada strict FVG aktif dekat harga"
        }
        val obDescription = ob?.let { "${it.side.uppercase(Locale.US)} qualified OB ${it.area()} | score ${it.score}" }
            ?: "Tidak ada qualified OB. OB butuh sweep/structure shift/displacement/FVG/PD alignment."

        return JSONObject()
            .put("bias", bias)
            .put("confidence_score", confidence)
            .put("timeframe", timeframe)
            .put("session_context", session)
            .put("current_price", price)
            .put("daily_bias_summary", summary)
            .put("trade_setup", setup)
            .put("market_structure", JSONObject()
                .put("trend", when (bias) { "BULLISH" -> "Bullish"; "BEARISH" -> "Bearish"; else -> "Range" })
                .put("last_bos", structure.breakText)
                .put("choch", structure.chochText)
                .put("swing_high", liquidity.buySide)
                .put("swing_low", liquidity.sellSide)
                .put("liquidity", liquidity.description)
                .put("fvg", fvgDescription)
                .put("order_block", obDescription)
                .put("premium_discount", "Active dealing range ${fmt(rangeLow)} - ${fmt(rangeHigh)} | EQ ${fmt(equilibrium)} | Harga di $currentZone"))
            .put("order_blocks", JSONObject()
                .put("bullish_ob", bullishOb)
                .put("bearish_ob", bearishOb)
                .put("description", obDescription))
            .put("fvg", JSONObject()
                .put("bullish_fvg", bullishFvg)
                .put("bearish_fvg", bearishFvg)
                .put("description", fvgDescription))
            .put("liquidity", JSONObject()
                .put("buy_side", fmt(liquidity.buySide))
                .put("sell_side", fmt(liquidity.sellSide))
                .put("sweep_occurred", liquidity.sweepOccurred)
                .put("description", liquidity.description))
            .put("premium_discount", JSONObject()
                .put("equilibrium", equilibrium)
                .put("current_zone", currentZone)
                .put("ote_zone", oteZone(bias, rangeLow, rangeHigh)))
            .put("active_poi", JSONArray().apply {
                if (activeBullFvg != null) put(JSONObject().put("type", "bullish_fvg").put("zone", activeBullFvg.area()).put("score", activeBullFvg.score))
                if (activeBearFvg != null) put(JSONObject().put("type", "bearish_fvg").put("zone", activeBearFvg.area()).put("score", activeBearFvg.score))
                if (ob != null && ob.active) put(JSONObject().put("type", "qualified_${ob.side}_ob").put("zone", ob.area()).put("score", ob.score))
            })
            .put("key_notes", JSONArray()
                .put("Current price memakai live tick cache jika tersedia.")
                .put("FVG memakai strict 3-candle + filter ATR + displacement candle.")
                .put("Liquidity memakai pivot cluster, ATR tolerance, wick sweep, dan close reclaim.")
                .put("Order Block hanya valid jika lolos minimal confluence sweep/structure/displacement/FVG/PD."))
            .put("warnings", JSONArray().apply {
                if (MarketPriceCache.latestPrice == null) put("Live tick belum masuk; current price memakai candle terakhir dari snapshot.")
                if (isChoppy(bars)) put("Market choppy; tunggu retest yang jelas.")
                if (setup.optString("status") == "wait") put("Setup masih WAIT; tunggu POI aktif dan konfirmasi retest.")
                if (abs(price - equilibrium) < atr * 0.25) put("Harga dekat equilibrium; area ini rawan noise.")
                if (ob == null) put("Qualified OB tidak dipaksa muncul agar zona tidak ngaco.")
            })
            .toString()
    }

    private data class StructureState(val phase: String, val breakText: String, val chochText: String, val direction: String)
    private data class LiquidityState(val buySide: Double, val sellSide: Double, val sweepOccurred: Boolean, val description: String, val sweepSide: String)

    private fun parseBars(prompt: String): List<Bar> {
        val regex = Regex("(\\d+):\\s*O=([-0-9.]+),\\s*H=([-0-9.]+),\\s*L=([-0-9.]+),\\s*C=([-0-9.]+),\\s*ticks=(\\d+)")
        return regex.findAll(prompt).mapNotNull { m ->
            val v = m.groupValues
            val time = v[1].toLongOrNull()
            val open = v[2].toDoubleOrNull()
            val high = v[3].toDoubleOrNull()
            val low = v[4].toDoubleOrNull()
            val close = v[5].toDoubleOrNull()
            val ticks = v[6].toIntOrNull() ?: 1
            if (time != null && open != null && high != null && low != null && close != null) Bar(time, open, high, low, close, ticks) else null
        }.toList()
    }

    private fun detectStructure(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>, price: Double): StructureState {
        val lastHigh = highs.lastOrNull()
        val lastLow = lows.lastOrNull()
        val previousHigh = highs.dropLast(1).lastOrNull()
        val previousLow = lows.dropLast(1).lastOrNull()
        val direction = when {
            lastHigh != null && previousHigh != null && lastLow != null && previousLow != null && lastHigh.level > previousHigh.level && lastLow.level > previousLow.level -> "bullish"
            lastHigh != null && previousHigh != null && lastLow != null && previousLow != null && lastHigh.level < previousHigh.level && lastLow.level < previousLow.level -> "bearish"
            else -> "range"
        }
        val close = bars.last().close
        val breakText = when {
            lastHigh != null && close > lastHigh.level -> if (direction == "bearish") "MSS_BULLISH at ${fmt(lastHigh.level)}" else "BOS_BULLISH at ${fmt(lastHigh.level)}"
            lastLow != null && close < lastLow.level -> if (direction == "bullish") "MSS_BEARISH at ${fmt(lastLow.level)}" else "BOS_BEARISH at ${fmt(lastLow.level)}"
            else -> "None"
        }
        val chochText = if (breakText.contains("MSS")) breakText else "Belum ada CHoCH/MSS baru"
        val phase = when {
            isChoppy(bars) -> "CHOPPY"
            breakText != "None" -> "EXPANSION"
            direction == "bullish" -> "PULLBACK_OR_MARKUP"
            direction == "bearish" -> "PULLBACK_OR_MARKDOWN"
            else -> "RANGING"
        }
        return StructureState(phase, breakText, chochText, direction)
    }

    private fun detectFvgs(bars: List<Bar>, atr: Double, price: Double, maxDistance: Double): List<PoiZone> {
        val minGap = max(atr * 0.08, 0.05)
        val zones = mutableListOf<PoiZone>()
        for (i in 2 until bars.size) {
            val left = bars[i - 2]
            val mid = bars[i - 1]
            val right = bars[i]
            val impulse = bodyRatio(mid) >= 0.55 || (mid.high - mid.low) >= atr * 1.15
            if (right.low > left.high) {
                val low = left.high
                val high = right.low
                val gap = high - low
                val filled = bars.drop(i + 1).any { it.low <= low }
                val active = !filled && gap >= minGap && impulse && abs(((low + high) / 2.0) - price) <= maxDistance
                val score = 30 + freshnessScore(i, bars.lastIndex) + if (impulse) 15 else 0 + if (!filled) 20 else 0
                if (gap >= minGap) zones.add(PoiZone("bullish", low, high, "Bullish FVG", score, active))
            }
            if (right.high < left.low) {
                val low = right.high
                val high = left.low
                val gap = high - low
                val filled = bars.drop(i + 1).any { it.high >= high }
                val active = !filled && gap >= minGap && impulse && abs(((low + high) / 2.0) - price) <= maxDistance
                val score = 30 + freshnessScore(i, bars.lastIndex) + if (impulse) 15 else 0 + if (!filled) 20 else 0
                if (gap >= minGap) zones.add(PoiZone("bearish", low, high, "Bearish FVG", score, active))
            }
        }
        return zones.sortedByDescending { it.score }
    }

    private fun detectLiquidity(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>, atr: Double, price: Double): LiquidityState {
        val tol = max(atr * 0.25, 0.10)
        val buyLevel = nearestLiquidityLevel(highs, price, above = true, fallback = bars.maxOf { it.high }, tol = tol)
        val sellLevel = nearestLiquidityLevel(lows, price, above = false, fallback = bars.minOf { it.low }, tol = tol)
        val recent = bars.takeLast(3)
        var sweep = false
        var side = "none"
        var desc = "No fresh liquidity sweep. Buy-side ${fmt(buyLevel)}, sell-side ${fmt(sellLevel)}"
        recent.forEach { bar ->
