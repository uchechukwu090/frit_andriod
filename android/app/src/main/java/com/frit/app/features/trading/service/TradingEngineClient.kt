package com.frit.app.features.trading.service

import android.util.Log
import com.frit.app.core.FritConfig
import com.frit.app.core.TokenStore
import com.frit.app.features.trading.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TradingEngineClient — HTTP client for the FRIT Trading Suite API.
 *
 * Talks to the server-side MTFStrategyEngine (30M primary + 4H confirmation,
 * trend / pullback-Fib / range-fade dual engines) via the /api/trading endpoints. Uses the
 * same auth/base-URL mechanism as NetworkClient (TokenStore + baked BuildConfig).
 * All calls are suspend + IO-dispatched; failures throw TradingApiException so
 * the repository can fall back to the offline cache.
 */
class TradingApiException(message: String, val code: Int = -1) : Exception(message)

object TradingEngineClient {
    private const val TAG = "TradingEngineClient"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun resolveBaseUrl(appPrefs: android.content.SharedPreferences? = null): String {
        val override = try { appPrefs?.getString("server_url", "")?.trim() ?: "" } catch (_: Exception) { "" }
        val raw = if (override.isNotBlank()) override else FritConfig.BASE_URL
        val t = raw.trim().trimEnd('/')
        return if (t.startsWith("http")) t else "https://$t"
    }

    private var prefsProvider: (() -> android.content.SharedPreferences?)? = null
    fun init(prefs: () -> android.content.SharedPreferences?) { prefsProvider = prefs }

    // Same identity rules as NetworkClient: the per-install key first (that is the
    // identity the subscription / owner redeem is bound to), master token only as
    // bootstrap. Previously this client sent the master token with NO user_id, so
    // the server resolved every trading/pocket call to the shared "default" free
    // account — an owner/pro/tester redeem on this device never applied here.
    private fun masterToken(): String = TokenStore.get().ifBlank { FritConfig.AUTH_TOKEN }
    private fun auth(): String {
        val k = try { TokenStore.installKey() } catch (_: Exception) { "" }
        return k.ifBlank { masterToken() }
    }
    private fun withUid(path: String): String {
        val uid = try { TokenStore.installId() } catch (_: Exception) { "" }
        if (uid.isBlank()) return path
        val sep = if ("?" in path) "&" else "?"
        return "$path${sep}user_id=${java.net.URLEncoder.encode(uid, "UTF-8")}"
    }

    private fun build(path: String, token: String, method: String, body: JSONObject?): Request {
        val url = resolveBaseUrl(prefsProvider?.invoke()) + withUid(path)
        val b = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
        val rb = (body ?: JSONObject()).toString().toRequestBody(JSON)
        return when (method) {
            "GET" -> b.get().build()
            "POST" -> b.post(rb).build()
            "DELETE" -> b.delete().build()
            else -> b.patch(rb).build()
        }
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        try {
            execute(build(path, auth(), method, body))
        } catch (e: TradingApiException) {
            // Stale install key (server redeploy wipes api_keys): retry once with the
            // master token; user_id in the query keeps the same account binding.
            val master = masterToken()
            if (e.code == 401 && master.isNotBlank() && master != auth()) execute(build(path, master, method, body)) else throw e
        }
    }

    private suspend fun get(path: String): JSONObject = call("GET", path)
    private suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call("POST", path, body)
    private suspend fun delete(path: String): JSONObject = call("DELETE", path)
    private suspend fun patch(path: String, body: JSONObject): JSONObject = call("PATCH", path, body)

