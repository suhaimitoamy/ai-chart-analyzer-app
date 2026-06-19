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
    private data class StructureState(val phase: String, val breakText: String, val chochText: String, val direction: String)
    private data class LiquidityState(val buySide: Double, val sellSide: Double, val sweepOccurred: Boolean, val description: String, val sweepSide: String)
    private data class PoiZone(val side: String, val low: Double, val high: Double, val score: Int, val active: Boolean, val kind: String) {
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

        if (bars.size < 12) {
            return minimalWaitJson(timeframe, session, price, "Candle belum cukup untuk mapping POI TradingView-style.")
        }

        val atr = calculateAtr(bars).coerceAtLeast(0.50)
        val maxDistance = activeDistance(timeframe, atr)
        val internalHighs = pivotsHigh(bars, 3, 1)
        val internalLows = pivotsLow(bars, 3, 1)
        val externalHighs = pivotsHigh(bars, 5, 1).ifEmpty { internalHighs }
        val externalLows = pivotsLow(bars, 5, 1).ifEmpty { internalLows }
        val rangeHigh = max(externalHighs.lastOrNull()?.level ?: bars.maxOf { it.high }, externalLows.lastOrNull()?.level ?: bars.minOf { it.low })
        val rangeLow = min(externalHighs.lastOrNull()?.level ?: bars.maxOf { it.high }, externalLows.lastOrNull()?.level ?: bars.minOf { it.low })
        val equilibrium = (rangeHigh + rangeLow) / 2.0
        val currentZone = when {
            price > equilibrium -> "PREMIUM"
            price < equilibrium -> "DISCOUNT"
            else -> "EQUILIBRIUM"
        }

        val structure = detectStructure(bars, externalHighs, externalLows)
        val liquidity = detectLiquidityTradingViewStyle(bars, internalHighs, internalLows, atr, price)
        val fvgZones = detectFvgsTradingViewStyle(bars, atr, price, maxDistance)
        val activeBullFvg = fvgZones.filter { it.side == "bullish" && it.active }.maxByOrNull { it.score }
        val activeBearFvg = fvgZones.filter { it.side == "bearish" && it.active }.maxByOrNull { it.score }
        val bias = calculateBias(bars, externalHighs, externalLows, structure, price, equilibrium)
        val ob = detectOrderBlockTradingViewStyle(bars, atr, price, maxDistance, currentZone, bias, structure, liquidity.sweepOccurred, activeBullFvg, activeBearFvg, externalHighs, externalLows)

        val bullishFvg = activeBullFvg?.area() ?: "-"
        val bearishFvg = activeBearFvg?.area() ?: "-"
        val bullishOb = if (ob != null && ob.side == "bullish" && ob.active) ob.area() else "-"
        val bearishOb = if (ob != null && ob.side == "bearish" && ob.active) ob.area() else "-"
        val hasFvg = (bias == "BULLISH" && bullishFvg != "-") || (bias == "BEARISH" && bearishFvg != "-")
        val hasOb = (bias == "BULLISH" && bullishOb != "-") || (bias == "BEARISH" && bearishOb != "-")
        val setup = tradeSetup(bias, currentZone, price, liquidity.sellSide, liquidity.buySide, rangeHigh, rangeLow, atr, activeBullFvg, activeBearFvg, ob)
        val confidence = confidence(bias, bars, liquidity.sweepOccurred, hasFvg, hasOb, setup.optString("status") == "valid")
        val phase = structure.phase
        val zoneText = when (currentZone) { "PREMIUM" -> "premium"; "DISCOUNT" -> "discount"; else -> "equilibrium" }
        val summary = when (bias) {
            "BULLISH" -> "Market dalam fase $phase dengan bias bullish. Harga live berada di zona $zoneText. Buy-side liquidity terdekat ${fmt(liquidity.buySide)}, sell-side liquidity terdekat ${fmt(liquidity.sellSide)}. Entry buy hanya valid setelah harga kembali ke discount/OTE atau POI aktif."
            "BEARISH" -> "Market dalam fase $phase dengan bias bearish. Harga live berada di zona $zoneText. Buy-side liquidity terdekat ${fmt(liquidity.buySide)}, sell-side liquidity terdekat ${fmt(liquidity.sellSide)}. Entry sell hanya valid setelah harga kembali ke premium/OTE atau POI aktif."
            else -> "Market dalam fase $phase dengan bias netral. Harga live berada di zona $zoneText dan engine belum menemukan rangkaian sweep, displacement, dan POI aktif yang cukup kuat."
        }
        val fvgDescription = when {
            activeBullFvg != null && activeBearFvg != null -> "Bullish FVG ${activeBullFvg.area()} | Bearish FVG ${activeBearFvg.area()}"
            activeBullFvg != null -> "Bullish FVG ${activeBullFvg.area()}"
            activeBearFvg != null -> "Bearish FVG ${activeBearFvg.area()}"
            else -> "Tidak ada FVG aktif ala TradingView dekat harga"
        }
        val obDescription = ob?.let { "${it.side.uppercase(Locale.US)} OB ${it.area()} | score ${it.score} | ${it.kind}" }
            ?: "Tidak ada OB aktif. OB hanya muncul setelah swing break dan zona masih relevan."

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
            .put("order_blocks", JSONObject().put("bullish_ob", bullishOb).put("bearish_ob", bearishOb).put("description", obDescription))
            .put("fvg", JSONObject().put("bullish_fvg", bullishFvg).put("bearish_fvg", bearishFvg).put("description", fvgDescription))
            .put("liquidity", JSONObject().put("buy_side", fmt(liquidity.buySide)).put("sell_side", fmt(liquidity.sellSide)).put("sweep_occurred", liquidity.sweepOccurred).put("description", liquidity.description))
            .put("premium_discount", JSONObject().put("equilibrium", equilibrium).put("current_zone", currentZone).put("ote_zone", oteZone(bias, rangeLow, rangeHigh)))
            .put("active_poi", JSONArray().apply {
                activeBullFvg?.let { put(JSONObject().put("type", "bullish_fvg").put("zone", it.area()).put("score", it.score)) }
                activeBearFvg?.let { put(JSONObject().put("type", "bearish_fvg").put("zone", it.area()).put("score", it.score)) }
                if (ob != null && ob.active) put(JSONObject().put("type", "${ob.side}_ob").put("zone", ob.area()).put("score", ob.score))
            })
            .put("key_notes", JSONArray()
                .put("FVG disamakan dengan Pine: long-body candle tengah + low > high[2] / high < low[2].")
                .put("Liquidity disamakan dengan Pine: pivot cluster minimal 3 level dalam margin ATR.")
                .put("OB disamakan dengan Pine: muncul setelah close menembus swing, lalu zona diambil dari ekstrem antara swing dan break."))
            .put("warnings", JSONArray().apply {
                if (MarketPriceCache.latestPrice == null) put("Live tick belum masuk; current price memakai candle terakhir dari snapshot.")
                if (isChoppy(bars)) put("Market choppy; tunggu retest yang jelas.")
                if (setup.optString("status") == "wait") put("Setup masih WAIT; tunggu POI aktif dan konfirmasi retest.")
                if (abs(price - equilibrium) < atr * 0.25) put("Harga dekat equilibrium; area ini rawan noise.")
            })
            .toString()
    }

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

    private fun detectStructure(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>): StructureState {
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

    private fun detectFvgsTradingViewStyle(bars: List<Bar>, atr: Double, price: Double, maxDistance: Double): List<PoiZone> {
        val minGap = max(atr * 0.05, 0.03)
        val zones = mutableListOf<PoiZone>()
        for (i in 2 until bars.size) {
            val left = bars[i - 2]
            val mid = bars[i - 1]
            val right = bars[i]
            val meanBody = meanBodyBefore(bars, i - 1, 5)
            val longBodyUp = body(mid) > meanBody && cleanBody(mid) && mid.close > mid.open
            val longBodyDn = body(mid) > meanBody && cleanBody(mid) && mid.close < mid.open

            if (longBodyUp && right.low > left.high) {
                val low = left.high
                val high = right.low
                val gap = high - low
                val broken = bars.drop(i + 1).any { it.low < low }
                val partial = bars.drop(i + 1).any { it.low < high }
                val active = !broken && gap >= minGap && abs(((low + high) / 2.0) - price) <= maxDistance
                val score = 45 + freshnessScore(i, bars.lastIndex) + if (!partial) 15 else 5
                if (gap >= minGap) zones.add(PoiZone("bullish", low, high, score, active, "FVG"))
            }

            if (longBodyDn && right.high < left.low) {
                val low = right.high
                val high = left.low
                val gap = high - low
                val broken = bars.drop(i + 1).any { it.high > high }
                val partial = bars.drop(i + 1).any { it.high > low }
                val active = !broken && gap >= minGap && abs(((low + high) / 2.0) - price) <= maxDistance
                val score = 45 + freshnessScore(i, bars.lastIndex) + if (!partial) 15 else 5
                if (gap >= minGap) zones.add(PoiZone("bearish", low, high, score, active, "FVG"))
            }
        }
        return zones.sortedByDescending { it.score }
    }

    private fun detectLiquidityTradingViewStyle(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>, atr: Double, price: Double): LiquidityState {
        val margin = max(atr / 2.5, 0.10)
        val buyCluster = bestCluster(highs.map { it.level }, price, true, margin)
        val sellCluster = bestCluster(lows.map { it.level }, price, false, margin)
        val buyLevel = buyCluster ?: highs.map { it.level }.filter { it > price }.minOrNull() ?: bars.maxOf { it.high }
        val sellLevel = sellCluster ?: lows.map { it.level }.filter { it < price }.maxOrNull() ?: bars.minOf { it.low }

        var sweep = false
        var side = "none"
        var desc = if (buyCluster != null || sellCluster != null) {
            "Liquidity cluster aktif. Buy-side ${fmt(buyLevel)}, sell-side ${fmt(sellLevel)}"
        } else {
            "No fresh liquidity cluster. Buy-side ${fmt(buyLevel)}, sell-side ${fmt(sellLevel)}"
        }

        bars.takeLast(3).forEach { bar ->
            val range = (bar.high - bar.low).coerceAtLeast(0.0001)
            val topWickRatio = (bar.high - max(bar.open, bar.close)) / range
            val bottomWickRatio = (min(bar.open, bar.close) - bar.low) / range
            if (!sweep && bar.high > buyLevel + margin && bar.close < buyLevel && topWickRatio >= 0.30) {
                sweep = true
                side = "buy_side"
                desc = "Buy-side liquidity swept at ${fmt(buyLevel)}, close reclaimed below level."
            }
            if (!sweep && bar.low < sellLevel - margin && bar.close > sellLevel && bottomWickRatio >= 0.30) {
                sweep = true
                side = "sell_side"
                desc = "Sell-side liquidity swept at ${fmt(sellLevel)}, close reclaimed above level."
            }
        }
        return LiquidityState(buyLevel, sellLevel, sweep, desc, side)
    }

    private fun detectOrderBlockTradingViewStyle(
        bars: List<Bar>, atr: Double, price: Double, maxDistance: Double, currentZone: String, bias: String,
        structure: StructureState, hasSweep: Boolean, bullFvg: PoiZone?, bearFvg: PoiZone?, highs: List<Pivot>, lows: List<Pivot>
    ): PoiZone? {
        val candidates = mutableListOf<PoiZone>()
        val useBody = true
        val lastClose = bars.last().close
        val lastHigh = highs.lastOrNull()
        val lastLow = lows.lastOrNull()

        if (lastHigh != null && lastClose > lastHigh.level) {
            pineBullishOb(bars, lastHigh.index, useBody)?.let { zone ->
                val pdAligned = currentZone == "DISCOUNT"
                val hasFvg = bullFvg != null
                val score = obScore(zone, price, maxDistance, hasSweep, hasFvg, pdAligned, structure.breakText.contains("BULLISH"))
                if (score >= 35) candidates.add(zone.copy(score = score, active = abs(zone.mid - price) <= maxDistance))
            }
        }

        if (lastLow != null && lastClose < lastLow.level) {
            pineBearishOb(bars, lastLow.index, useBody)?.let { zone ->
                val pdAligned = currentZone == "PREMIUM"
                val hasFvg = bearFvg != null
                val score = obScore(zone, price, maxDistance, hasSweep, hasFvg, pdAligned, structure.breakText.contains("BEARISH"))
                if (score >= 35) candidates.add(zone.copy(score = score, active = abs(zone.mid - price) <= maxDistance))
            }
        }

        if (candidates.isEmpty()) return null
        val directional = candidates.filter { (bias == "BULLISH" && it.side == "bullish") || (bias == "BEARISH" && it.side == "bearish") }
        return (directional.ifEmpty { candidates }).maxByOrNull { it.score }?.takeIf { it.active }
    }

    private fun pineBullishOb(bars: List<Bar>, swingIndex: Int, useBody: Boolean): PoiZone? {
        if (swingIndex >= bars.lastIndex - 1) return null
        var bestLow = bodyMin(bars[swingIndex + 1], useBody)
        var bestHigh = bodyMax(bars[swingIndex + 1], useBody)
        for (i in swingIndex + 1 until bars.lastIndex) {
            val btm = bodyMin(bars[i], useBody)
            val top = bodyMax(bars[i], useBody)
            if (btm <= bestLow) {
                bestLow = btm
                bestHigh = top
            }
        }
        return PoiZone("bullish", min(bestLow, bestHigh), max(bestLow, bestHigh), 0, false, "Pine swing-break OB")
    }

    private fun pineBearishOb(bars: List<Bar>, swingIndex: Int, useBody: Boolean): PoiZone? {
        if (swingIndex >= bars.lastIndex - 1) return null
        var bestHigh = bodyMax(bars[swingIndex + 1], useBody)
        var bestLow = bodyMin(bars[swingIndex + 1], useBody)
        for (i in swingIndex + 1 until bars.lastIndex) {
            val top = bodyMax(bars[i], useBody)
            val btm = bodyMin(bars[i], useBody)
            if (top >= bestHigh) {
                bestHigh = top
                bestLow = btm
            }
        }
        return PoiZone("bearish", min(bestLow, bestHigh), max(bestLow, bestHigh), 0, false, "Pine swing-break OB")
    }

    private fun tradeSetup(
        bias: String, zone: String, price: Double, support: Double, resistance: Double, rangeHigh: Double, rangeLow: Double,
        atr: Double, bullFvg: PoiZone?, bearFvg: PoiZone?, ob: PoiZone?
    ): JSONObject {
        return when (bias) {
            "BULLISH" -> {
                val ote = oteZone("BULLISH", rangeLow, rangeHigh)
                val activePoi = when {
                    ob != null && ob.side == "bullish" && zone == "DISCOUNT" -> ob.area()
                    bullFvg != null && zone == "DISCOUNT" -> bullFvg.area()
                    else -> "Tunggu harga masuk discount/OTE: $ote"
                }
                val valid = zone == "DISCOUNT" && ((ob != null && ob.side == "bullish") || bullFvg != null) && support < price
                val tp1 = max(resistance, price + atr)
                val tp2 = max(rangeHigh, tp1 + atr)
                val sl = min(support, rangeLow) - atr
                JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", activePoi).put("tp1", tp1).put("tp2", tp2).put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona").put("invalidation", "Close kuat di bawah ${fmt(sl)}")
            }
            "BEARISH" -> {
                val ote = oteZone("BEARISH", rangeLow, rangeHigh)
                val activePoi = when {
                    ob != null && ob.side == "bearish" && zone == "PREMIUM" -> ob.area()
                    bearFvg != null && zone == "PREMIUM" -> bearFvg.area()
                    else -> "Tunggu harga masuk premium/OTE: $ote"
                }
                val valid = zone == "PREMIUM" && ((ob != null && ob.side == "bearish") || bearFvg != null) && resistance > price
                val tp1 = min(support, price - atr)
                val tp2 = min(rangeLow, tp1 - atr)
                val sl = max(resistance, rangeHigh) + atr
                JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", activePoi).put("tp1", tp1).put("tp2", tp2).put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona").put("invalidation", "Close kuat di atas ${fmt(sl)}")
            }
            else -> JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", "Tunggu BOS/CHoCH dan liquidity sweep")
        }
    }

    private fun calculateBias(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>, structure: StructureState, price: Double, eq: Double): String {
        if (structure.breakText.contains("BULLISH")) return "BULLISH"
        if (structure.breakText.contains("BEARISH")) return "BEARISH"
        val hh = highs.size >= 2 && highs.last().level > highs[highs.size - 2].level
        val hl = lows.size >= 2 && lows.last().level > lows[lows.size - 2].level
        val lh = highs.size >= 2 && highs.last().level < highs[highs.size - 2].level
        val ll = lows.size >= 2 && lows.last().level < lows[lows.size - 2].level
        val momentum = bars.takeLast(3).sumOf { it.close - it.open }
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

    private fun obScore(zone: PoiZone, price: Double, maxDistance: Double, sweep: Boolean, fvg: Boolean, pdAligned: Boolean, structureBreak: Boolean): Int {
        var score = 25
        if (abs(zone.mid - price) <= maxDistance) score += 20
        if (structureBreak) score += 15
        if (fvg) score += 15
        if (sweep) score += 10
        if (pdAligned) score += 10
        return score.coerceIn(0, 95)
    }

    private fun pivotsHigh(bars: List<Bar>, left: Int, right: Int): List<Pivot> {
        if (bars.size < left + right + 1) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until bars.size - right) {
            val level = bars[i].high
            if ((1..left).all { bars[i - it].high < level } && (1..right).all { bars[i + it].high < level }) out.add(Pivot(i, level))
        }
        return out
    }

    private fun pivotsLow(bars: List<Bar>, left: Int, right: Int): List<Pivot> {
        if (bars.size < left + right + 1) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until bars.size - right) {
            val level = bars[i].low
            if ((1..left).all { bars[i - it].low > level } && (1..right).all { bars[i + it].low > level }) out.add(Pivot(i, level))
        }
        return out
    }

    private fun bestCluster(levels: List<Double>, price: Double, above: Boolean, margin: Double): Double? {
        val candidates = levels.filter { if (above) it > price else it < price }.sorted()
        if (candidates.size < 3) return null
        val clusters = mutableListOf<List<Double>>()
        for (level in candidates) {
            val existingIndex = clusters.indexOfFirst { abs(it.average() - level) <= margin }
            if (existingIndex >= 0) {
                clusters[existingIndex] = clusters[existingIndex] + level
            } else {
                clusters.add(listOf(level))
            }
        }
        return clusters.filter { it.size >= 3 }
            .map { it.average() to it.size }
            .sortedWith(compareByDescending<Pair<Double, Int>> { it.second }.thenBy { abs(it.first - price) })
            .firstOrNull()?.first
    }

    private fun calculateAtr(bars: List<Bar>): Double {
        val sample = bars.takeLast(10)
        return if (sample.isEmpty()) 1.0 else sample.map { it.high - it.low }.average().coerceAtLeast(0.01)
    }

    private fun body(bar: Bar): Double = abs(bar.close - bar.open)

    private fun meanBodyBefore(bars: List<Bar>, index: Int, len: Int): Double {
        val from = (index - len).coerceAtLeast(0)
        val sample = bars.subList(from, index.coerceAtLeast(from) + 1)
        return sample.map { body(it) }.average().takeIf { !it.isNaN() } ?: 0.0
    }

    private fun cleanBody(bar: Bar): Boolean {
        val b = body(bar).coerceAtLeast(0.0001)
        val mx = max(bar.close, bar.open)
        val mn = min(bar.close, bar.open)
        return (bar.high - mx) < b * 0.36 && (mn - bar.low) < b * 0.36
    }

    private fun bodyRatio(bar: Bar): Double {
        val range = (bar.high - bar.low).coerceAtLeast(0.0001)
        return body(bar) / range
    }

    private fun bodyMax(bar: Bar, useBody: Boolean): Double = if (useBody) max(bar.open, bar.close) else bar.high
    private fun bodyMin(bar: Bar, useBody: Boolean): Double = if (useBody) min(bar.open, bar.close) else bar.low

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
        val range = (high - low).coerceAtLeast(0.0)
        return when (bias) {
            "BULLISH" -> "${fmt(high - range * 0.79)} - ${fmt(high - range * 0.62)}"
            "BEARISH" -> "${fmt(low + range * 0.62)} - ${fmt(low + range * 0.79)}"
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
