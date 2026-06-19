package com.example.ui.dashboard

import com.example.data.database.CandleEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

object RuleBasedIctEngine {
    private data class FvgInfo(
        val bullish: String,
        val bearish: String,
        val summary: String,
        val direction: String
    )

    private data class ObInfo(
        val bullish: String,
        val bearish: String,
        val summary: String
    )

    fun build(
        timeframe: String,
        session: String,
        notes: String,
        candles: List<CandleEntity>,
        livePrice: Double?
    ): String {
        val recent = candles.takeLast(60)
        val latest = recent.last()
        val currentPrice = livePrice ?: latest.close
        val previous = recent.dropLast(1)
        val last20 = previous.takeLast(20).ifEmpty { previous.ifEmpty { recent } }
        val high20 = last20.maxOfOrNull { it.high } ?: latest.high
        val low20 = last20.minOfOrNull { it.low } ?: latest.low
        val high60 = recent.maxOf { it.high }
        val low60 = recent.minOf { it.low }
        val equilibrium = (high60 + low60) / 2.0
        val currentZone = when {
            currentPrice > equilibrium -> "PREMIUM"
            currentPrice < equilibrium -> "DISCOUNT"
            else -> "EQUILIBRIUM"
        }

        val swings = getSwings(recent)
        val swingHighs = swings.first
        val swingLows = swings.second
        val nearestSupport = swingLows.map { it.low }.filter { it < currentPrice }.maxOrNull() ?: low20
        val nearestResistance = swingHighs.map { it.high }.filter { it > currentPrice }.minOrNull() ?: high20
        val rawBias = calculateBias(recent)
        val choppy = isChoppy(recent)
        val atr = calculateAtr(recent).takeIf { it > 0.0 } ?: ((high60 - low60) / 10.0).coerceAtLeast(1.0)
        val structure = buildStructureState(recent, swingHighs, swingLows, rawBias, nearestSupport, nearestResistance, choppy)
        val fvg = findLatestFvg(recent)
        val ob = findOrderBlock(recent)

        val bias = when {
            rawBias == "bearish" || (structure["momentum"] == "bearish" && currentPrice < equilibrium) -> "BEARISH"
            rawBias == "bullish" || (structure["momentum"] == "bullish" && currentPrice > equilibrium) -> "BULLISH"
            else -> "NEUTRAL"
        }

        val hasSweep = structure["liquidity"]?.startsWith("No fresh") == false
        val hasDirectionalFvg = (bias == "BULLISH" && fvg.bullish != "-") || (bias == "BEARISH" && fvg.bearish != "-")
        val hasDirectionalOb = (bias == "BULLISH" && ob.bullish != "-") || (bias == "BEARISH" && ob.bearish != "-")
        val confidence = calculateConfidence(bias, choppy, hasSweep, hasDirectionalFvg, hasDirectionalOb)
        val setup = buildTradeSetup(bias, currentPrice, currentZone, nearestSupport, nearestResistance, high60, low60, atr, fvg, ob)

        val phase = structure["phase"] ?: "RANGING"
        val zoneText = when (currentZone) {
            "PREMIUM" -> "premium"
            "DISCOUNT" -> "diskon"
            else -> "equilibrium"
        }
        val summary = when (bias) {
            "BULLISH" -> "Market dalam fase $phase dengan bias bullish. Harga berada di zona $zoneText, resistance terdekat berada di ${formatPrice(nearestResistance)}, dan struktur masih mendukung skenario buy selektif."
            "BEARISH" -> "Market dalam fase $phase dengan bias bearish. Harga berada di zona $zoneText, support terdekat berada di ${formatPrice(nearestSupport)}, dan struktur masih menekan harga ke bawah."
            else -> "Market dalam fase $phase dengan bias netral. Harga berada di zona $zoneText dan belum ada konfirmasi struktur yang cukup kuat untuk entry agresif."
        }

        return JSONObject()
            .put("bias", bias)
            .put("confidence_score", confidence)
            .put("timeframe", timeframe)
            .put("session_context", session)
            .put("current_price", currentPrice)
            .put("daily_bias_summary", summary)
            .put("trade_setup", setup)
            .put("market_structure", JSONObject()
                .put("trend", when (bias) { "BULLISH" -> "Bullish"; "BEARISH" -> "Bearish"; else -> "Range" })
                .put("last_bos", structure["break"] ?: "None")
                .put("choch", if ((structure["break"] ?: "").contains("MSS")) structure["break"] else "Belum ada CHoCH/MSS baru")
                .put("swing_high", high20)
                .put("swing_low", low20)
                .put("liquidity", structure["liquidity"] ?: "No fresh liquidity sweep")
                .put("fvg", fvg.summary)
                .put("order_block", ob.summary)
                .put("premium_discount", "Harga berada di zona $currentZone dengan equilibrium ${formatPrice(equilibrium)}"))
            .put("order_blocks", JSONObject()
                .put("bullish_ob", ob.bullish)
                .put("bearish_ob", ob.bearish)
                .put("description", ob.summary))
            .put("fvg", JSONObject()
                .put("bullish_fvg", fvg.bullish)
                .put("bearish_fvg", fvg.bearish)
                .put("description", fvg.summary))
            .put("liquidity", JSONObject()
                .put("buy_side", formatPrice(nearestResistance))
                .put("sell_side", formatPrice(nearestSupport))
                .put("sweep_occurred", hasSweep)
                .put("description", structure["liquidity"] ?: "No fresh liquidity sweep"))
            .put("premium_discount", JSONObject()
                .put("equilibrium", equilibrium)
                .put("current_zone", currentZone)
                .put("ote_zone", when (bias) {
                    "BULLISH" -> "Cari discount/OTE di bawah equilibrium"
                    "BEARISH" -> "Cari premium/OTE di atas equilibrium"
                    else -> "Belum ada OTE valid"
                }))
            .put("key_notes", JSONArray()
                .put(if (hasSweep) "Liquidity sweep sudah terdeteksi oleh rule engine." else "Belum ada fresh liquidity sweep pada candle terakhir.")
                .put("Harga analisa dipaksa dari local live price/candle, bukan dari AI.")
                .put(if (notes.isNotBlank()) "Catatan trader terbaca: $notes" else "Tidak ada catatan tambahan dari trader."))
            .put("warnings", JSONArray().apply {
                if (choppy) put("Market choppy; hindari entry agresif tanpa retest jelas.")
                if (setup.optString("status") == "wait") put("Setup masih WAIT; tunggu konfirmasi tambahan sebelum entry.")
                if (abs(currentPrice - equilibrium) < atr * 0.25) put("Harga dekat equilibrium; area ini rawan noise.")
            })
            .toString()
    }

