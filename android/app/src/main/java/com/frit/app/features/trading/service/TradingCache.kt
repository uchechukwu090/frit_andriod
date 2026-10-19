package com.frit.app.features.trading.service

import android.content.Context
import android.content.SharedPreferences
import com.frit.app.features.trading.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * TradingCache — offline-first persistence for the trading suite.
 *
 * Server is source of truth when online; every successful fetch is mirrored
 * here as JSON so Dashboard / Signals / Portfolio / Sentiment / Learning all
 * render from cache in offline mode (24h+ usable without sync). Signal
 * favorites/deletes made offline are queued and replayed by the repository.
 */
object TradingCache {
    private const val PREFS = "frit_trading_cache"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized)
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun getPrefs(): SharedPreferences = prefs

    // ---- generic JSON blobs ----
    fun putJson(key: String, value: String) { getPrefs().edit().putString(key, value).apply() }
    fun getJson(key: String): String? = try { getPrefs().getString(key, null) } catch (_: Exception) { null }
    fun putLong(key: String, v: Long) { getPrefs().edit().putLong(key, v).apply() }
    fun getLong(key: String, d: Long = 0): Long = try { getPrefs().getLong(key, d) } catch (_: Exception) { d }

    // ---- typed helpers ----
    fun saveWatchlist(items: List<WatchlistItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject()
            .put("symbol", it.symbol).put("name", it.name)
            .put("asset_class", it.assetClass.name)
            .put("is_enabled", if (it.isEnabled) 1 else 0)
            .put("sort_order", it.sortOrder)) }
        putJson("watchlist", arr.toString())
        putLong("watchlist_at", System.currentTimeMillis())
    }

    fun loadWatchlist(): List<WatchlistItem> {
        val out = mutableListOf<WatchlistItem>()
        val raw = getJson("watchlist") ?: return out
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += WatchlistItem(
                symbol = o.optString("symbol"), name = o.optString("name", o.optString("symbol")),
                assetClass = runCatching { AssetClass.valueOf(o.optString("asset_class", "FOREX")) }.getOrDefault(AssetClass.FOREX),
                isEnabled = o.optInt("is_enabled", 1) == 1,
                sortOrder = o.optInt("sort_order", 0)
            )
        }
        return out
    }

    fun saveSignals(signals: List<TradingSignal>) {
        val arr = JSONArray()
        signals.forEach { s -> arr.put(signalToJson(s)) }
        putJson("signals", arr.toString())
        putLong("signals_at", System.currentTimeMillis())
    }

    fun loadSignals(): List<TradingSignal> {
        val raw = getJson("signals") ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<TradingSignal>()
        for (i in 0 until arr.length())
            arr.optJSONObject(i)?.let { runCatching { out += it.toSignalKt() }.getOrNull() }
        return out
    }

    fun savePortfolio(summary: PortfolioSummary, positions: List<PortfolioPosition>) {
        putJson("portfolio_summary", JSONObject()
            .put("totalPositions", summary.totalPositions)
            .put("unrealizedPnl", summary.unrealizedPnl)
            .put("realizedPnl", summary.realizedPnl)
            .put("totalPnl", summary.totalPnl)
            .put("balance", summary.balance).put("equity", summary.equity).toString())
        val arr = JSONArray()
        positions.forEach { p -> arr.put(JSONObject()
            .put("id", p.id).put("symbol", p.symbol).put("side", p.side)
            .put("entryPrice", p.entryPrice).put("currentPrice", p.currentPrice)
            .put("lotSize", p.lotSize).put("sl", p.sl).put("tp", p.tp)
            .put("unrealizedPnl", p.unrealizedPnl).put("realizedPnl", p.realizedPnl)
            .put("status", p.status.name).put("openedAt", p.openedAt)
            .put("closedAt", p.closedAt ?: -1)) }
        putJson("portfolio_positions", arr.toString())
        putLong("portfolio_at", System.currentTimeMillis())
    }

    fun loadPortfolio(): Pair<PortfolioSummary, List<PortfolioPosition>>? {
        val raw = getJson("portfolio_summary") ?: return null
        val s = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val summary = PortfolioSummary(s.optInt("totalPositions"), s.optDouble("unrealizedPnl"),
            s.optDouble("realizedPnl"), s.optDouble("totalPnl"), s.optDouble("balance"), s.optDouble("equity"))
        val positions = mutableListOf<PortfolioPosition>()
        runCatching { JSONArray(getJson("portfolio_positions") ?: "[]") }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                positions += PortfolioPosition(o.optString("id"), o.optString("symbol"), o.optString("side"),
                    o.optDouble("entryPrice"), o.optDouble("currentPrice"), o.optDouble("lotSize"),
                    o.optDouble("sl"), o.optDouble("tp"), o.optDouble("unrealizedPnl"), o.optDouble("realizedPnl"),
                    runCatching { PositionStatus.valueOf(o.optString("status", "OPEN")) }.getOrDefault(PositionStatus.OPEN),
                    o.optLong("openedAt"), o.optLong("closedAt", -1).takeIf { it >= 0 })
            }
        }
        return summary to positions
    }

    // ---- pending offline ops (favorite/delete replayed on next sync) ----
    fun queueOp(op: JSONObject) {
        val q = runCatching { JSONArray(getJson("pending_ops") ?: "[]") }.getOrDefault(JSONArray())
        q.put(op)
        putJson("pending_ops", q.toString())
    }

    fun drainOps(): List<JSONObject> {
        val raw = getJson("pending_ops") ?: return emptyList()
        putJson("pending_ops", "[]")
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * Drop queued offline ops matching a predicate. Used to prune stale
     * intents: a re-add cancels a queued remove (and vice versa), so a
     * replayed op can never resurrect or delete against newer user intent.
     */
    fun removeOps(match: (JSONObject) -> Boolean) {
        val arr = runCatching { JSONArray(getJson("pending_ops") ?: "[]") }.getOrDefault(JSONArray())
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!runCatching { match(o) }.getOrDefault(false)) kept.put(o)
        }
        putJson("pending_ops", kept.toString())
    }

    // ---- misc prefs ----
    fun saveNotifPrefs(p: NotificationPrefs) {
        putJson("notif_prefs", JSONObject()
            .put("signalAlerts", p.signalAlerts).put("tpSlAlerts", p.tpSlAlerts)
            .put("newsAlerts", p.newsAlerts).put("dailySummary", p.dailySummary).toString())
    }

    fun loadNotifPrefs(): NotificationPrefs {
        val o = runCatching { JSONObject(getJson("notif_prefs") ?: "") }.getOrNull() ?: return NotificationPrefs()
        return NotificationPrefs(o.optBoolean("signalAlerts", true), o.optBoolean("tpSlAlerts", true),
            o.optBoolean("newsAlerts", true), o.optBoolean("dailySummary", true))
    }

    fun saveRiskProfile(v: String) { putJson("risk_profile", v) }
    fun loadRiskProfile(): String = getJson("risk_profile") ?: "MODERATE"
    fun saveScanIntervalMin(v: Int) { putLong("scan_interval_min", v.toLong()) }
    fun loadScanIntervalMin(): Int = getLong("scan_interval_min", 30).toInt().coerceIn(15, 480)

    // ---- local JSON <-> model for signals (mirrors server shape) ----
    private fun signalToJson(s: TradingSignal): JSONObject = JSONObject()
        .put("id", s.id).put("symbol", s.symbol).put("decision", s.decision.name)
        .put("confidence", s.confidence).put("entry", s.entry).put("sl", s.sl)
        .put("tp1", s.tp1).put("tp2", s.tp2).put("rr", s.rr)
        .put("scenario", s.scenario.name).put("reasoning", s.reasoning)
        .put("timestamp", s.timestamp)
        .put("is_favorite", if (s.isFavorite) 1 else 0)
        .put("is_read", if (s.isRead) 1 else 0)

    private fun JSONObject.toSignalKt(): TradingSignal = TradingEngineClient.run { this@toSignalKt.toSignal() }
}
