package com.frit.app.features.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class MarketCard(val label: String, val value: String, val delta: String, val isPositive: Boolean = true)

object MarketEngine {
    // Forex-first strip as requested; XAUUSD = Gold
    private val SYMBOLS = listOf("EURUSD", "GBPUSD", "XAUUSD", "BTC", "USDNGN")
    private var cachedCards = SYMBOLS.map { MarketCard(it, "Loading...", "0%") }

    fun cards(): List<MarketCard> = cachedCards

    suspend fun refresh(baseUrl: String) = withContext(Dispatchers.IO) {
        try {
            val payload = """{"symbols":${SYMBOLS.joinToString(",", "[", "]") { "\"$it\"" }}}"""
            val conn = (URL("${baseUrl.trimEnd('/')}/market/batch").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }

            if (code !in 200..299) return@withContext

            val json = JSONObject(body)
            val newCards = SYMBOLS.map { sym ->
                val obj = json.optJSONObject(sym)
                if (obj != null && !obj.has("error")) {
                    val price = obj.optDouble("price", 0.0)
                    val delta = obj.optDouble("change24h", 0.0)
                    val currency = obj.optString("currency", "USD")
                    val isForex = sym.length == 6 && sym.all { it.isLetter() }
                    val prefix = if (currency == "NGN") "₦" else if (isForex) "" else "$"
                    val formattedPrice = when {
                        isForex && sym != "XAUUSD" -> "${prefix}${String.format("%.5f", price)}"
                        sym == "XAUUSD" -> "${prefix}${String.format("%.2f", price)}"
                        price > 1000 -> "${prefix}${String.format("%,.0f", price)}"
                        else -> "${prefix}${String.format("%.4f", price)}"
                    }
                    MarketCard(
                        label = sym,
                        value = formattedPrice,
                        delta = "${if (delta >= 0) "+" else ""}${String.format("%.2f", delta)}%",
                        isPositive = delta >= 0
                    )
                } else {
                    MarketCard(sym, "N/A", "0%")
                }
            }
            cachedCards = newCards
        } catch (e: Exception) {
            // Keep cached data on error
        }
    }
}