    private fun buildTradeSetup(
        bias: String,
        currentPrice: Double,
        currentZone: String,
        nearestSupport: Double,
        nearestResistance: Double,
        high60: Double,
        low60: Double,
        atr: Double,
        fvg: FvgInfo,
        ob: ObInfo
    ): JSONObject {
        return when (bias) {
            "BULLISH" -> {
                val entryZone = when {
                    fvg.bullish != "-" -> fvg.bullish
                    ob.bullish != "-" -> ob.bullish
                    else -> "${formatPrice(nearestSupport)} - ${formatPrice(currentPrice)}"
                }
                val valid = currentZone == "DISCOUNT" && (fvg.bullish != "-" || ob.bullish != "-")
                val sl = minOf(nearestSupport, low60) - atr
                JSONObject()
                    .put("status", if (valid) "valid" else "wait")
                    .put("entry_zone", entryZone)
                    .put("tp1", nearestResistance)
                    .put("tp2", high60)
                    .put("stop_loss", sl)
                    .put("risk_reward", "Minimal 1:2 jika entry dekat zona")
                    .put("invalidation", "Close kuat di bawah ${formatPrice(sl)}")
            }
            "BEARISH" -> {
                val entryZone = when {
                    fvg.bearish != "-" -> fvg.bearish
                    ob.bearish != "-" -> ob.bearish
                    else -> "${formatPrice(currentPrice)} - ${formatPrice(nearestResistance)}"
                }
                val valid = currentZone == "PREMIUM" && (fvg.bearish != "-" || ob.bearish != "-")
                val sl = maxOf(nearestResistance, high60) + atr
                JSONObject()
                    .put("status", if (valid) "valid" else "wait")
                    .put("entry_zone", entryZone)
                    .put("tp1", nearestSupport)
                    .put("tp2", low60)
                    .put("stop_loss", sl)
                    .put("risk_reward", "Minimal 1:2 jika entry dekat zona")
                    .put("invalidation", "Close kuat di atas ${formatPrice(sl)}")
            }
            else -> JSONObject()
                .put("status", "wait")
                .put("entry_zone", "Belum ada zona entry valid")
                .put("tp1", 0.0)
                .put("tp2", 0.0)
                .put("stop_loss", 0.0)
                .put("risk_reward", "-")
                .put("invalidation", "Tunggu BOS/CHoCH dan liquidity sweep")
        }
    }

    private fun calculateConfidence(bias: String, choppy: Boolean, hasSweep: Boolean, hasFvg: Boolean, hasOb: Boolean): Int {
        var score = if (bias == "NEUTRAL") 35 else 55
        if (hasSweep) score += 10
        if (hasFvg) score += 10
        if (hasOb) score += 10
        if (choppy) score -= 15
        return score.coerceIn(25, 85)
    }

