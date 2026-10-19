package com.frit.app.features.trading.repository

import com.frit.app.core.Diag
import com.frit.app.features.trading.model.*
import com.frit.app.features.trading.service.TradingApiException
import com.frit.app.features.trading.service.TradingCache
import com.frit.app.features.trading.service.TradingEngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TradingRepository — single data layer for the trading suite.
 *
 * Strategy: network-first, cache-fallback. Every successful network fetch is
 * mirrored into TradingCache so the UI stays fully usable offline. Offline
 * favorite/delete ops are queued in the cache and replayed on next sync.
 */
object TradingRepository {

    sealed class Result<out T> {
        data class Ok<T>(val value: T, val fromCache: Boolean = false) : Result<T>()
        data class Err(val message: String) : Result<Nothing>()
    }

    // ---------------- Watchlist ----------------
    suspend fun watchlist(): Result<List<WatchlistItem>> = withContext(Dispatchers.IO) {
        try {
            val items = TradingEngineClient.getWatchlist()
            TradingCache.saveWatchlist(items)
            replayOps()
            Result.Ok(items)
        } catch (e: Exception) {
            val cached = TradingCache.loadWatchlist()
            if (cached.isNotEmpty()) Result.Ok(cached, fromCache = true)
            else Result.Err(e.friendly())
        }
    }

    suspend fun addWatch(symbol: String, assetClass: AssetClass = guessClass(symbol)): Result<WatchlistItem> =
        withContext(Dispatchers.IO) {
            val sym = symbol.uppercase()
            try {
                val item = TradingEngineClient.addWatch(sym, sym, assetClass)
                // A fresh add cancels any stale queued remove for this pair —
                // otherwise the next sync replay would delete it again.
                TradingCache.removeOps { it.optString("op") == "remove_watch" && it.optString("symbol") == sym }
                val cur = TradingCache.loadWatchlist().toMutableList()
                if (cur.none { it.symbol == sym }) cur += item
                TradingCache.saveWatchlist(cur)
                Result.Ok(item)
            } catch (e: Exception) {
                // Offline: keep it locally and queue the add for replay so the
                // pair survives restarts instead of silently vanishing.
                TradingCache.queueOp(JSONObject().put("op", "add_watch").put("symbol", sym)
                    .put("name", sym).put("assetClass", assetClass.name))
                val local = WatchlistItem(symbol = sym, name = sym, assetClass = assetClass)
                val cur = TradingCache.loadWatchlist().toMutableList()
                if (cur.none { it.symbol == sym }) cur += local
                TradingCache.saveWatchlist(cur)
                Result.Ok(local)
            }
        }

    suspend fun removeWatch(symbol: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        try {
            TradingEngineClient.removeWatch(sym)
            TradingCache.removeOps { it.optString("symbol") == sym }
            TradingCache.saveWatchlist(TradingCache.loadWatchlist().filterNot { it.symbol == sym })
            Result.Ok(Unit)
        } catch (e: Exception) {
            // Removal is the newest intent: drop any queued re-add first.
            TradingCache.removeOps { it.optString("op") == "add_watch" && it.optString("symbol") == sym }
            TradingCache.saveWatchlist(TradingCache.loadWatchlist().filterNot { it.symbol == sym })
            TradingCache.queueOp(JSONObject().put("op", "remove_watch").put("symbol", sym))
            Result.Ok(Unit)
        }
    }