    private fun execute(req: Request): JSONObject {
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) throw TradingApiException("HTTP ${resp.code}: ${text.take(300)}", resp.code)
                return JSONObject(text)
            }
        } catch (e: TradingApiException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "network failure", e)
            throw TradingApiException(e.message ?: "network error")
        }
    }

    // ---------------- Watchlist ----------------
    suspend fun getWatchlist(): List<WatchlistItem> {
        val res = get("/api/trading/watchlist")
        return res.optJSONArray("items")?.toWatchlist() ?: emptyList()
    }

    suspend fun addWatch(symbol: String, name: String = symbol, assetClass: AssetClass = AssetClass.FOREX): WatchlistItem {
        val res = post("/api/trading/watchlist", JSONObject()
            .put("symbol", symbol.uppercase()).put("name", name).put("assetClass", assetClass.name))
        val o = res.optJSONObject("item") ?: throw TradingApiException("add failed")
        return o.toWatchlistItem()
    }

    suspend fun removeWatch(symbol: String) { delete("/api/trading/watchlist/${symbol.uppercase()}") }

    suspend fun setWatchEnabled(symbol: String, enabled: Boolean) {
        patch("/api/trading/watchlist/${symbol.uppercase()}", JSONObject().put("isEnabled", enabled))
    }

    // ---------------- Scan + signals ----------------
    suspend fun scan(symbols: List<String>? = null, forceRefresh: Boolean = false): ScanResult {
        val body = JSONObject().put("forceRefresh", forceRefresh)
        if (symbols != null) body.put("symbols", JSONArray(symbols))
        val res = post("/api/trading/scan", body)
        val list = res.optJSONArray("signals")?.toSignals() ?: emptyList()
        return ScanResult(list, res.optLong("scannedAt", System.currentTimeMillis()))
    }

    suspend fun signalHistory(symbol: String? = null, limit: Int = 50, favoritesOnly: Boolean = false): List<TradingSignal> {
        var path = "/api/trading/signals/history?limit=$limit"
        if (symbol != null) path += "&symbol=${symbol.uppercase()}"
        if (favoritesOnly) path += "&favoritesOnly=true"
        val res = get(path)
        return res.optJSONArray("signals")?.toSignals() ?: emptyList()
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        post("/api/trading/signals/$id/favorite", JSONObject().put("favorite", favorite))
    }

    suspend fun markRead(id: String) { post("/api/trading/signals/$id/read", JSONObject()) }
    suspend fun deleteSignal(id: String) { delete("/api/trading/signals/$id") }

    // ---------------- Portfolio + risk ----------------
    suspend fun getPortfolio(balance: Double = 1000.0): Pair<PortfolioSummary, List<PortfolioPosition>> {
        val res = get("/api/trading/portfolio?balance=$balance")
        val s = res.optJSONObject("summary")
        val summary = PortfolioSummary(
            totalPositions = s?.optInt("totalPositions", 0) ?: 0,
            unrealizedPnl = s?.optDouble("unrealizedPnl", 0.0) ?: 0.0,
            realizedPnl = s?.optDouble("realizedPnl", 0.0) ?: 0.0,
            totalPnl = s?.optDouble("totalPnl", 0.0) ?: 0.0,
            balance = s?.optDouble("balance", balance) ?: balance,
            equity = s?.optDouble("equity", balance) ?: balance
        )
        val positions = res.optJSONArray("positions")?.toPositions() ?: emptyList()
        return summary to positions
    }

    suspend fun getRisk(balance: Double = 1000.0): JSONObject = get("/api/trading/risk?balance=$balance")

    // ---------------- Sentiment (cached daily server-side) ----------------
    suspend fun getSentiment(symbols: List<String>? = null): Map<String, SentimentData> {
        var path = "/api/trading/sentiment"
        if (!symbols.isNullOrEmpty()) path += "?symbols=" + symbols.joinToString(",") { it.uppercase() }
        val res = get(path)
        val map = mutableMapOf<String, SentimentData>()
        val obj = res.optJSONObject("sentiment") ?: return map
        for (k in obj.keys()) {
            val s = obj.optJSONObject(k) ?: continue
            map[k] = SentimentData(
                symbol = k,
                sentiment = runCatching { Sentiment.valueOf(s.optString("sentiment", "NEUTRAL")) }.getOrDefault(Sentiment.NEUTRAL),
                score = s.optInt("score", 50),
                bullishSignals = s.optInt("bullishSignals", 0),
                bearishSignals = s.optInt("bearishSignals", 0),
                summary = s.optString("summary", ""),
                cachedAt = s.optLong("cachedAt", System.currentTimeMillis())
            )
        }
        return map
    }

    // ---------------- AI: explain + recommendations + learning ----------------
    suspend fun explain(signalId: String, detailLevel: String = "full"): Pair<String, List<ExplanationFactor>> {
        val res = post("/api/trading/ai/explain", JSONObject().put("signalId", signalId).put("detailLevel", detailLevel))
        val factors = mutableListOf<ExplanationFactor>()
        res.optJSONArray("factors")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                factors += ExplanationFactor(
                    name = f.optString("name"),
                    value = f.optString("value"),
                    impact = runCatching { FactorImpact.valueOf(f.optString("impact", "MEDIUM")) }.getOrDefault(FactorImpact.MEDIUM),
                    description = f.optString("description")
                )
            }
        }
        return (res.optString("plainEnglish", "")) to factors
    }

    suspend fun recommendations(riskProfile: String = "MODERATE", capital: Double = 10000.0): List<AIRecommendation> {
        val res = post("/api/trading/ai/recommendations",
            JSONObject().put("riskProfile", riskProfile).put("capital", capital))
        val out = mutableListOf<AIRecommendation>()
        res.optJSONArray("recommendations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += AIRecommendation(
                    symbol = o.optString("symbol"),
                    action = runCatching { SignalDecision.valueOf(o.optString("action", "WAIT")) }.getOrDefault(SignalDecision.WAIT),
                    confidence = o.optInt("confidence", 0),
                    entry = o.optDouble("entry", Double.NaN),
                    sl = o.optDouble("sl", Double.NaN),
                    tp1 = o.optDouble("tp1", Double.NaN),
                    tp2 = o.optDouble("tp2", Double.NaN),
                    rr = o.optDouble("rr", 0.0),
                    reasoning = o.optString("reasoning", ""),
                    regime = o.optString("regime", null),
                    positionSize = o.optDouble("positionSize", 0.0)
                )
            }
        }
        return out
    }

    suspend fun learning(category: String? = null): List<LearningContent> {
        val res = get(if (category != null) "/api/trading/learning?category=$category" else "/api/trading/learning")
        val out = mutableListOf<LearningContent>()
        val content = res.optJSONObject("content")
        fun pull(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += LearningContent(o.optString("id"), o.optString("title"), o.optString("category"),
                    o.optInt("readTime", 5), o.optString("content"))
            }
        }
        if (content != null) {
            pull(content.optJSONArray("items"))
            content.optJSONObject("all")?.let { all ->
                for (k in all.keys()) pull(all.optJSONArray(k))
            }
        }
        return out
    }

    // ---------------- Notifications + sync + alerts ----------------
    suspend fun registerPushToken(token: String, platform: String = "android") {
        post("/api/trading/notifications/register", JSONObject().put("token", token).put("platform", platform))
    }

    suspend fun saveNotifPrefs(p: NotificationPrefs) {
        post("/api/trading/notifications/preferences", JSONObject()
            .put("signalAlerts", p.signalAlerts).put("tpSlAlerts", p.tpSlAlerts)
            .put("newsAlerts", p.newsAlerts).put("dailySummary", p.dailySummary))
    }

    suspend fun checkAlerts(signals: List<TradingSignal>): List<AlertHit> {
        val arr = JSONArray()
        signals.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("symbol", s.symbol)
                .put("entry", s.entry).put("sl", s.sl).put("tp1", s.tp1).put("tp2", s.tp2))
        }
        val res = post("/api/trading/alerts/check", JSONObject().put("signals", arr))
        val out = mutableListOf<AlertHit>()
        res.optJSONArray("alerts")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out += AlertHit(o.optString("symbol"), o.optString("kind"),
                    o.optDouble("price", 0.0), o.optString("signalId", null))
            }
        }
        return out
    }

    suspend fun syncSince(since: Long): JSONObject = get("/api/trading/sync?since=$since")

    // ---------------- Pocket fixed-expiry signals (manual execution) ----------------
    suspend fun pocketSignal(symbol: String, expiry: Int = 5): JSONObject =
        get("/api/trading/pocket/signal?symbol=${symbol.uppercase()}&expiry=$expiry")

    // Pocket watchlist is SEPARATE from the main trading watchlist.
    suspend fun pocketWatchlist(): JSONObject = get("/api/trading/pocket/watchlist")

    suspend fun addPocketWatch(symbol: String, expiry: Int = 5): JSONObject =
        post("/api/trading/pocket/watchlist",
            JSONObject().put("symbol", symbol.uppercase()).put("expiry", expiry))

    suspend fun removePocketWatch(symbol: String): JSONObject =
        delete("/api/trading/pocket/watchlist/${symbol.uppercase()}")

    suspend fun derivStatus(): JSONObject = get("/api/trading/deriv/status")

    suspend fun derivTest(symbol: String, direction: String, stake: Double, durationMin: Int): JSONObject =
        post("/api/trading/deriv/test", JSONObject()
            .put("symbol", symbol.uppercase()).put("direction", direction)
            .put("stake", stake).put("duration", durationMin))

    suspend fun pocketHistory(symbol: String? = null, limit: Int = 50): JSONObject {
        var path = "/api/trading/pocket/history?limit=$limit"
        if (symbol != null) path += "&symbol=${symbol.uppercase()}"
        return get(path)
    }

    suspend fun pocketStats(symbol: String? = null): JSONObject {
        var path = "/api/trading/pocket/stats"
        if (symbol != null) path += "?symbol=${symbol.uppercase()}"
        return get(path)
    }

    /** Pocket bot ON/OFF switch (server-side, per user). */
    suspend fun pocketEnabled(): Boolean = get("/api/trading/pocket/toggle").optBoolean("enabled", true)
    suspend fun setPocketEnabled(on: Boolean): Boolean =
        post("/api/trading/pocket/toggle", JSONObject().put("enabled", on)).optBoolean("enabled", on)

    suspend fun feedStatus(): JSONObject = get("/api/trading/feed/status")

    // ---------------- Unified paper portfolio (every bot, bot-tagged) ----------------
    suspend fun paperPortfolio(): JSONObject = get("/api/trading/paper-portfolio")

    // ---------------- Bots registry (server monitors saved+enabled bots) ----------------
    suspend fun getBots(): List<BotConfig> {
        val res = get("/api/trading/bots")
        val defs = res.optJSONObject("defs") ?: JSONObject()
        val out = mutableListOf<BotConfig>()
        res.optJSONArray("bots")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val d = defs.optJSONObject(name)
                val tfs = mutableListOf<String>()
                d?.optJSONArray("timeframes")?.let { t ->
                    for (j in 0 until t.length()) tfs += t.optString(j)
                }
                out += BotConfig(
                    name = name,
                    label = o.optString("label", name),
                    pair = o.optString("pair", ""),
                    timeframe = o.optString("timeframe", ""),
                    auto = o.optBoolean("auto_enabled", false),
                    minConfidence = o.optInt("min_confidence", 60),
                    maxTradesDay = o.optInt("max_trades_day", 2),
                    executes = o.optBoolean("executes", d?.optBoolean("executes", false) ?: false),
                    blurb = o.optString("blurb", ""),
                    allowedTimeframes = tfs.ifEmpty { listOf(o.optString("timeframe", "")) },
                    lastRun = ""
                )
            }
        }
        return out
    }

    suspend fun saveBot(name: String, pair: String, timeframe: String, auto: Boolean, minConfidence: Int = 60, maxTradesDay: Int = 2): BotConfig {
        val res = post("/api/trading/bots/$name", JSONObject()
            .put("pair", pair.uppercase()).put("timeframe", timeframe)
            .put("auto_enabled", auto).put("min_confidence", minConfidence).put("max_trades_day", maxTradesDay))
        val o = res.optJSONObject("bot") ?: throw TradingApiException("save failed")
        return getBots().firstOrNull { it.name == name }
            ?: BotConfig(name, o.optString("label", name), o.optString("pair", pair),
                o.optString("timeframe", timeframe), o.optBoolean("auto_enabled", auto),
                o.optInt("min_confidence", minConfidence), 2, false, "", listOf(timeframe))
    }

    suspend fun botRuns(limit: Int = 30): List<BotRun> {
        val res = get("/api/trading/bots/runs?limit=$limit")
        val out = mutableListOf<BotRun>()
        res.optJSONArray("runs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += BotRun(
                    bot = o.optString("bot"), pair = o.optString("pair", ""),
                    decision = o.optString("decision", ""), confidence = o.optInt("confidence", 0),
                    note = o.optString("note", ""), firedAt = o.optLong("fired_at", 0)
                )
            }
        }
        return out
    }

    // ---------------- Bot-Pair Association ----------------
    suspend fun getBotPairs(pair: String? = null): List<BotPairConfig> {
        val path = if (pair != null) "/api/trading/bot-pairs?pair=${pair.uppercase()}" else "/api/trading/bot-pairs"
        val res = get(path)
        val out = mutableListOf<BotPairConfig>()
        res.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += BotPairConfig(
                    id = o.optLong("id", 0),
                    userId = o.optString("userId", ""),
                    botName = o.optString("botName", ""),
                    pair = o.optString("pair", ""),
                    timeframe = o.optString("timeframe", ""),
                    tradeEnabled = o.optBoolean("tradeEnabled", false),
                    signalEnabled = o.optBoolean("signalEnabled", true),
                    minConfidence = o.optInt("minConfidence", 60),
                    maxTradesDay = o.optInt("maxTradesDay", 2)
                )
            }
        }
        return out
    }

    suspend fun saveBotPair(botName: String, pair: String, timeframe: String,
                           tradeEnabled: Boolean, signalEnabled: Boolean,
                           minConfidence: Int, maxTradesDay: Int): BotPairConfig {
        val res = post("/api/trading/bot-pairs", JSONObject()
            .put("botName", botName).put("pair", pair.uppercase())
            .put("timeframe", timeframe).put("tradeEnabled", tradeEnabled)
            .put("signalEnabled", signalEnabled).put("minConfidence", minConfidence)
            .put("maxTradesDay", maxTradesDay))
        val o = res.optJSONObject("item") ?: throw TradingApiException("save failed")
        return BotPairConfig(
            id = o.optLong("id", 0), userId = o.optString("userId", ""),
            botName = o.optString("botName", ""), pair = o.optString("pair", ""),
            timeframe = o.optString("timeframe", ""), tradeEnabled = o.optBoolean("tradeEnabled", false),
            signalEnabled = o.optBoolean("signalEnabled", true),
            minConfidence = o.optInt("minConfidence", 60), maxTradesDay = o.optInt("maxTradesDay", 2)
        )
    }

    suspend fun removeBotPair(botName: String, pair: String) {
        delete("/api/trading/bot-pairs/${botName}/${pair.uppercase()}")
    }

    // ---------------- Notifications ----------------
    suspend fun getNotifications(limit: Int = 50): List<BotNotification> {
        val res = get("/api/trading/notifications?limit=$limit")
        val out = mutableListOf<BotNotification>()
        res.optJSONArray("notifications")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += BotNotification(
                    id = o.optString("id", ""),
                    bot = o.optString("bot", ""),
                    type = o.optString("type", ""),
                    symbol = o.optString("symbol", ""),
                    decision = o.optString("decision", ""),
                    confidence = o.optInt("confidence", 0),
                    note = o.optString("note", ""),
                    timestamp = o.optLong("timestamp", 0),
                    read = o.optBoolean("read", false)
                )
            }
        }
        return out
    }

    // ---------------- Options suite (signal-only, never executes) ----------------
    suspend fun optionsScan(symbols: List<String> = listOf("SPY", "QQQ")): List<OptionsSignal> {
        val res = post("/options/scan", JSONObject().put("symbols", JSONArray(symbols)))
        return res.optJSONArray("signals")?.toOptionsSignals() ?: emptyList()
    }

    suspend fun optionsFxScan(symbols: List<String> = listOf("EURUSD", "GBPUSD", "XAUUSD")): List<OptionsSignal> {
        val res = post("/options/fx-scan", JSONObject().put("symbols", JSONArray(symbols)))
        return res.optJSONArray("signals")?.toOptionsSignals() ?: emptyList()
    }

    private fun JSONArray.toOptionsSignals(): List<OptionsSignal> {
        val out = mutableListOf<OptionsSignal>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out += o.toOptionsSignal()
        }
        return out
    }

    private fun JSONObject.toOptionsSignal(): OptionsSignal {
        val kind = optString("kind", optString("decision", "SIGNAL"))
        val symbol = optString("symbol", "")
        val synth = optBoolean("synthetic", false)
        val prov = optJSONObject("dataSource")?.optBoolean("provisional", false) ?: false
        val title: String
        val text: String
        if (optJSONArray("band1d") != null || kind == "FX_EXPECTED_MOVE") {
            // FX model levels
            val lo = optJSONArray("band1d")?.optDouble(0, Double.NaN) ?: Double.NaN
            val hi = optJSONArray("band1d")?.optDouble(1, Double.NaN) ?: Double.NaN
            val regime = optString("regime", "")
            title = "$symbol expected move ${fmtOpt(lo)}–${fmtOpt(hi)}"
            text = listOf(
                "1-day band ${fmtOpt(lo)} / ${fmtOpt(hi)}",
                if (regime.isNotBlank()) "regime $regime" else null,
                optString("tactic", "").takeIf { it.isNotBlank() },
                if (synth) "model estimate, not a market quote" else null
            ).filterNotNull().joinToString(" · ")
        } else {
            // Equity credit spread / condor
            val credit = optDouble("credit", Double.NaN)
            val risk = optDouble("maxLossDollars", optDouble("maxLoss", Double.NaN))
            val bes = optJSONArray("breakevens")
            val be = if (bes != null) (0 until bes.length()).map { fmtOpt(bes.optDouble(it, Double.NaN)) }.joinToString("/") else "—"
            val dte = optInt("dte", -1)
            val ivr = optDouble("ivRank", Double.NaN)
            title = "SELL PREMIUM $symbol — $kind"
            text = listOf(
                "credit ${mny(credit)} · risk ${mny(risk)}",
                "BE $be",
                if (dte >= 0) "$dte DTE" else null,
                if (ivr.isFinite()) "IV rank ${ivr.toInt()}" else null,
                "exit 50% / 21d / 2x stop",
                if (prov) "delayed unofficial quotes" else null
            ).filterNotNull().joinToString(" · ")
        }
        return OptionsSignal(
            id = optString("id", "${symbol}_${System.currentTimeMillis()}"),
            symbol = symbol, kind = kind, title = title, text = text,
            timestamp = optLong("timestamp", System.currentTimeMillis()),
            synthetic = synth, provisional = prov
        )
    }

    private fun fmtOpt(v: Double): String = if (v.isNaN()) "—" else {
        val s = if (v >= 100) "%,.2f" else "%,.5f"
        s.format(v).trimEnd('0').trimEnd('.')
    }

    private fun mny(v: Double): String = if (v.isNaN()) "—" else "$" + "%,.2f".format(v)

    // ---------------- JSON mapping ----------------
    private fun JSONArray.toWatchlist(): List<WatchlistItem> {
        val out = mutableListOf<WatchlistItem>()
        for (i in 0 until length()) out += optJSONObject(i)?.toWatchlistItem() ?: continue
        return out
    }

    private fun JSONObject.toWatchlistItem(): WatchlistItem = WatchlistItem(
        id = optLong("id", 0),
        symbol = optString("symbol"),
        name = optString("name", optString("symbol")),
        assetClass = runCatching { AssetClass.valueOf(optString("asset_class", optString("assetClass", "FOREX"))) }.getOrDefault(AssetClass.FOREX),
        isEnabled = optInt("is_enabled", 1) == 1,
        sortOrder = optInt("sort_order", 0)
    )

    private fun JSONArray.toSignals(): List<TradingSignal> {
        val out = mutableListOf<TradingSignal>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out += o.toSignal()
        }
        return out
    }

    fun JSONObject.toSignal(): TradingSignal {
        fun obj(key: String): JSONObject? = when (val v = opt(key)) {
            is JSONObject -> v
            is String -> runCatching { JSONObject(v) }.getOrNull()
            else -> null
        }
        val ph = obj("pullbackHealth") ?: obj("pullback_health")
        val ri = obj("rangeInfo") ?: obj("range_info")
        val co = obj("costs")
        return TradingSignal(
            id = optString("id"),
            symbol = optString("symbol"),
            decision = runCatching { SignalDecision.valueOf(optString("decision", "WAIT")) }.getOrDefault(SignalDecision.WAIT),
            confidence = optInt("confidence", 0),
            entry = optDouble("entry", Double.NaN),
            sl = optDouble("sl", Double.NaN),
            tp1 = optDouble("tp1", Double.NaN),
            tp2 = optDouble("tp2", Double.NaN),
            rr = optDouble("rr", 0.0),
            scenario = runCatching { SignalScenario.valueOf(optString("scenario", "WAIT")) }.getOrDefault(SignalScenario.WAIT),
            reasoning = optString("reasoning", ""),
            pullbackHealth = ph?.let {
                PullbackHealth(
                    status = it.optString("status", ""),
                    isHealthy = it.optBoolean("is_healthy", it.optBoolean("isHealthy", true)),
                    volumeOk = it.optBoolean("volume_ok", it.optBoolean("volumeOk", true)),
                    pullbackProbability = it.optDouble("pullback_probability", Double.NaN).takeIf { v -> v.isFinite() },
                    reversalProbability = it.optDouble("reversal_probability", Double.NaN).takeIf { v -> v.isFinite() },
                    adxPercentile = it.optDouble("adx_percentile_4h", Double.NaN).takeIf { v -> v.isFinite() },
                    note = it.optString("note", null)
                )
            },
            rangeInfo = ri?.let {
                RangeInfo(
                    upper = it.optDouble("upper", Double.NaN),
                    lower = it.optDouble("lower", Double.NaN),
                    mid = it.optDouble("mid", Double.NaN),
                    widthAtr = it.optDouble("width_atr", it.optDouble("widthAtr", Double.NaN)),
                    touchesUp = it.optInt("touchesUp", it.optInt("touches_ceiling", 0)),
                    touchesLo = it.optInt("touchesLo", it.optInt("touches_floor", 0)),
                    side = it.optString("side", ""),
                    fibConfluence = it.optBoolean("fib_confluence", it.optBoolean("fibConfluence", false))
                )
            },
            costs = co?.let {
                CostAnalysis(
                    costPrice = it.optDouble("costPrice", 0.0),
                    grossRisk = it.optDouble("grossRisk", 0.0),
                    grossWin = it.optDouble("grossWin", 0.0),
                    netRisk = it.optDouble("netRisk", 0.0),
                    netWin = it.optDouble("netWin", 0.0),
                    netRR = it.optDouble("netRR", 0.0),
                    costPctOfRisk = it.optDouble("costPctOfRisk", 0.0),
                    blocked = it.optBoolean("blocked", false),
                    weak = it.optBoolean("weak", false)
                )
            },
            timestamp = optLong("timestamp", System.currentTimeMillis()),
            isFavorite = optInt("is_favorite", if (optBoolean("isFavorite", false)) 1 else 0) == 1,
            isRead = optInt("is_read", if (optBoolean("isRead", false)) 1 else 0) == 1
        )
    }

    private fun JSONArray.toPositions(): List<PortfolioPosition> {
        val out = mutableListOf<PortfolioPosition>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out += PortfolioPosition(
                id = o.optString("id"),
                symbol = o.optString("symbol"),
                side = o.optString("side", ""),
                entryPrice = o.optDouble("entryPrice", 0.0),
                currentPrice = o.optDouble("currentPrice", 0.0),
                lotSize = o.optDouble("lotSize", 0.0),
                sl = o.optDouble("sl", 0.0),
                tp = o.optDouble("tp", 0.0),
                unrealizedPnl = o.optDouble("unrealizedPnl", 0.0),
                realizedPnl = o.optDouble("realizedPnl", 0.0),
                status = runCatching { PositionStatus.valueOf(o.optString("status", "OPEN")) }.getOrDefault(PositionStatus.OPEN),
                openedAt = o.optLong("openedAt", 0),
                closedAt = o.optLong("closedAt", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            )
        }
        return out
    }
}

data class AlertHit(val symbol: String, val kind: String, val price: Double, val signalId: String?)
