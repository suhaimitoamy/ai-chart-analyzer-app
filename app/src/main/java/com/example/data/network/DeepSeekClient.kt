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
        fun area(): String = "${fmt(low)} - ${fmt(high)}"
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
            val range = (bar.high - bar.low).coerceAtLeast(0.0001)
            val topWickRatio = (bar.high - max(bar.open, bar.close)) / range
            val bottomWickRatio = (min(bar.open, bar.close) - bar.low) / range
            if (!sweep && bar.high > buyLevel + tol && bar.close < buyLevel && topWickRatio >= 0.35) {
                sweep = true
                side = "buy_side"
                desc = "Buy-side liquidity swept at ${fmt(buyLevel)}, close reclaimed below level."
            }
            if (!sweep && bar.low < sellLevel - tol && bar.close > sellLevel && bottomWickRatio >= 0.35) {
                sweep = true
                side = "sell_side"
                desc = "Sell-side liquidity swept at ${fmt(sellLevel)}, close reclaimed above level."
            }
        }
        return LiquidityState(buyLevel, sellLevel, sweep, desc, side)
    }

    private fun detectQualifiedOb(
        bars: List<Bar>,
        atr: Double,
        price: Double,
        maxDistance: Double,
        currentZone: String,
        bias: String,
        structure: StructureState,
        hasSweep: Boolean,
        bullFvg: PoiZone?,
        bearFvg: PoiZone?
    ): PoiZone? {
        val side = when (bias) {
            "BULLISH" -> "bullish"
            "BEARISH" -> "bearish"
            else -> if (structure.breakText.contains("BULLISH")) "bullish" else if (structure.breakText.contains("BEARISH")) "bearish" else return null
        }
        val displacementIndex = findDisplacementIndex(bars, atr, side) ?: return null
        val originIndex = findLastOpposingCandle(bars, displacementIndex, side) ?: return null
        val origin = bars[originIndex]
        val low = origin.low
        val high = origin.high
        val mid = (low + high) / 2.0
        val pdAligned = (side == "bullish" && currentZone == "DISCOUNT") || (side == "bearish" && currentZone == "PREMIUM")
        val hasFvg = (side == "bullish" && bullFvg != null) || (side == "bearish" && bearFvg != null)
        val hasStructure = structure.breakText.contains("BOS") || structure.breakText.contains("MSS")
        var score = 0
        if (hasSweep) score += 2
        if (hasStructure) score += 2
        if (hasFvg) score += 2
        if (pdAligned) score += 1
        if ((bars[displacementIndex].high - bars[displacementIndex].low) >= atr * 1.2) score += 1
        score += (freshnessScore(originIndex, bars.lastIndex) / 20).coerceIn(0, 2)
        val active = score >= 4 && abs(mid - price) <= maxDistance
        return if (active) PoiZone(side, low, high, "Qualified OB", score, true) else null
    }

    private fun tradeSetup(
        bias: String,
        zone: String,
        price: Double,
        support: Double,
        resistance: Double,
        rangeHigh: Double,
        rangeLow: Double,
        atr: Double,
        bullFvg: PoiZone?,
        bearFvg: PoiZone?,
        ob: PoiZone?
    ): JSONObject {
        return when (bias) {
            "BULLISH" -> {
                val entry = when {
                    ob?.side == "bullish" -> ob.area()
                    bullFvg != null -> bullFvg.area()
                    else -> "${fmt(support)} - ${fmt(price)}"
                }
                val valid = zone == "DISCOUNT" && (ob?.side == "bullish" || bullFvg != null) && support < price
                val sl = min(support, rangeLow) - atr
                JSONObject().put("status", if (valid) "valid" else "wait")
                    .put("entry_zone", entry).put("tp1", resistance).put("tp2", rangeHigh)
                    .put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona")
                    .put("invalidation", "Close kuat di bawah ${fmt(sl)}")
            }
            "BEARISH" -> {
                val entry = when {
                    ob?.side == "bearish" -> ob.area()
                    bearFvg != null -> bearFvg.area()
                    else -> "${fmt(price)} - ${fmt(resistance)}"
                }
                val valid = zone == "PREMIUM" && (ob?.side == "bearish" || bearFvg != null) && resistance > price
                val sl = max(resistance, rangeHigh) + atr
                JSONObject().put("status", if (valid) "valid" else "wait")
                    .put("entry_zone", entry).put("tp1", support).put("tp2", rangeLow)
                    .put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona")
                    .put("invalidation", "Close kuat di atas ${fmt(sl)}")
            }
            else -> JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid")
                .put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0)
                .put("risk_reward", "-").put("invalidation", "Tunggu BOS/CHoCH dan liquidity sweep")
        }
    }

    private fun calculateBias(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>, structure: StructureState, price: Double, eq: Double): String {
        if (structure.breakText.contains("BULLISH")) return "BULLISH"
        if (structure.breakText.contains("BEARISH")) return "BEARISH"
        val hh = highs.size >= 2 && highs.last().level > highs[highs.size - 2].level
        val hl = lows.size >= 2 && lows.last().level > lows[lows.size - 2].level
        val lh = highs.size >= 2 && highs.last().level < highs[highs.size - 2].level
        val ll = lows.size >= 2 && lows.last().level < lows[lows.size - 2].level
        val last3 = bars.takeLast(3)
        val momentum = last3.sumOf { it.close - it.open }
        return when {
            hh && hl -> "BULLISH"
            lh && ll -> "BEARISH"
            momentum > 0 && price > eq -> "BULLISH"
            momentum < 0 && price < eq -> "BEARISH"
            else -> "NEUTRAL"
        }
    }

    private fun confidence(bias: String, bars: List<Bar>, sweep: Boolean, fvg: Boolean, ob: Boolean, valid: Boolean): Int {
        var score = if (bias == "NEUTRAL") 35 else 50
        if (sweep) score += 10
        if (fvg) score += 10
        if (ob) score += 15
        if (valid) score += 5
        if (isChoppy(bars)) score -= 15
        return score.coerceIn(25, 90)
    }

    private fun pivotsHigh(bars: List<Bar>, left: Int, right: Int): List<Pivot> {
        if (bars.size < left + right + 1) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until bars.size - right) {
            val level = bars[i].high
            val okLeft = (1..left).all { bars[i - it].high < level }
            val okRight = (1..right).all { bars[i + it].high < level }
            if (okLeft && okRight) out.add(Pivot(i, level))
        }
        return out
    }

    private fun pivotsLow(bars: List<Bar>, left: Int, right: Int): List<Pivot> {
        if (bars.size < left + right + 1) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until bars.size - right) {
            val level = bars[i].low
            val okLeft = (1..left).all { bars[i - it].low > level }
            val okRight = (1..right).all { bars[i + it].low > level }
            if (okLeft && okRight) out.add(Pivot(i, level))
        }
        return out
    }

    private fun nearestLiquidityLevel(pivots: List<Pivot>, price: Double, above: Boolean, fallback: Double, tol: Double): Double {
        val candidates = pivots.map { it.level }.filter { if (above) it > price else it < price }
        if (candidates.isEmpty()) return fallback
        val clusters = candidates.groupBy { level -> (level / tol).toInt() }
            .map { (_, levels) -> levels.average() to levels.size }
            .sortedWith(compareByDescending<Pair<Double, Int>> { it.second }.thenBy { abs(it.first - price) })
        return clusters.firstOrNull()?.first ?: if (above) candidates.minOrNull() ?: fallback else candidates.maxOrNull() ?: fallback
    }

    private fun findDisplacementIndex(bars: List<Bar>, atr: Double, side: String): Int? {
        val start = (bars.size - 12).coerceAtLeast(1)
        for (i in bars.lastIndex downTo start) {
            val bar = bars[i]
            val range = bar.high - bar.low
            val body = abs(bar.close - bar.open)
            val impulse = range >= atr * 1.15 || (range > 0 && body / range >= 0.60)
            if (side == "bullish" && bar.close > bar.open && impulse) return i
            if (side == "bearish" && bar.close < bar.open && impulse) return i
        }
        return null
    }

    private fun findLastOpposingCandle(bars: List<Bar>, beforeIndex: Int, side: String): Int? {
        val start = (beforeIndex - 8).coerceAtLeast(0)
        for (i in beforeIndex - 1 downTo start) {
            val bar = bars[i]
            if (side == "bullish" && bar.close < bar.open) return i
            if (side == "bearish" && bar.close > bar.open) return i
        }
        return null
    }

    private fun calculateAtr(bars: List<Bar>): Double {
        val sample = bars.takeLast(14)
        return if (sample.isEmpty()) 1.0 else sample.map { it.high - it.low }.average().coerceAtLeast(0.01)
    }

    private fun bodyRatio(bar: Bar): Double {
        val range = (bar.high - bar.low).coerceAtLeast(0.0001)
        return abs(bar.close - bar.open) / range
    }

    private fun isChoppy(bars: List<Bar>): Boolean {
        if (bars.size < 5) return false
        return bars.takeLast(5).count { bodyRatio(it) < 0.30 } >= 3
    }

    private fun activeDistance(timeframe: String, atr: Double): Double {
        return when (timeframe.uppercase(Locale.US)) {
            "M1" -> atr * 6.0
            "M5" -> atr * 8.0
            "M15" -> atr * 10.0
            "M30" -> atr * 12.0
            "H1" -> atr * 14.0
            else -> atr * 18.0
        }.coerceAtLeast(6.0)
    }

    private fun freshnessScore(index: Int, lastIndex: Int): Int {
        val age = (lastIndex - index).coerceAtLeast(0)
        return when {
            age <= 3 -> 30
            age <= 8 -> 20
            age <= 15 -> 10
            else -> 0
        }
    }

    private fun oteZone(bias: String, low: Double, high: Double): String {
        val range = high - low
        return when (bias) {
            "BULLISH" -> "${fmt(low + range * 0.62)} - ${fmt(low + range * 0.79)}"
            "BEARISH" -> "${fmt(high - range * 0.79)} - ${fmt(high - range * 0.62)}"
            else -> "Belum ada OTE valid"
        }
    }

    private fun minimalWaitJson(timeframe: String, session: String, price: Double, reason: String): String {
        return JSONObject()
            .put("bias", "NEUTRAL")
            .put("confidence_score", 25)
            .put("timeframe", timeframe)
            .put("session_context", session)
            .put("current_price", price)
            .put("daily_bias_summary", reason)
            .put("trade_setup", JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", "Tunggu candle lengkap"))
            .put("market_structure", JSONObject().put("trend", "Range").put("last_bos", "None").put("choch", "-").put("swing_high", 0.0).put("swing_low", 0.0).put("liquidity", reason).put("fvg", "-").put("order_block", "-").put("premium_discount", "-"))
            .put("order_blocks", JSONObject().put("bullish_ob", "-").put("bearish_ob", "-").put("description", "-"))
            .put("fvg", JSONObject().put("bullish_fvg", "-").put("bearish_fvg", "-").put("description", "-"))
            .put("liquidity", JSONObject().put("buy_side", "-").put("sell_side", "-").put("sweep_occurred", false).put("description", reason))
            .put("premium_discount", JSONObject().put("equilibrium", 0.0).put("current_zone", "EQUILIBRIUM").put("ote_zone", "-"))
            .put("key_notes", JSONArray().put(reason))
            .put("warnings", JSONArray().put(reason))
            .toString()
    }

    private fun readText(prompt: String, label: String): String? {
        val match = Regex("$label:\\s*([^\\n]+)").find(prompt) ?: return null
        return match.groupValues[1].trim().takeIf { it != "-" }
    }

    private fun readNumber(prompt: String, label: String): Double? {
        return readText(prompt, label)?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}
