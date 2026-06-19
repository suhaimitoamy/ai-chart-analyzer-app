package com.example.data.network

import com.example.data.database.CandleEntity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object MarketEventScanner {
    data class Event(val key: String, val priority: Int, val text: String)
    private data class Pivot(val index: Int, val high: Double? = null, val low: Double? = null)

    fun scan(timeframe: String, candles: List<CandleEntity>, livePrice: Double? = null): List<Event> {
        val tf = timeframe.uppercase(Locale.US)
        val rows = candles.sortedBy { it.time }.takeLast(180)
        if (rows.size < 8) return emptyList()

        val price = livePrice ?: rows.last().close
        val atr = atr(rows).coerceAtLeast(0.05)
        val events = mutableListOf<Event>()

        events += scanStructure(tf, rows, atr)
        events += scanCisd(tf, rows, atr)
        events += scanLiquidity(tf, rows, atr)
        events += scanFvg(tf, rows, atr, price)
        events += scanOrderBlocks(tf, rows, atr, price)
        events += scanPremiumDiscount(tf, rows, price)
        events += scanDisplacement(tf, rows, atr)

        return events
            .distinctBy { it.key }
            .sortedWith(compareByDescending<Event> { it.priority }.thenBy { it.key })
            .take(12)
    }

    private fun scanStructure(tf: String, rows: List<CandleEntity>, atr: Double): List<Event> {
        val latest = rows.last()
        val highs = pivotsHigh(rows, 3, 2)
        val lows = pivotsLow(rows, 3, 2)
        val lastHigh = highs.lastOrNull { it.index < rows.lastIndex - 1 }
        val lastLow = lows.lastOrNull { it.index < rows.lastIndex - 1 }
        val trend = inferTrend(highs, lows)
        val out = mutableListOf<Event>()

        if (lastHigh?.high != null && latest.close > lastHigh.high) {
            val tag = if (trend == "bearish") "MSS" else "BOS"
            out += Event("$tf-$tag-BULL-${lastHigh.index}-${roundKey(lastHigh.high)}", 95, "[$tf] $tag Bullish confirmed @ ${fmt(lastHigh.high)}")
        }
        if (lastLow?.low != null && latest.close < lastLow.low) {
            val tag = if (trend == "bullish") "MSS" else "BOS"
            out += Event("$tf-$tag-BEAR-${lastLow.index}-${roundKey(lastLow.low)}", 95, "[$tf] $tag Bearish confirmed @ ${fmt(lastLow.low)}")
        }

        val hasDisplacement = bodyRatio(latest) >= 0.60 || (latest.high - latest.low) >= atr * 1.20
        if (hasDisplacement && lastHigh?.high != null && latest.high > lastHigh.high && latest.close < lastHigh.high) {
            out += Event("$tf-REJECT-BSL-${latest.time}-${roundKey(lastHigh.high)}", 88, "[$tf] Buy-side break rejected near ${fmt(lastHigh.high)}")
        }
        if (hasDisplacement && lastLow?.low != null && latest.low < lastLow.low && latest.close > lastLow.low) {
            out += Event("$tf-REJECT-SSL-${latest.time}-${roundKey(lastLow.low)}", 88, "[$tf] Sell-side break rejected near ${fmt(lastLow.low)}")
        }
        return out
    }

    private fun scanCisd(tf: String, rows: List<CandleEntity>, atr: Double): List<Event> {
        if (rows.size < 4) return emptyList()
        val latest = rows.last()
        val prev1 = rows[rows.lastIndex - 1]
        val prev2 = rows[rows.lastIndex - 2]
        val impulse = abs(latest.close - latest.open) >= atr * 0.45 || bodyRatio(latest) >= 0.60
        val out = mutableListOf<Event>()

        if (prev1.close < prev1.open && prev2.close < prev2.open && latest.close > max(prev1.open, prev2.open) && impulse) {
            out += Event("$tf-CISD-BULL-${latest.time}", 92, "[$tf] Bullish CISD confirmed @ ${fmt(latest.close)}")
        }
        if (prev1.close > prev1.open && prev2.close > prev2.open && latest.close < min(prev1.open, prev2.open) && impulse) {
            out += Event("$tf-CISD-BEAR-${latest.time}", 92, "[$tf] Bearish CISD confirmed @ ${fmt(latest.close)}")
        }
        return out
    }

    private fun scanLiquidity(tf: String, rows: List<CandleEntity>, atr: Double): List<Event> {
        val latest = rows.last()
        val highs = pivotsHigh(rows, 3, 2)
        val lows = pivotsLow(rows, 3, 2)
        val buySide = highs.mapNotNull { it.high }.filter { it > latest.close }.minOrNull() ?: rows.takeLast(60).maxOf { it.high }
        val sellSide = lows.mapNotNull { it.low }.filter { it < latest.close }.maxOrNull() ?: rows.takeLast(60).minOf { it.low }
        val tolerance = max(atr * 0.10, 0.05)
        val out = mutableListOf<Event>()

        equalCluster(highs.mapNotNull { it.high }, tolerance, true, latest.close)?.let {
            out += Event("$tf-EQH-${roundKey(it)}", 80, "[$tf] EQH / BSL cluster detected @ ${fmt(it)}")
        }
        equalCluster(lows.mapNotNull { it.low }, tolerance, false, latest.close)?.let {
            out += Event("$tf-EQL-${roundKey(it)}", 80, "[$tf] EQL / SSL cluster detected @ ${fmt(it)}")
        }

        val range = (latest.high - latest.low).coerceAtLeast(0.0001)
        val topWick = (latest.high - max(latest.open, latest.close)) / range
        val bottomWick = (min(latest.open, latest.close) - latest.low) / range
        if (latest.high > buySide + tolerance && latest.close < buySide && topWick >= 0.30) {
            out += Event("$tf-BSL-SWEPT-${latest.time}-${roundKey(buySide)}", 98, "[$tf] Buy-side liquidity swept @ ${fmt(buySide)}")
        }
        if (latest.low < sellSide - tolerance && latest.close > sellSide && bottomWick >= 0.30) {
            out += Event("$tf-SSL-SWEPT-${latest.time}-${roundKey(sellSide)}", 98, "[$tf] Sell-side liquidity swept @ ${fmt(sellSide)}")
        }
        return out
    }

    private fun scanFvg(tf: String, rows: List<CandleEntity>, atr: Double, price: Double): List<Event> {
        if (rows.size < 3) return emptyList()
        val i = rows.lastIndex
        val left = rows[i - 2]
        val mid = rows[i - 1]
        val right = rows[i]
        val out = mutableListOf<Event>()
        val minGap = max(atr * 0.03, 0.02)
        val impulse = bodyRatio(mid) >= 0.50 || (mid.high - mid.low) >= atr

        if (impulse && right.low > left.high && right.low - left.high >= minGap) {
            val low = left.high
            val high = right.low
            val status = if (abs(((low + high) / 2.0) - price) <= atr * 10.0) "ACTIVE" else "CONTEXT"
            out += Event("$tf-FVG-BULL-${right.time}-${roundKey(low)}-${roundKey(high)}", 84, "[$tf] Bullish FVG formed $status ${fmt(low)} - ${fmt(high)}")
        }
        if (impulse && right.high < left.low && left.low - right.high >= minGap) {
            val low = right.high
            val high = left.low
            val status = if (abs(((low + high) / 2.0) - price) <= atr * 10.0) "ACTIVE" else "CONTEXT"
            out += Event("$tf-FVG-BEAR-${right.time}-${roundKey(low)}-${roundKey(high)}", 84, "[$tf] Bearish FVG formed $status ${fmt(low)} - ${fmt(high)}")
        }
        out += scanMitigatedFvg(tf, rows, atr)
        return out
    }

    private fun scanMitigatedFvg(tf: String, rows: List<CandleEntity>, atr: Double): List<Event> {
        val latest = rows.last()
        val out = mutableListOf<Event>()
        val start = (rows.size - 25).coerceAtLeast(2)
        for (i in start until rows.lastIndex) {
            val left = rows[i - 2]
            val right = rows[i]
            val minGap = max(atr * 0.03, 0.02)
            if (right.low - left.high >= minGap && latest.low <= left.high) {
                out += Event("$tf-FVG-BULL-MIT-$i-${roundKey(left.high)}", 70, "[$tf] Bullish FVG mitigated @ ${fmt(left.high)}")
            }
            if (left.low - right.high >= minGap && latest.high >= left.low) {
                out += Event("$tf-FVG-BEAR-MIT-$i-${roundKey(left.low)}", 70, "[$tf] Bearish FVG mitigated @ ${fmt(left.low)}")
            }
        }
        return out.takeLast(2)
    }

    private fun scanOrderBlocks(tf: String, rows: List<CandleEntity>, atr: Double, price: Double): List<Event> {
        val latest = rows.last()
        val highs = pivotsHigh(rows, 3, 2)
        val lows = pivotsLow(rows, 3, 2)
        val out = mutableListOf<Event>()
        val lastHigh = highs.lastOrNull { it.index < rows.lastIndex - 1 }
        val lastLow = lows.lastOrNull { it.index < rows.lastIndex - 1 }

        if (lastHigh?.high != null && latest.close > lastHigh.high) {
            findBullishOb(rows, lastHigh.index, rows.lastIndex)?.let { (low, high, idx) ->
                val status = if (abs(((low + high) / 2.0) - price) <= atr * 10.0) "ACTIVE" else "CONTEXT"
                out += Event("$tf-OB-BULL-$idx-${roundKey(low)}-${roundKey(high)}", 82, "[$tf] Bullish OB created $status ${fmt(low)} - ${fmt(high)}")
            }
        }
        if (lastLow?.low != null && latest.close < lastLow.low) {
            findBearishOb(rows, lastLow.index, rows.lastIndex)?.let { (low, high, idx) ->
                val status = if (abs(((low + high) / 2.0) - price) <= atr * 10.0) "ACTIVE" else "CONTEXT"
                out += Event("$tf-OB-BEAR-$idx-${roundKey(low)}-${roundKey(high)}", 82, "[$tf] Bearish OB created $status ${fmt(low)} - ${fmt(high)}")
            }
        }
        return out
    }

    private fun scanPremiumDiscount(tf: String, rows: List<CandleEntity>, price: Double): List<Event> {
        val recent = rows.takeLast(80)
        val high = recent.maxOf { it.high }
        val low = recent.minOf { it.low }
        val eq = (high + low) / 2.0
        val zone = if (price > eq) "PREMIUM" else if (price < eq) "DISCOUNT" else "EQUILIBRIUM"
        return listOf(Event("$tf-PD-$zone-${roundKey(eq)}", 60, "[$tf] Price in $zone | EQ ${fmt(eq)}"))
    }

    private fun scanDisplacement(tf: String, rows: List<CandleEntity>, atr: Double): List<Event> {
        val latest = rows.last()
        val move = abs(latest.close - latest.open)
        return if (move >= atr * 1.5 && bodyRatio(latest) >= 0.65) {
            val dir = if (latest.close > latest.open) "bullish" else "bearish"
            listOf(Event("$tf-DISPLACEMENT-$dir-${latest.time}", 78, "[$tf] ${dir.uppercase(Locale.US)} displacement candle C:${fmt(latest.close)}"))
        } else emptyList()
    }

    private fun pivotsHigh(rows: List<CandleEntity>, left: Int, right: Int): List<Pivot> {
        if (rows.size <= left + right) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until rows.size - right) {
            val level = rows[i].high
            if ((1..left).all { rows[i - it].high < level } && (1..right).all { rows[i + it].high < level }) out += Pivot(i, high = level)
        }
        return out
    }

    private fun pivotsLow(rows: List<CandleEntity>, left: Int, right: Int): List<Pivot> {
        if (rows.size <= left + right) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until rows.size - right) {
            val level = rows[i].low
            if ((1..left).all { rows[i - it].low > level } && (1..right).all { rows[i + it].low > level }) out += Pivot(i, low = level)
        }
        return out
    }

    private fun inferTrend(highs: List<Pivot>, lows: List<Pivot>): String {
        val h = highs.mapNotNull { it.high }
        val l = lows.mapNotNull { it.low }
        val hh = h.size >= 2 && h.last() > h[h.size - 2]
        val hl = l.size >= 2 && l.last() > l[l.size - 2]
        val lh = h.size >= 2 && h.last() < h[h.size - 2]
        val ll = l.size >= 2 && l.last() < l[l.size - 2]
        return when {
            hh && hl -> "bullish"
            lh && ll -> "bearish"
            else -> "range"
        }
    }

    private fun equalCluster(levels: List<Double>, tolerance: Double, abovePrice: Boolean, price: Double): Double? {
        val filtered = levels.filter { if (abovePrice) it > price else it < price }.sorted()
        if (filtered.size < 2) return null
        val clusters = mutableListOf<List<Double>>()
        for (level in filtered) {
            val index = clusters.indexOfFirst { abs(it.average() - level) <= tolerance }
            if (index >= 0) clusters[index] = clusters[index] + level else clusters += listOf(level)
        }
        return clusters.filter { it.size >= 2 }.maxByOrNull { it.size }?.average()
    }

    private fun findBullishOb(rows: List<CandleEntity>, from: Int, to: Int): Triple<Double, Double, Int>? {
        if (to <= from + 1) return null
        var idx = -1
        var low = Double.MAX_VALUE
        for (i in from + 1 until to) {
            if (rows[i].low < low) {
                low = rows[i].low
                idx = i
            }
        }
        return if (idx >= 0) Triple(min(rows[idx].open, rows[idx].close), max(rows[idx].open, rows[idx].close), idx) else null
    }

    private fun findBearishOb(rows: List<CandleEntity>, from: Int, to: Int): Triple<Double, Double, Int>? {
        if (to <= from + 1) return null
        var idx = -1
        var high = Double.MIN_VALUE
        for (i in from + 1 until to) {
            if (rows[i].high > high) {
                high = rows[i].high
                idx = i
            }
        }
        return if (idx >= 0) Triple(min(rows[idx].open, rows[idx].close), max(rows[idx].open, rows[idx].close), idx) else null
    }

    private fun atr(rows: List<CandleEntity>): Double {
        val ranges = rows.takeLast(14).map { it.high - it.low }.filter { it > 0.0 }
        return ranges.average().takeIf { !it.isNaN() } ?: 0.50
    }

    private fun bodyRatio(c: CandleEntity): Double {
        val range = (c.high - c.low).coerceAtLeast(0.0001)
        return abs(c.close - c.open) / range
    }

    private fun roundKey(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}
