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
        val promptPrice = readNumber(prompt, "Current price") ?: 0.0
        val price = MarketPriceCache.latestPrice ?: promptPrice
        val rawBias = (readText(prompt, "Market bias") ?: "neutral").lowercase(Locale.US)
        val phase = readText(prompt, "Market phase") ?: "RANGING"
        val momentum = (readText(prompt, "Momentum") ?: "neutral").lowercase(Locale.US)
        val high60 = readNumber(prompt, "60 candle range high") ?: price
        val low60 = readNumber(prompt, "60 candle range low") ?: price
        val equilibrium = readNumber(prompt, "Equilibrium") ?: ((high60 + low60) / 2.0)
        val atr = readNumber(prompt, "ATR-like range")?.coerceAtLeast(0.50) ?: ((high60 - low60) / 10.0).coerceAtLeast(1.0)
        val maxDistance = when (timeframe.uppercase(Locale.US)) {
            "M1" -> atr * 8.0
            "M5" -> atr * 10.0
            "M15" -> atr * 12.0
            "M30" -> atr * 14.0
            else -> atr * 18.0
        }.coerceAtLeast(8.0)

        val rawResistance = readNumber(prompt, "Nearest resistance") ?: readNumber(prompt, "Last 20 swing high") ?: high60
        val rawSupport = readNumber(prompt, "Nearest support") ?: readNumber(prompt, "Last 20 swing low") ?: low60
        val resistance = clampResistance(rawResistance, price, high60, maxDistance)
        val support = clampSupport(rawSupport, price, low60, maxDistance)
        val currentZone = when {
            price > equilibrium -> "PREMIUM"
            price < equilibrium -> "DISCOUNT"
            else -> "EQUILIBRIUM"
        }

        val liquidity = readText(prompt, "Liquidity") ?: "No fresh liquidity sweep"
        val fvgText = readText(prompt, "FVG") ?: "No clear FVG"
        val obText = readText(prompt, "Order block reference") ?: "Order block reference belum ditemukan"
        val choppy = (readText(prompt, "Choppy") ?: "false").equals("true", true)

        val bias = when {
            rawBias == "bearish" && price < resistance -> "BEARISH"
            rawBias == "bullish" && price > support -> "BULLISH"
            momentum == "bearish" && price < equilibrium -> "BEARISH"
            momentum == "bullish" && price > equilibrium -> "BULLISH"
            else -> "NEUTRAL"
        }

        val bullishFvg = validArea(fvgText, "Bullish", price, maxDistance)
        val bearishFvg = validArea(fvgText, "Bearish", price, maxDistance)
        val bullishOb = validArea(obText, "Bullish OB", price, maxDistance)
        val bearishOb = validArea(obText, "Bearish OB", price, maxDistance)
        val hasSweep = !liquidity.startsWith("No fresh", true)
        val hasFvg = (bias == "BULLISH" && bullishFvg != "-") || (bias == "BEARISH" && bearishFvg != "-")
        val hasOb = (bias == "BULLISH" && bullishOb != "-") || (bias == "BEARISH" && bearishOb != "-")
        val setup = tradeSetup(bias, currentZone, price, support, resistance, high60, low60, atr, bullishFvg, bearishFvg, bullishOb, bearishOb)
        val confidence = confidence(bias, choppy, hasSweep, hasFvg, hasOb, setup.optString("status") == "valid")
        val zoneText = when (currentZone) {
            "PREMIUM" -> "premium"
            "DISCOUNT" -> "diskon"
            else -> "equilibrium"
        }
        val summary = when (bias) {
            "BULLISH" -> "Market dalam fase $phase dengan bias bullish. Harga live berada di zona $zoneText, support aktif berada di ${fmt(support)}, resistance terdekat berada di ${fmt(resistance)}, dan skenario buy hanya valid setelah retest."
            "BEARISH" -> "Market dalam fase $phase dengan bias bearish. Harga live berada di zona $zoneText, resistance aktif berada di ${fmt(resistance)}, support terdekat berada di ${fmt(support)}, dan skenario sell hanya valid setelah retest."
            else -> "Market dalam fase $phase dengan bias netral. Harga live berada di zona $zoneText dan belum ada konfirmasi struktur yang cukup kuat untuk entry agresif."
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
                .put("fvg", if (bullishFvg != "-" || bearishFvg != "-") fvgText else "Tidak ada FVG aktif dekat harga")
                .put("order_block", if (bullishOb != "-" || bearishOb != "-") obText else "Tidak ada OB aktif dekat harga")
                .put("premium_discount", "Harga live berada di zona $currentZone dengan equilibrium ${fmt(equilibrium)}"))
            .put("order_blocks", JSONObject()
                .put("bullish_ob", bullishOb)
                .put("bearish_ob", bearishOb)
                .put("description", if (bullishOb != "-" || bearishOb != "-") obText else "OB lama atau terlalu jauh dari harga, diabaikan"))
            .put("fvg", JSONObject()
                .put("bullish_fvg", bullishFvg)
                .put("bearish_fvg", bearishFvg)
                .put("description", if (bullishFvg != "-" || bearishFvg != "-") fvgText else "FVG lama atau terlalu jauh dari harga, diabaikan"))
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
                .put("Current price memakai cache live tick TwelveData jika tersedia.")
                .put("Level jauh dari harga aktif otomatis diabaikan agar tidak ngaco.")
                .put("Analisis dibuat oleh local rule engine, bukan DeepSeek API."))
            .put("warnings", JSONArray().apply {
                if (MarketPriceCache.latestPrice == null) put("Live tick belum masuk; current price memakai candle terakhir dari snapshot.")
                if (choppy) put("Market choppy; tunggu retest yang jelas.")
                if (setup.optString("status") == "wait") put("Setup masih WAIT; tunggu konfirmasi tambahan.")
                if (abs(price - equilibrium) < atr * 0.25) put("Harga dekat equilibrium; area ini rawan noise.")
            })
            .toString()
    }

    private fun tradeSetup(bias: String, zone: String, price: Double, support: Double, resistance: Double, high60: Double, low60: Double, atr: Double, bullishFvg: String, bearishFvg: String, bullishOb: String, bearishOb: String): JSONObject {
        return when (bias) {
            "BULLISH" -> {
                val entry = when { bullishFvg != "-" -> bullishFvg; bullishOb != "-" -> bullishOb; else -> "${fmt(support)} - ${fmt(price)}" }
                val valid = zone == "DISCOUNT" && (bullishFvg != "-" || bullishOb != "-") && support < price
                val sl = minOf(support, low60) - atr
                JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", entry).put("tp1", resistance).put("tp2", high60).put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona").put("invalidation", "Close kuat di bawah ${fmt(sl)}")
            }
            "BEARISH" -> {
                val entry = when { bearishFvg != "-" -> bearishFvg; bearishOb != "-" -> bearishOb; else -> "${fmt(price)} - ${fmt(resistance)}" }
                val valid = zone == "PREMIUM" && (bearishFvg != "-" || bearishOb != "-") && resistance > price
                val sl = maxOf(resistance, high60) + atr
                JSONObject().put("status", if (valid) "valid" else "wait").put("entry_zone", entry).put("tp1", support).put("tp2", low60).put("stop_loss", sl).put("risk_reward", "Minimal 1:2 jika entry dekat zona").put("invalidation", "Close kuat di atas ${fmt(sl)}")
            }
            else -> JSONObject().put("status", "wait").put("entry_zone", "Belum ada zona entry valid").put("tp1", 0.0).put("tp2", 0.0).put("stop_loss", 0.0).put("risk_reward", "-").put("invalidation", "Tunggu BOS/CHoCH dan liquidity sweep")
        }
    }

    private fun confidence(bias: String, choppy: Boolean, sweep: Boolean, fvg: Boolean, ob: Boolean, valid: Boolean): Int {
        var score = if (bias == "NEUTRAL") 35 else 50
        if (sweep) score += 10
        if (fvg) score += 10
        if (ob) score += 10
        if (valid) score += 5
        if (choppy) score -= 15
        return score.coerceIn(25, 85)
    }

    private fun validArea(text: String, label: String, price: Double, maxDistance: Double): String {
        if (!text.contains(label, true)) return "-"
        val area = areaFromText(text)
        val nums = Regex("-?\\d+(\\.\\d+)?").findAll(area).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (nums.size < 2) return "-"
        val mid = (nums[0] + nums[1]) / 2.0
        return if (abs(mid - price) <= maxDistance) area else "-"
    }

    private fun clampResistance(raw: Double, price: Double, high60: Double, maxDistance: Double): Double {
        val fallback = if (high60 > price && abs(high60 - price) <= maxDistance * 1.5) high60 else price + maxDistance
        return if (raw > price && abs(raw - price) <= maxDistance * 1.5) raw else fallback
    }

    private fun clampSupport(raw: Double, price: Double, low60: Double, maxDistance: Double): Double {
        val fallback = if (low60 < price && abs(price - low60) <= maxDistance * 1.5) low60 else price - maxDistance
        return if (raw < price && abs(raw - price) <= maxDistance * 1.5) raw else fallback
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

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}
