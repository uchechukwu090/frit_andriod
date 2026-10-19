package com.frit.app.features.trading.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.frit.app.MainActivity
import com.frit.app.core.Diag
import com.frit.app.features.trading.model.OptionsSignal
import com.frit.app.features.trading.model.TradingSignal

/**
 * SignalNotificationManager — local notifications for the trading suite.
 *
 * Fires on: confirmed engine signals (BUY/SELL), TP/SL/entry touches (via
 * /api/trading/alerts/check during background scans), options-suite signals
 * (/options/scan + /options/fx-scan), and the daily summary.
 * Options alerts use their own IMPORTANCE_HIGH channel so they always arrive
 * as a floating top banner (heads-up), like spot signals. Nothing here
 * places orders — every notification opens the app for review.
 * Respects NotificationPrefs stored in TradingCache. No FCM dependency — the
 * background scan scheduler triggers these locally, with an optional server
 * push-token registration for future FCM use.
 * (minSdk 26: framework Notification.Builder + channels, no compat lib needed.)
 */
object SignalNotificationManager {
    const val CH_SIGNAL = "frit_trading_signals"
    const val CH_PRICE = "frit_trading_price"
    const val CH_SUMMARY = "frit_trading_summary"
    const val CH_OPTIONS = "frit_options_signals"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SIGNAL, "Trading signals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_PRICE, "Price alerts (TP/SL)", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SUMMARY, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_OPTIONS, "Options signals", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun intent(ctx: Context, tab: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = "com.frit.app.OPEN_TRADING"
            putExtra("trading_tab", tab)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, tab.hashCode(), i, flags)
    }

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, tab: String) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(intent(ctx, tab))
            .setAutoCancel(true)
            .build()
        // A failed post means the user never sees the trading alert — log it
        // instead of dropping the signal silently (audit F-16).
        try { nm.notify(id, n) } catch (e: Exception) { Diag.warn("Trading", "notify", e) }
    }

    fun signalConfirmed(ctx: Context, s: TradingSignal) {
        val prefs = TradingCache.loadNotifPrefs()
        if (!prefs.signalAlerts) return
        val dir = if (s.decision.name == "BUY") "BUY" else "SELL"
        post(ctx, CH_SIGNAL, ("sig" + s.id).hashCode(),
            "$dir ${s.symbol} @ ${fmt(s.entry)}",
            "Conf ${s.confidence}% · SL ${fmt(s.sl)} · TP1 ${fmt(s.tp1)} · TP2 ${fmt(s.tp2)} · R:R ${s.rr}",
            "signals")
    }

    fun priceAlert(ctx: Context, symbol: String, kind: String, price: Double) {
        val prefs = TradingCache.loadNotifPrefs()
        if (!prefs.tpSlAlerts) return
        val label = when (kind) {
            "TP1_HIT" -> "TP1 hit"
            "TP2_HIT" -> "TP2 hit"
            "SL_HIT" -> "SL hit"
            "ENTRY_TOUCH" -> "Entry touched"
            else -> kind
        }
        post(ctx, CH_PRICE, ("px" + symbol + kind).hashCode(),
            "$label — $symbol @ $price", "$label on $symbol at $price. Review the position.", "portfolio")
    }

    fun dailySummary(ctx: Context, text: String) {
        val prefs = TradingCache.loadNotifPrefs()
        if (!prefs.dailySummary) return
        post(ctx, CH_SUMMARY, 9001, "Trading day summary", text, "dashboard")
    }

    /**
     * Options-suite signal → floating top banner. Covers both equity credit
     * spreads (paper, delayed quotes) and synthetic FX expected-move levels.
     * The body always states the data basis so a model estimate can never be
     * mistaken for a market quote.
     */
    fun optionsSignal(ctx: Context, s: OptionsSignal) {
        val prefs = TradingCache.loadNotifPrefs()
        if (!prefs.signalAlerts) return
        val tag = when {
            s.synthetic -> "model"
            s.provisional -> "delayed"
            else -> "paper"
        }
        post(ctx, CH_OPTIONS, ("opt" + s.id).hashCode(),
            "◈ ${s.title}",
            "${s.text} [$tag — signal only, no order placed]",
            "signals")
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "—" else "%,.${if (v > 1000) 2 else 5}f".format(v).trimEnd('0').trimEnd('.')
}