    suspend fun setWatchEnabled(symbol: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            TradingEngineClient.setWatchEnabled(symbol, enabled)
            TradingCache.saveWatchlist(TradingCache.loadWatchlist().map {
                if (it.symbol == symbol.uppercase()) it.copy(isEnabled = enabled) else it
            })
            Result.Ok(Unit)
        } catch (e: Exception) { Result.Err(e.friendly()) }
    }

    // ---------------- Scan + signals ----------------
    suspend fun scan(symbols: List<String>? = null): Result<ScanResult> = withContext(Dispatchers.IO) {
        try {
            val r = TradingEngineClient.scan(symbols)
            // merge fresh signals into cache (keep max 200)
            val merged = (r.signals + TradingCache.loadSignals())
                .distinctBy { it.id }.sortedByDescending { it.timestamp }.take(200)
            TradingCache.saveSignals(merged)
            Result.Ok(r)
        } catch (e: Exception) {
            Result.Err(e.friendly())
        }
    }

    suspend fun signals(symbol: String? = null, favoritesOnly: Boolean = false): Result<List<TradingSignal>> =
        withContext(Dispatchers.IO) {
            try {
                val list = TradingEngineClient.signalHistory(symbol, 100, favoritesOnly)
                if (symbol == null && !favoritesOnly) {
                    val merged = (list + TradingCache.loadSignals())
                        .distinctBy { it.id }.sortedByDescending { it.timestamp }.take(200)
                    TradingCache.saveSignals(merged)
                }
                Result.Ok(list)
            } catch (e: Exception) {
                var cached = TradingCache.loadSignals()
                if (symbol != null) cached = cached.filter { it.symbol == symbol.uppercase() }
                if (favoritesOnly) cached = cached.filter { it.isFavorite }
                if (cached.isNotEmpty()) Result.Ok(cached, fromCache = true)
                else Result.Err(e.friendly())
            }
        }

    suspend fun setFavorite(id: String, fav: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        // optimistic local update
        TradingCache.saveSignals(TradingCache.loadSignals().map {
            if (it.id == id) it.copy(isFavorite = fav) else it
        })
        try {
            TradingEngineClient.setFavorite(id, fav)
            TradingCache.removeOps { it.optString("op") == "favorite" && it.optString("id") == id }
            Result.Ok(Unit)
        } catch (e: Exception) {
            TradingCache.queueOp(JSONObject().put("op", "favorite").put("id", id).put("favorite", fav))
            Result.Ok(Unit) // applied locally, will replay
        }
    }

    suspend fun deleteSignal(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        TradingCache.saveSignals(TradingCache.loadSignals().filterNot { it.id == id })
        try {
            TradingEngineClient.deleteSignal(id)
            TradingCache.removeOps { it.optString("id") == id }
            Result.Ok(Unit)
        } catch (e: Exception) {
            TradingCache.queueOp(JSONObject().put("op", "delete_signal").put("id", id))
            Result.Ok(Unit)
        }
    }

    suspend fun markRead(id: String) {
        TradingCache.saveSignals(TradingCache.loadSignals().map {
            if (it.id == id) it.copy(isRead = true) else it
        })
        // Local state is already updated; the server-side read flag is not —
        // log the miss instead of swallowing it (audit F-16).
        try { TradingEngineClient.markRead(id) } catch (e: Exception) { Diag.warn("Trading", "markRead", e) }
    }

    // ---------------- Portfolio + risk ----------------
    suspend fun portfolio(balance: Double = 1000.0): Result<Pair<PortfolioSummary, List<PortfolioPosition>>> =
        withContext(Dispatchers.IO) {
            try {
                val r = TradingEngineClient.getPortfolio(balance)
                TradingCache.savePortfolio(r.first, r.second)
                Result.Ok(r)
            } catch (e: Exception) {
                val c = TradingCache.loadPortfolio()
                if (c != null) Result.Ok(c, fromCache = true) else Result.Err(e.friendly())
            }
        }

    suspend fun risk(balance: Double = 1000.0): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val r = TradingEngineClient.getRisk(balance)
            TradingCache.putJson("risk", r.toString())
            Result.Ok(r)
        } catch (e: Exception) {
            val c = runCatching { JSONObject(TradingCache.getJson("risk") ?: "") }.getOrNull()
            if (c != null) Result.Ok(c, fromCache = true) else Result.Err(e.friendly())
        }
    }

    // ---------------- Sentiment ----------------
    suspend fun sentiment(symbols: List<String>?): Result<Map<String, SentimentData>> =
        withContext(Dispatchers.IO) {
            try {
                val m = TradingEngineClient.getSentiment(symbols)
                // mirror to cache (server already caches daily; this is the offline copy)
                val obj = JSONObject()
                m.forEach { (k, v) -> obj.put(k, JSONObject()
                    .put("sentiment", v.sentiment.name).put("score", v.score)
                    .put("bullishSignals", v.bullishSignals).put("bearishSignals", v.bearishSignals)
                    .put("summary", v.summary).put("cachedAt", v.cachedAt)) }
                TradingCache.putJson("sentiment", obj.toString())
                Result.Ok(m)
            } catch (e: Exception) {
                val raw = TradingCache.getJson("sentiment")
                if (raw != null) {
                    val m = mutableMapOf<String, SentimentData>()
                    runCatching { JSONObject(raw) }.getOrNull()?.let { o ->
                        for (k in o.keys()) {
                            val s = o.optJSONObject(k) ?: continue
                            m[k] = SentimentData(k,
                                runCatching { Sentiment.valueOf(s.optString("sentiment", "NEUTRAL")) }.getOrDefault(Sentiment.NEUTRAL),
                                s.optInt("score", 50), s.optInt("bullishSignals", 0), s.optInt("bearishSignals", 0),
                                s.optString("summary", ""), s.optLong("cachedAt", 0))
                        }
                    }
                    if (m.isNotEmpty()) return@withContext Result.Ok(m, fromCache = true)
                }
                Result.Err(e.friendly())
            }
        }

    // ---------------- AI ----------------
    suspend fun explain(signalId: String) = withContext(Dispatchers.IO) {
        try { Result.Ok(TradingEngineClient.explain(signalId)) }
        catch (e: Exception) { Result.Err(e.friendly()) }
    }

    suspend fun recommendations(profile: String, capital: Double) = withContext(Dispatchers.IO) {
        try { Result.Ok(TradingEngineClient.recommendations(profile, capital)) }
        catch (e: Exception) { Result.Err(e.friendly()) }
    }

    suspend fun learning(category: String? = null): Result<List<LearningContent>> = withContext(Dispatchers.IO) {
        try {
            val items = TradingEngineClient.learning(category)
            if (category == null) {
                val arr = org.json.JSONArray()
                items.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title)
                    .put("category", it.category).put("readTime", it.readTime).put("content", it.content)) }
                TradingCache.putJson("learning", arr.toString())
            }
            Result.Ok(items)
        } catch (e: Exception) {
            if (category == null) {
                val out = mutableListOf<LearningContent>()
                runCatching { org.json.JSONArray(TradingCache.getJson("learning") ?: "[]") }.getOrNull()?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        out += LearningContent(o.optString("id"), o.optString("title"), o.optString("category"),
                            o.optInt("readTime", 5), o.optString("content"))
                    }
                }
                if (out.isNotEmpty()) return@withContext Result.Ok(out, fromCache = true)
            }
            Result.Err(e.friendly())
        }
    }

    // ---------------- Options suite (signal-only) ----------------
    suspend fun optionsScan(symbols: List<String>? = null): Result<List<OptionsSignal>> =
        withContext(Dispatchers.IO) {
            try {
                Result.Ok(TradingEngineClient.optionsScan(symbols ?: listOf("SPY", "QQQ")))
            } catch (e: Exception) { Result.Err(e.friendly()) }
        }

    suspend fun optionsFxScan(symbols: List<String>? = null): Result<List<OptionsSignal>> =
        withContext(Dispatchers.IO) {
            try {
                Result.Ok(TradingEngineClient.optionsFxScan(symbols ?: listOf("EURUSD", "GBPUSD", "XAUUSD")))
            } catch (e: Exception) { Result.Err(e.friendly()) }
        }

    // ---------------- Trading bots ----------------
    suspend fun bots(): Result<List<BotConfig>> = withContext(Dispatchers.IO) {
        try {
            val list = TradingEngineClient.getBots()
            val arr = org.json.JSONArray()
            list.forEach {
                arr.put(JSONObject().put("name", it.name).put("label", it.label)
                    .put("pair", it.pair).put("timeframe", it.timeframe).put("auto", it.auto)
                    .put("minConfidence", it.minConfidence).put("maxTradesDay", it.maxTradesDay)
                    .put("executes", it.executes).put("blurb", it.blurb)
                    .put("allowedTimeframes", org.json.JSONArray(it.allowedTimeframes)))
            }
            TradingCache.putJson("bots", arr.toString())
            Result.Ok(list)
        } catch (e: Exception) {
            val cached = runCatching {
                val arr = org.json.JSONArray(TradingCache.getJson("bots") ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val tfs = mutableListOf<String>()
                    o.optJSONArray("allowedTimeframes")?.let { t -> for (j in 0 until t.length()) tfs += t.optString(j) }
                    BotConfig(o.optString("name"), o.optString("label"), o.optString("pair"),
                        o.optString("timeframe"), o.optBoolean("auto"), o.optInt("minConfidence", 60),
                        o.optInt("maxTradesDay", 2), o.optBoolean("executes"), o.optString("blurb"), tfs)
                }
            }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) Result.Ok(cached, fromCache = true) else Result.Err(e.friendly())
        }
    }

    suspend fun saveBot(name: String, pair: String, timeframe: String, auto: Boolean): Result<BotConfig> =
        withContext(Dispatchers.IO) {
            try {
                val b = TradingEngineClient.saveBot(name, pair, timeframe, auto)
                bots() // refresh cache
                Result.Ok(b)
            } catch (e: Exception) { Result.Err(e.friendly()) }
        }

    suspend fun botRuns(): Result<List<BotRun>> = withContext(Dispatchers.IO) {
        try { Result.Ok(TradingEngineClient.botRuns()) }
        catch (e: Exception) { Result.Err(e.friendly()) }
    }

    // ---------------- Bot-Pair Association ----------------
    suspend fun botPairs(pair: String? = null): Result<List<BotPairConfig>> = withContext(Dispatchers.IO) {
        try {
            Result.Ok(TradingEngineClient.getBotPairs(pair))
        } catch (e: Exception) { Result.Err(e.friendly()) }
    }

    suspend fun saveBotPair(botName: String, pair: String, timeframe: String,
                           tradeEnabled: Boolean, signalEnabled: Boolean,
                           minConfidence: Int, maxTradesDay: Int): Result<BotPairConfig> =
        withContext(Dispatchers.IO) {
            try {
                Result.Ok(TradingEngineClient.saveBotPair(botName, pair, timeframe, tradeEnabled, signalEnabled, minConfidence, maxTradesDay))
            } catch (e: Exception) { Result.Err(e.friendly()) }
        }

    suspend fun removeBotPair(botName: String, pair: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            TradingEngineClient.removeBotPair(botName, pair)
            Result.Ok(Unit)
        } catch (e: Exception) { Result.Err(e.friendly()) }
    }

    // ---------------- Notifications ----------------
    suspend fun notifications(limit: Int = 50): Result<List<BotNotification>> = withContext(Dispatchers.IO) {
        try {
            Result.Ok(TradingEngineClient.getNotifications(limit))
        } catch (e: Exception) { Result.Err(e.friendly()) }
    }

    // ---------------- Sync (replay offline ops) ----------------
    private suspend fun replayOps() {
        val ops = TradingCache.drainOps()
        for (op in ops) {
            try {
                when (op.optString("op")) {
                    "favorite" -> TradingEngineClient.setFavorite(op.optString("id"), op.optBoolean("favorite"))
                    "delete_signal" -> TradingEngineClient.deleteSignal(op.optString("id"))
                    "remove_watch" -> TradingEngineClient.removeWatch(op.optString("symbol"))
                    "add_watch" -> {
                        val item = TradingEngineClient.addWatch(
                            op.optString("symbol"),
                            op.optString("name", op.optString("symbol")),
                            runCatching { AssetClass.valueOf(op.optString("assetClass", "FOREX")) }.getOrDefault(AssetClass.FOREX)
                        )
                        val cur = TradingCache.loadWatchlist().toMutableList()
                        if (cur.none { it.symbol == item.symbol }) cur += item
                        TradingCache.saveWatchlist(cur)
                    }
                }
            } catch (e: Exception) {
                // 404/409 = already gone / already there: converge, don't re-queue forever.
                val settled = (e as? TradingApiException)?.code == 404 || (e as? TradingApiException)?.code == 409
                if (!settled) TradingCache.queueOp(op) // keep for next time
            }
        }
    }

    // ---------------- helpers ----------------
    private fun Exception.friendly(): String = when (this) {
        is TradingApiException -> message ?: "server error"
        else -> message ?: "network error"
    }

    fun guessClass(symbol: String): AssetClass {
        val s = symbol.uppercase()
        return when {
            s.endsWith("USD") && (s.startsWith("BTC") || s.startsWith("ETH") || s.startsWith("SOL") || s.startsWith("BNB") || s.startsWith("XRP") || s.startsWith("DOGE") || s.startsWith("ADA")) -> AssetClass.CRYPTO
            s.contains("XAU") || s.contains("XAG") || s.contains("OIL") || s.contains("WTI") -> AssetClass.COMMODITIES
            s.length == 6 && s.all { it.isLetter() } -> AssetClass.FOREX
            else -> AssetClass.FOREX
        }
    }
}