    private fun getSwings(candles: List<CandleEntity>, left: Int = 2, right: Int = 2): Pair<List<CandleEntity>, List<CandleEntity>> {
        if (candles.size < left + right + 1) return Pair(emptyList(), emptyList())
        val highs = mutableListOf<CandleEntity>()
        val lows = mutableListOf<CandleEntity>()
        for (i in left until candles.size - right) {
            val high = candles[i].high
            val low = candles[i].low
            val isHigh = (1..left).all { candles[i - it].high < high } && (1..right).all { candles[i + it].high < high }
            val isLow = (1..left).all { candles[i - it].low > low } && (1..right).all { candles[i + it].low > low }
            if (isHigh) highs.add(candles[i])
            if (isLow) lows.add(candles[i])
        }
        return Pair(highs, lows)
    }

    private fun calculateBias(candles: List<CandleEntity>): String {
        if (candles.size < 10) return "neutral"
        val (highs, lows) = getSwings(candles)
        if (highs.size >= 2 && lows.size >= 2) {
            val hh = highs.last().high > highs[highs.size - 2].high
            val hl = lows.last().low > lows[lows.size - 2].low
            val lh = highs.last().high < highs[highs.size - 2].high
            val ll = lows.last().low < lows[lows.size - 2].low
            if (hh && hl) return "bullish"
            if (lh && ll) return "bearish"
            if (hh && ll) return "expanding"
            if (lh && hl) return "choppy"
        }
        val closes = candles.takeLast(5).map { it.close }
        return if (candles.last().close > closes.average()) "bullish" else "bearish"
    }

    private fun isChoppy(candles: List<CandleEntity>): Boolean {
        if (candles.size < 5) return false
        return candles.takeLast(5).count { candle ->
            val body = abs(candle.open - candle.close)
            val range = candle.high - candle.low
            range > 0 && body / range < 0.3
        } >= 3
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
        val body = abs(latest.open - latest.close)
        val range = latest.high - latest.low
        val momentum = if (range > 0 && body / range >= 0.5) {
            if (latest.close > latest.open) "bullish" else "bearish"
        } else "neutral"

        var liquidity = "No fresh liquidity sweep on latest closed candle"
        swingLows.lastOrNull()?.let {
            if (latest.low < it.low && latest.close > it.low) liquidity = "Sell-side liquidity swept at ${formatPrice(it.low)}, reclaimed at ${formatPrice(latest.close)}"
        }
        swingHighs.lastOrNull()?.let {
            if (liquidity.startsWith("No fresh") && latest.high > it.high && latest.close < it.high) liquidity = "Buy-side liquidity swept at ${formatPrice(it.high)}, reclaimed at ${formatPrice(latest.close)}"
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
        } else false

        val phase = when {
            choppy -> "CHOPPY"
            breakType != "None" -> "EXPANSION"
            middleOfRange -> "RANGING"
            bias == "bullish" -> "PULLBACK_OR_MARKUP"
            bias == "bearish" -> "PULLBACK_OR_MARKDOWN"
            else -> "RANGING"
        }

        val retest = if (breakType != "None" && breakLevel != null) {
            val distance = abs(latest.close - breakLevel)
            if (distance > 3.0) "WAIT_PULLBACK_TO_${formatPrice(breakLevel)}" else "ACTIVE_RETEST_$breakType"
        } else "NONE"

        return mapOf(
            "phase" to phase,
            "break" to if (breakLevel != null) "$breakType at ${formatPrice(breakLevel)}" else breakType,
            "retest" to retest,
            "momentum" to momentum,
            "liquidity" to liquidity
        )
    }

    private fun findLatestFvg(candles: List<CandleEntity>): FvgInfo {
        if (candles.size < 3) return FvgInfo("-", "-", "Belum cukup candle untuk FVG", "none")
        for (i in candles.size - 1 downTo 2) {
            val left = candles[i - 2]
            val right = candles[i]
            if (left.high < right.low) {
                val area = "${formatPrice(left.high)} - ${formatPrice(right.low)}"
                return FvgInfo(area, "-", "Bullish FVG $area", "bullish")
            }
            if (left.low > right.high) {
                val area = "${formatPrice(right.high)} - ${formatPrice(left.low)}"
                return FvgInfo("-", area, "Bearish FVG $area", "bearish")
            }
        }
        return FvgInfo("-", "-", "No clear FVG in recent candles", "none")
    }

    private fun findOrderBlock(candles: List<CandleEntity>): ObInfo {
        val recent = candles.takeLast(20)
        val bullishOb = recent.lastOrNull { it.close < it.open }
        val bearishOb = recent.lastOrNull { it.close > it.open }
        val bullish = bullishOb?.let { "${formatPrice(it.low)} - ${formatPrice(it.high)}" } ?: "-"
        val bearish = bearishOb?.let { "${formatPrice(it.low)} - ${formatPrice(it.high)}" } ?: "-"
        val summary = when {
            bullish != "-" && bearish != "-" -> "Bullish OB $bullish | Bearish OB $bearish"
            bullish != "-" -> "Bullish OB $bullish"
            bearish != "-" -> "Bearish OB $bearish"
            else -> "Order block reference belum ditemukan"
        }
        return ObInfo(bullish, bearish, summary)
    }

    private fun formatPrice(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }
}
