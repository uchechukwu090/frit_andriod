package com.frit.app.features.market

import com.frit.app.core.FritConfig
import com.frit.app.core.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MarketAnalysisEngine {

    data class TradePlan(
        val entry: String,
        val sl: String,
        val tp: String,
        val riskState: String
    )

    data class AuctionData(
        val poc: Double,
        val vah: Double,
        val val_: Double,
        val position: String,
        val bias: String,
        val note: String
    )

    data class Signal(
        val symbol: String,
        val direction: String,
        val strength: String,
        val rsi: Double,
        val macdSignal: String,
        val ema20: Double,
        val ema50: Double,
        val support: Double,
        val resistance: Double,
        val summary: String,
        val aiOpinion: String = "",
        val tradePlan: TradePlan? = null,
        val auction: AuctionData? = null,
        val confidence: Int = 0,
        val isNewsBlocked: Boolean = false
    )

    private var serverBaseUrl = com.frit.app.core.FritConfig.BASE_URL
    fun setBaseUrl(url: String) { serverBaseUrl = url.trimEnd('/') }

    /**
     * Server auth for /market/analyze (requireAuth — Week-3 audit finding: these
     * calls previously carried no Authorization header, so every one of them
     * got HTTP 401 and forex analysis silently degraded to the crypto-only
     * offline fallback / "NO DATA"). Same identity order as NetworkClient:
     * the per-install key first, master token as bootstrap.
     */
    private fun authHeader(): String {
        val k = try { com.frit.app.core.TokenStore.installKey() } catch (_: Exception) { "" }
        val token = k.ifBlank { try { com.frit.app.core.TokenStore.get() } catch (_: Exception) { "" }.ifBlank { FritConfig.AUTH_TOKEN } }
        return "Bearer $token"
    }

    suspend fun analyze(symbol: String, interval: String = "1h"): Signal = withContext(Dispatchers.IO) {
        fetchViaGet(symbol, interval)
            ?: fetchViaPost(symbol, interval)
            ?: fallbackAnalysis(symbol)
    }

    private fun fetchViaGet(symbol: String, interval: String): Signal? {
        return try {
            val s = URLEncoder.encode(symbol.uppercase(), "UTF-8")
            val iv = URLEncoder.encode(interval, "UTF-8")
            val conn = (URL("$serverBaseUrl/market/analyze?symbol=$s&interval=$iv").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Authorization", authHeader())
            }

            if (conn.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            parseSignal(JSONObject(body), symbol)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchViaPost(symbol: String, interval: String): Signal? {
        return try {
            val payload = JSONObject().apply {
                put("symbol", symbol.uppercase())
                put("interval", interval)
            }.toString()

            val conn = (URL("$serverBaseUrl/market/analyze").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", authHeader())
            }

            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            if (conn.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            parseSignal(JSONObject(body), symbol)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSignal(json: JSONObject, symbol: String): Signal {
        val tpJson = json.optJSONObject("trade_plan")
        val plan = if (tpJson != null) TradePlan(
            entry = tpJson.optString("entry_zone", "N/A"),
            sl = tpJson.optString("invalidation", "0"),
            tp = tpJson.optString("tp1", "0"),
            riskState = tpJson.optString("risk_state", "unknown")
        ) else null

        val aucJson = json.optJSONObject("auction")
        val auction = if (aucJson != null) AuctionData(
            poc = aucJson.optDouble("poc", 0.0),
            vah = aucJson.optDouble("vah", 0.0),
            val_ = aucJson.optDouble("val", 0.0),
            position = aucJson.optString("position", ""),
            bias = aucJson.optString("bias", ""),
            note = aucJson.optString("note", "")
        ) else null

        val nf = json.optJSONObject("news_filter")
        val blocked = nf?.optBoolean("blocked", false) ?: false

        return Signal(
            symbol = json.optString("symbol", symbol.uppercase()),
            direction = json.optString("direction", "NEUTRAL"),
            strength = json.optString("strength", "WEAK"),
            rsi = json.optDouble("rsi", 50.0),
            macdSignal = json.optString("macd_signal", "neutral"),
            ema20 = json.optDouble("ema20", 0.0),
            ema50 = json.optDouble("ema50", 0.0),
            support = json.optDouble("support", 0.0),
            resistance = json.optDouble("resistance", 0.0),
            summary = json.optString("summary", ""),
            aiOpinion = json.optString("ai_opinion", ""),
            tradePlan = plan,
            auction = auction,
            confidence = json.optInt("confidence", 0),
            isNewsBlocked = blocked || json.optString("strength") == "NEWS_BLACKOUT"
        )
    }

    private suspend fun fallbackAnalysis(symbol: String): Signal {
        val sym = symbol.uppercase()
        val isCrypto = listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA").contains(sym)
        if (!isCrypto) {
            return Signal(
                sym, "NO DATA", "WEAK", 50.0, "neutral",
                0.0, 0.0, 0.0, 0.0,
                "$sym: Server offline. Forex analysis requires server connection.",
                "Server unreachable — cannot analyze $sym without live data."
            )
        }

        val binanceSym = mapOf(
            "BTC" to "BTCUSDT", "ETH" to "ETHUSDT", "SOL" to "SOLUSDT",
            "BNB" to "BNBUSDT", "XRP" to "XRPUSDT", "DOGE" to "DOGEUSDT", "ADA" to "ADAUSDT"
        )
        val bSym = binanceSym[sym] ?: "${sym}USDT"

        return try {
            val url = URL("https://api.binance.com/api/v3/klines?symbol=$bSym&interval=1h&limit=60")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) return noDataSignal(sym)

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() < 30) return noDataSignal(sym)

            val closes = List(arr.length()) { i -> arr.getJSONArray(i).getDouble(4) }
            val price = closes.last()
            val rsiVal = rsi(closes)
            val (macdLine, macdSig, _) = macd(closes)
            val ema20 = ema(closes, 20).lastOrNull() ?: price
            val ema50 = ema(closes, 50).lastOrNull() ?: price
            val recent = List(minOf(20, arr.length())) { i ->
                val c = arr.getJSONArray(arr.length() - minOf(20, arr.length()) + i)
                Pair(c.getDouble(3), c.getDouble(2))
            }
            val support = recent.minOf { it.first }
            val resistance = recent.maxOf { it.second }

            var bull = 0
            var bear = 0
            if (rsiVal in 50.0..70.0) bull++ else if (rsiVal in 30.0..50.0) bear++
            if (macdLine > macdSig) bull++ else bear++
            if (price > ema20) bull++ else bear++
            if (ema20 > ema50) bull++ else bear++

            val direction = if (rsiVal >= 70) "OVERBOUGHT"
            else if (rsiVal <= 30) "OVERSOLD"
            else if (bull >= 3) "BULLISH"
            else if (bear >= 3) "BEARISH"
            else "NEUTRAL"

            val strength = if (bull == 4 || bear == 4) "STRONG"
            else if (bull >= 3 || bear >= 3) "MODERATE"
            else "WEAK"

            Signal(
                sym,
                direction,
                strength,
                rsiVal,
                if (macdLine > macdSig) "bullish" else "bearish",
                ema20,
                ema50,
                support,
                resistance,
                "$sym @${"%.2f".format(price)} | $direction($strength) RSI:${"%.1f".format(rsiVal)} S:${"%.2f".format(support)} R:${"%.2f".format(resistance)}"
            )
        } catch (_: Exception) {
            noDataSignal(sym)
        }
    }

    private fun noDataSignal(sym: String) = Signal(
        sym, "NO DATA", "WEAK", 50.0, "neutral",
        0.0, 0.0, 0.0, 0.0,
        "$sym: No data available", ""
    )

    private fun ema(closes: List<Double>, period: Int): List<Double> {
        if (closes.size < period) return emptyList()
        val k = 2.0 / (period + 1)
        val result = mutableListOf<Double>()
        var prev = closes.take(period).average()
        result.add(prev)
        for (i in period until closes.size) {
            prev = closes[i] * k + prev * (1 - k)
            result.add(prev)
        }
        return result
    }

    private fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size < period + 1) return 50.0
        val changes = closes.zipWithNext { a, b -> b - a }
        val gains = changes.takeLast(period).map { if (it > 0) it else 0.0 }
        val losses = changes.takeLast(period).map { if (it < 0) -it else 0.0 }
        val avgLoss = losses.average()
        if (avgLoss == 0.0) return 100.0
        return 100.0 - (100.0 / (1 + gains.average() / avgLoss))
    }

    private fun macd(closes: List<Double>): Triple<Double, Double, Double> {
        val e12 = ema(closes, 12)
        val e26 = ema(closes, 26)
        if (e12.isEmpty() || e26.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val line = e12.last() - e26.last()
        // takeLast aligns the shorter EMA to the tail: e12.takeLast(e26.size)[k]
        // is EMA12 at close index (11 + (e12.size-e26.size) + k) = 25+k, matching
        // e26[k] (EMA26 at 25+k). Verified algebra — do not "fix" this pairing.
        val hist = e12.takeLast(e26.size).zip(e26).map { (a, b) -> a - b }
        val sig = if (hist.size >= 9) ema(hist, 9).lastOrNull() ?: 0.0 else 0.0
        return Triple(line, sig, line - sig)
    }
}
