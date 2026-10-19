package com.frit.app.features.trading.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.frit.app.features.trading.repository.TradingRepository
import com.frit.app.features.trading.service.TradingEngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * TradingScanScheduler — background signal scanning without extra deps.
 *
 * Uses AlarmManager (inexact, battery-friendly) to fire TradingScanReceiver
 * every N minutes (default 30, configurable 15–480). Each run:
 *  1. scans the enabled watchlist via the engine,
 *  2. fires notifications for new BUY/SELL confirmations,
 *  3. checks TP/SL/entry touches on cached open signals.
 * All state lives in TradingCache so runs survive reboots (BOOT_COMPLETED
 * re-arms via the manifest-registered receiver).
 */
object TradingScanScheduler {
    private const val REQ = 7701

    fun schedule(ctx: Context, intervalMin: Int = TradingCache.loadScanIntervalMin()) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        am.cancel(pi)
        val ms = intervalMin.coerceIn(15, 480) * 60_000L
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ms, ms, pi)
        } catch (_: Exception) { }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pending(ctx)) } catch (_: Exception) { }
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, TradingScanReceiver::class.java).setAction("com.frit.app.ACTION_TRADING_SCAN")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }
}

class TradingScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        TradingCache.init(ctx)
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            try { TradingScanScheduler.schedule(ctx) } catch (_: Exception) { }
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. scan watchlist
                when (val r = TradingRepository.scan()) {
                    is TradingRepository.Result.Ok -> {
                        val seenKey = "seen_signal_ids"
                        val seen = (TradingCache.getJson(seenKey) ?: "").split(",").toMutableSet()
                        r.value.signals.filter {
                            (it.decision.name == "BUY" || it.decision.name == "SELL") && seen.add(it.id)
                        }.forEach { SignalNotificationManager.signalConfirmed(ctx, it) }
                        TradingCache.putJson(seenKey, seen.toList().takeLast(300).joinToString(","))
                    }
                    is TradingRepository.Result.Err -> { /* offline: keep quiet */ }
                }
                // 2. Options suite: equity credit spreads + FX expected-move levels.
                // Signal-only endpoints; each new signal id fires one floating banner.
                try {
                    val seenOpt = ((TradingCache.getJson("seen_options_ids") ?: "").split(",")).toMutableSet()
                    when (val r = TradingRepository.optionsScan()) {
                        is TradingRepository.Result.Ok -> {
                            r.value.filter { seenOpt.add(it.id) }
                                .forEach { SignalNotificationManager.optionsSignal(ctx, it) }
                        }
                        is TradingRepository.Result.Err -> { /* offline: keep quiet */ }
                    }
                    when (val r = TradingRepository.optionsFxScan()) {
                        is TradingRepository.Result.Ok -> {
                            r.value.filter { seenOpt.add(it.id) }
                                .forEach { SignalNotificationManager.optionsSignal(ctx, it) }
                        }
                        is TradingRepository.Result.Err -> { /* offline: keep quiet */ }
                    }
                    TradingCache.putJson("seen_options_ids", seenOpt.toList().takeLast(300).joinToString(","))
                } catch (_: Exception) { }
                // 3. TP/SL/entry touches on recent actionable signals
                val open = TradingCache.loadSignals()
                    .filter { it.decision.name == "BUY" || it.decision.name == "SELL" }
                    .sortedByDescending { it.timestamp }.take(10)
                if (open.isNotEmpty()) {
                    try {
                        TradingEngineClient.checkAlerts(open).forEach {
                            SignalNotificationManager.priceAlert(ctx, it.symbol, it.kind, it.price)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            } finally {
                try { pending.finish() } catch (_: Exception) { }
            }
        }
    }
}
