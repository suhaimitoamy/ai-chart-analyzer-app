package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class DeepSeekClient(private val apiKey: String) {
    suspend fun analyzeChart(prompt: String): String = withContext(Dispatchers.Default) {
        compileLocalAnalysis(prompt)
    }

    private fun compileLocalAnalysis(prompt: String): String {
        val timeframe = readText(prompt, "Requested timeframe") ?: "M1"
        val session = readText(prompt, "Session") ?: "-"
        val price = readNumber(prompt, "Current price") ?: 0.0
        val rawBias = (readText(prompt, "Market bias") ?: "neutral").lowercase(Locale.US)
        val phase = readText(prompt, "Market phase") ?: "RANGING"
        val momentum = (readText(prompt, "Momentum") ?: "neutral").lowercase(Locale.US)
        val resistance = readNumber(prompt, "Nearest resistance") ?: readNumber(prompt, "Last 20 swing high") ?: price
        val support = readNumber(prompt, "Nearest support") ?: readNumber(prompt, "Last 20 swing low") ?: price
        val high60 = readNumber(prompt, "60 candle range high") ?: resistance
        val low60 = readNumber(prompt, "60 candle range low") ?: support
        val equilibrium = readNumber(prompt, "Equilibrium") ?: ((high60 + low60) / 2.0)
        val currentZone = when {
            price > equilibrium -> "PREMIUM"
            price < equilibrium -> "DISCOUNT"
            else -> "EQUILIBRIUM"
        }
        val liquidity = readText(prompt, "Liquidity") ?: "No fresh liquidity sweep"
        val fvgText = readText(prompt, "FVG") ?: "No clear FVG"
        val obText = readText(prompt, "Order block reference") ?: "Order block reference belum ditemukan"
        val atr = readNumber(prompt, "ATR-like range")?.coerceAtLeast(1.0) ?: 1.0
        val choppy = (readText(prompt, "Choppy") ?: "false").equals("true", true)

        val bias = when {
            rawBias == "bearish" || (momentum == "bearish" && price < equilibrium) -> "BEARISH"
            rawBias == "bullish" || (momentum == "bullish" && price > equilibrium) -> "BULLISH"
            else -> "NEUTRAL"
        }

        val bullishFvg = if (fvgText.contains("Bullish", true)) areaFromText(fvgText) else "-"
        val bearishFvg = if (fvgText.contains("Bearish", true)) areaFromText(fvgText) else "-"
        val bullishOb = if (obText.contains("Bullish OB", true)) areaAfterLabel(obText, "Bullish OB ref") else "-"
        val bearishOb = if (obText.contains("Bearish OB", true)) areaAfterLabel(obText, "Bearish OB ref") else "-"
        val hasSweep = !liquidity.startsWith("No fresh", true)
        val hasFvg = (bias == "BULLISH" && bullishFvg != "-") || (bias == "BEARISH" && bearishFvg != "-")
        val hasOb = (bias == "BULLISH" && bullishOb != "-") || (bias == "BEARISH" && bearishOb != "-")
        val confidence = confidence(bias, choppy, hasSweep, hasFvg, hasOb)
        val setup = tradeSetup(bias, currentZone, price, support, resistance, high60, low60, atr, bullishFvg, bearishFvg, bullishOb, bearishOb)
        val zoneText = when (currentZone) {
            "PREMIUM" -> "premium"
            "DISCOUNT" -> "diskon"
            else -> "equilibrium"
        }
        val summary = when (bias) {
            "BULLISH" -> "Market dalam fase $phase dengan bias bullish. Harga berada di zona $zoneText, resistance terdekat berada di ${fmt(resistance)}, dan struktur masih mendukung skenario buy selektif."
            "BEARISH" -> "Market dalam fase $phase dengan bias bearish. Harga berada di zona $zoneText, support terdekat berada di ${fmt(support)}, dan struktur masih menekan harga ke bawah."
            else -> "Market dalam fase $phase dengan bias netral. Harga berada di zona $zoneText dan belum ada konfirmasi struktur yang cukup kuat untuk entry agresif."
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
                .put("last_bos", readText(prompt, "Break / MSS") ?: "None")
                .put("choch", readText(prompt, "Break / MSS") ?: "Belum ada CHoCH/MSS baru")
                .put("swing_high", resistance)
                .put("swing_low", support)
                .put("liquidity", liquidity)
                .put("fvg", fvgText)
                .put("order_block", obText)
                .put("premium_discount", "Harga berada di zona $currentZone dengan equilibrium ${fmt(equilibrium)}"))
            .put("order_blocks", JSONObject()
                .put("bullish_ob", bullishOb)
                .put("bearish_ob", bearishOb)
                .put("description", obText))
            .put("fvg", JSONObject()
                .put("bullish_fvg", bullishFvg)
                .put("bearish_fvg", bearishFvg)
                .put("description", fvgText))
            .put("liquidity", JSONObject()
                .put("buy_side", fmt(resistance))
                .put("sell_side", fmt(support))
                .put("sweep_occurred", hasSweep)
                .put("description", liquidity))
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
                .put("Analisis dibuat oleh local rule engine, bukan DeepSeek API."))
            .put("warnings", JSONArray().apply {
                if (choppy) put("Market choppy; tunggu retest yang jelas.")
                if (setup.optString("status") == "wait") put("Setup masih WAIT; tunggu konfirmasi tambahan.")
                if (abs(price - equilibrium) < atr * 0.25) put("Harga dekat equilibrium; area ini rawan noise.")
            })
            .toString()
    }

    private fun tradeSetup(
        bias: String,
        zone: String,
        price: Double,
        support: Double,
        resistance: Double,
        high60: Double,
        low60: Double,
        atr: Double,
        bullishFvg: String,
        bearishFvg: String,
        bullishOb: String,
        bearishOb: String
    ): JSONObject {
        return when (bias) {
            "BULLISH" -> {
                val entry = when {
                    bullishFvg != "-" -> bullishFvg
                    bullishOb != "-" -> bullishOb
                    else -> "${fmt(support)} - ${fmt(price)}"
                }
                val valid = zone == "DISCOUNT" && (bullishFvg != "-" || bullishOb != "-")
                val sl = minOf(support, low60) - atr
                JSONObject().put("status", if (valid) "valid" else "wait")
                    .put("entry_zone", entry).put("tp1", resistance).put("tp2", high60)
                    .put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona")
                    .put("invalidation", "Close kuat di bawah ${fmt(sl)}")
            }
            "BEARISH" -> {
                val entry = when {
                    bearishFvg != "-" -> bearishFvg
                    bearishOb != "-" -> bearishOb
                    else -> "${fmt(price)} - ${fmt(resistance)}"
                }
                val valid = zone == "PREMIUM" && (bearishFvg != "-" || bearishOb != "-")
                val sl = maxOf(resistance, high60) + atr
                JSONObject().put("status", if (valid) "valid" else "wait")
                    .put("entry_zone", entry).put("tp1", support).put("tp2", low60)
                    .put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona")
                    .put("invalidation", "Close kuat di atas ${fmt(sl)}")
            }
            else -> JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid")
                .put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0)
                .put("risk_reward", "-").put("invalidation", "Tunggu BOS/CHoCH dan liquidity sweep")
        }
    }

    private fun confidence(bias: String, choppy: Boolean, sweep: Boolean, fvg: Boolean, ob: Boolean): Int {
        var score = if (bias == "NEUTRAL") 35 else 55
        if (sweep) score += 10
        if (fvg) score += 10
        if (ob) score += 10
        if (choppy) score -= 15
        return score.coerceIn(25, 85)
    }

    private fun readText(prompt: String, label: String): String? {
        val match = Regex("$label:\\s*([^\\n]+)").find(prompt) ?: return null
        return match.groupValues[1].trim().takeIf { it != "-" }
    }

    private fun readNumber(prompt: String, label: String): Double? {
        return readText(prompt, label)?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }
    }

    private fun areaFromText(text: String): String {
        val nums = Regex("-?\\d+(\\.\\d+)?").findAll(text).map { it.value }.toList()
        return if (nums.size >= 2) "${nums[0]} - ${nums[1]}" else "-"
    }

    private fun areaAfterLabel(text: String, label: String): String {
        val idx = text.indexOf(label, ignoreCase = true)
        if (idx < 0) return "-"
        return areaFromText(text.substring(idx))
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}
