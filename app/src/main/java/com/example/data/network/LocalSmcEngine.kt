package com.example.data.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object LocalSmcEngine {
    private data class Bar(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val ticks: Int)
    private data class Pivot(val index: Int, val level: Double)
    private data class Event(val side: String, val tag: String, val level: Double, val pivotIndex: Int, val breakIndex: Int)
    private data class Zone(val side: String, val low: Double, val high: Double, val score: Int, val active: Boolean, val kind: String, val index: Int) {
        val mid: Double get() = (low + high) / 2.0
        fun area(): String = "${fmt(low)} - ${fmt(high)}"
    }

    fun compile(prompt: String): String {
        val timeframe = readText(prompt, "Requested timeframe") ?: "M1"
        val session = readText(prompt, "Session") ?: "-"
        val bars = parseBars(prompt)
        val price = MarketPriceCache.latestPrice ?: readNumber(prompt, "Current price") ?: bars.lastOrNull()?.close ?: 0.0
        if (bars.size < 20) return waitJson(timeframe, session, price, "Candle belum cukup untuk SMC mapping.")

        val vol = volatility(bars)
        val maxDistance = activeDistance(timeframe, vol)
        val parsedHigh = bars.map { if ((it.high - it.low) >= 2.0 * vol) it.low else it.high }
        val parsedLow = bars.map { if ((it.high - it.low) >= 2.0 * vol) it.high else it.low }
        val internalHighs = pivotsHigh(bars, 5, 5)
        val internalLows = pivotsLow(bars, 5, 5)
        val swingSize = if (bars.size >= 90) 50 else max(10, bars.size / 4)
        val swingHighs = pivotsHigh(bars, swingSize, swingSize).ifEmpty { internalHighs }
        val swingLows = pivotsLow(bars, swingSize, swingSize).ifEmpty { internalLows }
        val eqHighs = pivotsHigh(bars, 3, 3)
        val eqLows = pivotsLow(bars, 3, 3)

        val internalEvents = structureEvents(bars, internalHighs, internalLows)
        val swingEvents = structureEvents(bars, swingHighs, swingLows)
        val selectedEvent = swingEvents.lastOrNull() ?: internalEvents.lastOrNull()
        val trendSide = selectedEvent?.side ?: inferTrend(swingHighs, swingLows)
        val phase = when (selectedEvent?.tag) {
            "CHoCH" -> "REVERSAL_OR_MSS"
            "BOS" -> "EXPANSION"
            else -> if (isChoppy(bars)) "CHOPPY" else "RANGING"
        }

        val rangeHigh = max(swingHighs.lastOrNull()?.level ?: bars.maxOf { it.high }, swingLows.lastOrNull()?.level ?: bars.minOf { it.low })
        val rangeLow = min(swingHighs.lastOrNull()?.level ?: bars.maxOf { it.high }, swingLows.lastOrNull()?.level ?: bars.minOf { it.low })
        val eq = (rangeHigh + rangeLow) / 2.0
        val zone = pdZone(price, rangeLow, rangeHigh)
        val bias = when {
            trendSide == "bullish" -> "BULLISH"
            trendSide == "bearish" -> "BEARISH"
            bars.takeLast(3).sumOf { it.close - it.open } > 0 && price > eq -> "BULLISH"
            bars.takeLast(3).sumOf { it.close - it.open } < 0 && price < eq -> "BEARISH"
            else -> "NEUTRAL"
        }

        val liquidity = liquidity(bars, eqHighs, eqLows, internalHighs, internalLows, vol, price)
        val fvgs = fairValueGaps(bars, vol, price, maxDistance)
        val obs = orderBlocks(bars, parsedHigh, parsedLow, internalEvents + swingEvents, price, maxDistance)
        val bullFvg = fvgs.filter { it.side == "bullish" && it.active }.maxByOrNull { it.score }
        val bearFvg = fvgs.filter { it.side == "bearish" && it.active }.maxByOrNull { it.score }
        val bullOb = obs.filter { it.side == "bullish" && it.active }.maxByOrNull { it.score }
        val bearOb = obs.filter { it.side == "bearish" && it.active }.maxByOrNull { it.score }

        val setup = setup(bias, zone, price, liquidity.sellSide, liquidity.buySide, rangeHigh, rangeLow, vol, bullFvg, bearFvg, bullOb, bearOb)
        val confidence = confidence(bias, bars, liquidity.swept, (bias == "BULLISH" && bullFvg != null) || (bias == "BEARISH" && bearFvg != null), (bias == "BULLISH" && bullOb != null) || (bias == "BEARISH" && bearOb != null), setup.optString("status") == "valid")
        val breakText = selectedEvent?.let { "${it.side.uppercase(Locale.US)} ${it.tag} at ${fmt(it.level)}" } ?: "None"
        val chochText = selectedEvent?.takeIf { it.tag == "CHoCH" }?.let { "${it.side.uppercase(Locale.US)} CHoCH at ${fmt(it.level)}" } ?: "Belum ada CHoCH baru"
        val fvgDesc = listOfNotNull(bullFvg?.let { "Bullish FVG ${it.area()}" }, bearFvg?.let { "Bearish FVG ${it.area()}" }).joinToString(" | ").ifBlank { "Tidak ada FVG aktif dekat harga" }
        val obDesc = listOfNotNull(bullOb?.let { "Bullish OB ${it.area()} | ${it.kind} | score ${it.score}" }, bearOb?.let { "Bearish OB ${it.area()} | ${it.kind} | score ${it.score}" }).joinToString(" | ").ifBlank { "Tidak ada OB aktif; OB yang sudah mitigated atau terlalu jauh diabaikan" }
        val summary = when (bias) {
            "BULLISH" -> "Market dalam fase $phase dengan bias bullish. Harga live berada di zona ${zone.lowercase(Locale.US)}. BSL ${fmt(liquidity.buySide)}, SSL ${fmt(liquidity.sellSide)}. Buy hanya valid jika harga kembali ke discount/OTE dan POI bullish aktif."
            "BEARISH" -> "Market dalam fase $phase dengan bias bearish. Harga live berada di zona ${zone.lowercase(Locale.US)}. BSL ${fmt(liquidity.buySide)}, SSL ${fmt(liquidity.sellSide)}. Sell hanya valid jika harga kembali ke premium/OTE dan POI bearish aktif."
            else -> "Market dalam fase $phase dengan bias netral. Harga live berada di zona ${zone.lowercase(Locale.US)} dan belum ada rangkaian struktur, liquidity, dan POI aktif yang cukup kuat."
        }

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
                .put("last_bos", breakText)
                .put("choch", chochText)
                .put("internal_structure", internalEvents.lastOrNull()?.let { "${it.side.uppercase(Locale.US)} ${it.tag} ${fmt(it.level)}" } ?: "No internal break")
                .put("swing_structure", swingEvents.lastOrNull()?.let { "${it.side.uppercase(Locale.US)} ${it.tag} ${fmt(it.level)}" } ?: "No swing break")
                .put("swing_high", liquidity.buySide)
                .put("swing_low", liquidity.sellSide)
                .put("liquidity", liquidity.text)
                .put("fvg", fvgDesc)
                .put("order_block", obDesc)
                .put("premium_discount", "Range ${fmt(rangeLow)} - ${fmt(rangeHigh)} | EQ ${fmt(eq)} | Zona $zone"))
            .put("order_blocks", JSONObject().put("bullish_ob", bullOb?.area() ?: "-").put("bearish_ob", bearOb?.area() ?: "-").put("description", obDesc))
            .put("fvg", JSONObject().put("bullish_fvg", bullFvg?.area() ?: "-").put("bearish_fvg", bearFvg?.area() ?: "-").put("description", fvgDesc))
            .put("liquidity", JSONObject().put("buy_side", fmt(liquidity.buySide)).put("sell_side", fmt(liquidity.sellSide)).put("sweep_occurred", liquidity.swept).put("description", liquidity.text))
            .put("premium_discount", JSONObject().put("equilibrium", eq).put("current_zone", zone).put("premium_zone", "${fmt(0.95 * rangeHigh + 0.05 * rangeLow)} - ${fmt(rangeHigh)}").put("discount_zone", "${fmt(rangeLow)} - ${fmt(0.95 * rangeLow + 0.05 * rangeHigh)}").put("ote_zone", ote(bias, rangeLow, rangeHigh)))
            .put("active_poi", JSONArray().apply {
                bullFvg?.let { put(JSONObject().put("type", "bullish_fvg").put("zone", it.area()).put("score", it.score)) }
                bearFvg?.let { put(JSONObject().put("type", "bearish_fvg").put("zone", it.area()).put("score", it.score)) }
                bullOb?.let { put(JSONObject().put("type", "bullish_order_block").put("zone", it.area()).put("score", it.score)) }
                bearOb?.let { put(JSONObject().put("type", "bearish_order_block").put("zone", it.area()).put("score", it.score)) }
            })
            .put("key_notes", JSONArray().put("Semua komponen SMC memakai rule lokal: structure, OB, FVG, liquidity, premium/discount, OTE, dan setup.").put("OB dibuat dari ekstrem parsed high/low antara pivot dan break, lalu dihapus jika mitigated.").put("FVG memakai threshold candle delta dan dihapus jika mitigated.").put("Liquidity memakai EQH/EQL dan pivot terdekat dengan tolerance volatilitas."))
            .put("warnings", JSONArray().apply {
                if (MarketPriceCache.latestPrice == null) put("Live tick belum masuk; current price memakai candle snapshot.")
                if (isChoppy(bars)) put("Market choppy; tunggu displacement/retest jelas.")
                if (setup.optString("status") == "wait") put("Setup masih WAIT; tunggu zona dan POI aktif searah bias.")
            })
            .toString()
    }

    private data class Liq(val buySide: Double, val sellSide: Double, val swept: Boolean, val text: String)

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

    private fun structureEvents(bars: List<Bar>, highs: List<Pivot>, lows: List<Pivot>): List<Event> {
        val out = mutableListOf<Event>()
        val crossedHigh = mutableSetOf<Int>()
        val crossedLow = mutableSetOf<Int>()
        var trend = "neutral"
        for (i in bars.indices) {
            highs.filter { it.index < i && it.index !in crossedHigh }.forEach { p ->
                if (bars[i].close > p.level) {
                    val tag = if (trend == "bearish") "CHoCH" else "BOS"
                    out.add(Event("bullish", tag, p.level, p.index, i))
                    crossedHigh.add(p.index)
                    trend = "bullish"
                }
            }
            lows.filter { it.index < i && it.index !in crossedLow }.forEach { p ->
                if (bars[i].close < p.level) {
                    val tag = if (trend == "bullish") "CHoCH" else "BOS"
                    out.add(Event("bearish", tag, p.level, p.index, i))
                    crossedLow.add(p.index)
                    trend = "bearish"
                }
            }
        }
        return out.sortedBy { it.breakIndex }
    }

    private fun orderBlocks(bars: List<Bar>, ph: List<Double>, pl: List<Double>, events: List<Event>, price: Double, maxDistance: Double): List<Zone> {
        val zones = mutableListOf<Zone>()
        events.sortedBy { it.breakIndex }.forEach { e ->
            val from = e.pivotIndex.coerceIn(0, bars.lastIndex)
            val to = e.breakIndex.coerceIn(from + 1, bars.size)
            if (to <= from) return@forEach
            val idx = if (e.side == "bullish") pl.subList(from, to).withIndex().minByOrNull { it.value }?.index?.plus(from)
            else ph.subList(from, to).withIndex().maxByOrNull { it.value }?.index?.plus(from)
            if (idx != null) {
                val low = min(ph[idx], pl[idx])
                val high = max(ph[idx], pl[idx])
                val mitigated = bars.drop(idx + 1).any { if (e.side == "bullish") it.low < low else it.high > high }
                val active = !mitigated && abs(((low + high) / 2.0) - price) <= maxDistance
                val score = (45 + fresh(idx, bars.lastIndex) + (if (e.tag == "CHoCH") 15 else 5) + (if (active) 20 else 0)).coerceIn(0, 95)
                zones.add(Zone(e.side, low, high, score, active, "${e.tag} order block", idx))
            }
        }
        return zones.sortedWith(compareByDescending<Zone> { it.active }.thenByDescending { it.score })
    }

    private fun fairValueGaps(bars: List<Bar>, vol: Double, price: Double, maxDistance: Double): List<Zone> {
        val zones = mutableListOf<Zone>()
        val deltas = mutableListOf<Double>()
        for (i in 2 until bars.size) {
            val last = bars[i - 1]
            val now = bars[i]
            val prev2 = bars[i - 2]
            val delta = (last.close - last.open) / (last.open * 100.0)
            deltas.add(abs(delta))
            val threshold = deltas.average() * 2.0
            if (now.low > prev2.high && last.close > prev2.high && delta > threshold) {
                val low = prev2.high
                val high = now.low
                val mitigated = bars.drop(i + 1).any { it.low < low }
                val active = !mitigated && high - low >= max(vol * 0.03, 0.02) && abs(((low + high) / 2.0) - price) <= maxDistance
                zones.add(Zone("bullish", low, high, 50 + fresh(i, bars.lastIndex) + if (active) 20 else 0, active, "FVG", i))
            }
            if (now.high < prev2.low && last.close < prev2.low && -delta > threshold) {
                val low = now.high
                val high = prev2.low
                val mitigated = bars.drop(i + 1).any { it.high > high }
                val active = !mitigated && high - low >= max(vol * 0.03, 0.02) && abs(((low + high) / 2.0) - price) <= maxDistance
                zones.add(Zone("bearish", low, high, 50 + fresh(i, bars.lastIndex) + if (active) 20 else 0, active, "FVG", i))
            }
        }
        return zones.sortedWith(compareByDescending<Zone> { it.active }.thenByDescending { it.score })
    }

    private fun liquidity(bars: List<Bar>, eqHighs: List<Pivot>, eqLows: List<Pivot>, highs: List<Pivot>, lows: List<Pivot>, vol: Double, price: Double): Liq {
        val tol = max(vol * 0.10, 0.05)
        val eqh = cluster(eqHighs.map { it.level }.filter { it > price }, tol)
        val eql = cluster(eqLows.map { it.level }.filter { it < price }, tol)
        val buy = eqh ?: highs.map { it.level }.filter { it > price }.minOrNull() ?: bars.maxOf { it.high }
        val sell = eql ?: lows.map { it.level }.filter { it < price }.maxOrNull() ?: bars.minOf { it.low }
        var swept = false
        var text = when {
            eqh != null && eql != null -> "EQH ${fmt(eqh)} dan EQL ${fmt(eql)} terdeteksi."
            eqh != null -> "EQH ${fmt(eqh)} terdeteksi. SSL ${fmt(sell)}."
            eql != null -> "EQL ${fmt(eql)} terdeteksi. BSL ${fmt(buy)}."
            else -> "No fresh EQH/EQL. Buy-side ${fmt(buy)}, sell-side ${fmt(sell)}"
        }
        bars.takeLast(3).forEach { b ->
            val r = (b.high - b.low).coerceAtLeast(0.0001)
            val topWick = (b.high - max(b.open, b.close)) / r
            val botWick = (min(b.open, b.close) - b.low) / r
            if (!swept && b.high > buy + tol && b.close < buy && topWick >= 0.30) {
                swept = true
                text = "Buy-side liquidity swept at ${fmt(buy)}, close reclaim di bawah level."
            }
            if (!swept && b.low < sell - tol && b.close > sell && botWick >= 0.30) {
                swept = true
                text = "Sell-side liquidity swept at ${fmt(sell)}, close reclaim di atas level."
            }
        }
        return Liq(buy, sell, swept, text)
    }

    private fun setup(bias: String, zone: String, price: Double, sellSide: Double, buySide: Double, rangeHigh: Double, rangeLow: Double, vol: Double, bullFvg: Zone?, bearFvg: Zone?, bullOb: Zone?, bearOb: Zone?): JSONObject {
        return when (bias) {
            "BULLISH" -> {
                val valid = zone == "DISCOUNT" && (bullOb != null || bullFvg != null)
                val entry = if (valid) (bullOb ?: bullFvg)!!.area() else "Tunggu harga masuk discount/OTE: ${ote("BULLISH", rangeLow, rangeHigh)}"
                val tp1 = if (valid) max(buySide, price + vol) else 0.0
                val tp2 = if (valid) max(rangeHigh, tp1 + vol) else 0.0
                val sl = if (valid) min(sellSide, rangeLow) - vol else 0.0
                JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", entry).put("tp1", tp1).put("tp2", tp2).put("stop_loss", sl).put("risk_reward", if (valid) "Minimal 1:2 jika entry dekat zona" else "-").put("invalidation", if (valid) "Close kuat di bawah ${fmt(sl)}" else "Tunggu discount + POI bullish aktif")
            }
            "BEARISH" -> {
                val valid = zone == "PREMIUM" && (bearOb != null || bearFvg != null)
                val entry = if (valid) (bearOb ?: bearFvg)!!.area() else "Tunggu harga masuk premium/OTE: ${ote("BEARISH", rangeLow, rangeHigh)}"
                val tp1 = if (valid) min(sellSide, price - vol) else 0.0
                val tp2 = if (valid) min(rangeLow, tp1 - vol) else 0.0
                val sl = if (valid) max(buySide, rangeHigh) + vol else 0.0
                JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", entry).put("tp1", tp1).put("tp2", tp2).put("stop_loss", sl).put("risk_reward", if (valid) "Minimal 1:2 jika entry dekat zona" else "-").put("invalidation", if (valid) "Close kuat di atas ${fmt(sl)}" else "Tunggu premium + POI bearish aktif")
            }
            else -> JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", "Tunggu BOS/CHoCH dan liquidity sweep")
        }
    }

    private fun pivotsHigh(bars: List<Bar>, left: Int, right: Int): List<Pivot> {
        if (bars.size <= left + right) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until bars.size - right) {
            val level = bars[i].high
            if ((1..left).all { bars[i - it].high < level } && (1..right).all { bars[i + it].high < level }) out.add(Pivot(i, level))
        }
        return out
    }

    private fun pivotsLow(bars: List<Bar>, left: Int, right: Int): List<Pivot> {
        if (bars.size <= left + right) return emptyList()
        val out = mutableListOf<Pivot>()
        for (i in left until bars.size - right) {
            val level = bars[i].low
            if ((1..left).all { bars[i - it].low > level } && (1..right).all { bars[i + it].low > level }) out.add(Pivot(i, level))
        }
        return out
    }

    private fun inferTrend(highs: List<Pivot>, lows: List<Pivot>): String {
        val hh = highs.size >= 2 && highs.last().level > highs[highs.size - 2].level
        val hl = lows.size >= 2 && lows.last().level > lows[lows.size - 2].level
        val lh = highs.size >= 2 && highs.last().level < highs[highs.size - 2].level
        val ll = lows.size >= 2 && lows.last().level < lows[lows.size - 2].level
        return when { hh && hl -> "bullish"; lh && ll -> "bearish"; else -> "range" }
    }

    private fun cluster(levels: List<Double>, tol: Double): Double? {
        if (levels.size < 2) return null
        val clusters = mutableListOf<List<Double>>()
        levels.sorted().forEach { level ->
            val idx = clusters.indexOfFirst { abs(it.average() - level) <= tol }
            if (idx >= 0) clusters[idx] = clusters[idx] + level else clusters.add(listOf(level))
        }
        return clusters.filter { it.size >= 2 }.maxByOrNull { it.size }?.average()
    }

    private fun volatility(bars: List<Bar>): Double {
        val ranges = bars.map { it.high - it.low }.filter { it > 0.0 }
        val sample = ranges.takeLast(min(200, ranges.size))
        return (sample.average().takeIf { !it.isNaN() } ?: ranges.average().takeIf { !it.isNaN() } ?: 1.0).coerceAtLeast(0.01)
    }

    private fun pdZone(price: Double, low: Double, high: Double): String {
        val eqTop = 0.525 * high + 0.475 * low
        val eqBottom = 0.525 * low + 0.475 * high
        return when { price in eqBottom..eqTop -> "EQUILIBRIUM"; price > eqTop -> "PREMIUM"; else -> "DISCOUNT" }
    }

    private fun ote(bias: String, low: Double, high: Double): String {
        val r = (high - low).coerceAtLeast(0.0)
        return when (bias) { "BULLISH" -> "${fmt(high - r * 0.79)} - ${fmt(high - r * 0.62)}"; "BEARISH" -> "${fmt(low + r * 0.62)} - ${fmt(low + r * 0.79)}"; else -> "Belum ada OTE valid" }
    }

    private fun confidence(bias: String, bars: List<Bar>, sweep: Boolean, fvg: Boolean, ob: Boolean, valid: Boolean): Int {
        var s = if (bias == "NEUTRAL") 35 else 50
        if (sweep) s += 10
        if (fvg) s += 10
        if (ob) s += 15
        if (valid) s += 10
        if (isChoppy(bars)) s -= 15
        return s.coerceIn(25, 90)
    }

    private fun isChoppy(bars: List<Bar>): Boolean = bars.takeLast(5).count { abs(it.close - it.open) / (it.high - it.low).coerceAtLeast(0.0001) < 0.30 } >= 3
    private fun activeDistance(tf: String, vol: Double): Double = when (tf.uppercase(Locale.US)) { "M1" -> vol * 8; "M5" -> vol * 10; "M15" -> vol * 12; "M30" -> vol * 14; "H1" -> vol * 18; else -> vol * 22 }.coerceAtLeast(6.0)
    private fun fresh(index: Int, last: Int): Int = when ((last - index).coerceAtLeast(0)) { in 0..3 -> 30; in 4..8 -> 20; in 9..15 -> 10; in 16..30 -> 5; else -> 0 }

    private fun waitJson(timeframe: String, session: String, price: Double, reason: String): String = JSONObject()
        .put("bias", "NEUTRAL").put("confidence_score", 25).put("timeframe", timeframe).put("session_context", session).put("current_price", price).put("daily_bias_summary", reason)
        .put("trade_setup", JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", "Tunggu candle lengkap"))
        .put("market_structure", JSONObject().put("trend", "Range").put("last_bos", "None").put("choch", "-").put("swing_high", 0.0).put("swing_low", 0.0).put("liquidity", reason).put("fvg", "-").put("order_block", "-").put("premium_discount", "-"))
        .put("order_blocks", JSONObject().put("bullish_ob", "-").put("bearish_ob", "-").put("description", "-"))
        .put("fvg", JSONObject().put("bullish_fvg", "-").put("bearish_fvg", "-").put("description", "-"))
        .put("liquidity", JSONObject().put("buy_side", "-").put("sell_side", "-").put("sweep_occurred", false).put("description", reason))
        .put("premium_discount", JSONObject().put("equilibrium", 0.0).put("current_zone", "EQUILIBRIUM").put("ote_zone", "-"))
        .put("key_notes", JSONArray().put(reason)).put("warnings", JSONArray().put(reason)).toString()

    private fun readText(prompt: String, label: String): String? = Regex("$label:\\s*([^\\n]+)").find(prompt)?.groupValues?.get(1)?.trim()?.takeIf { it != "-" }
    private fun readNumber(prompt: String, label: String): Double? = readText(prompt, label)?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }
    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}
