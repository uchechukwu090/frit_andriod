package com.frit.app.features.market

import android.content.Context
import android.content.Intent
import android.util.Log
import com.frit.app.data.NetworkClient
import kotlinx.coroutines.*

// ══════════════════════════════════════════════════════════════════════
// MT5Agent — Controls MetaTrader 5 via guided UI automation + Accessibility.
// NOTE: the old mt5://trade deep link is GONE on purpose. MetaQuotes
// publishes no trade intent for the Android app — firing the link resolved
// to an activity that silently dropped the order extras, so signals were
// "sent" while nothing was ever placed. All execution below drives the real
// order ticket (Quotes → symbol → Trade → volume/SL/TP → Buy/Sell).
// ══════════════════════════════════════════════════════════════════════
object MT5Agent {
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active monitoring jobs
    private var monitorJob: Job? = null
    private val openPositions = mutableListOf<TradePosition>()

    data class TradePosition(val ticket: String, val symbol: String, val type: String, val volume: Double, val openPrice: Double, val sl: Double, val tp: Double, val profit: Double)
    data class TradeOrder(val symbol: String, val action: String, val volume: Double, val sl: Double = 0.0, val tp: Double = 0.0, val comment: String = "FRIT")

    fun init(context: Context) { appContext = context.applicationContext }

    /**
     * Dynamically finds MetaTrader 5 or compatible trading app package.
     */
    fun findMT5Package(pm: android.content.pm.PackageManager): String? {
        val candidates = listOf(
            "net.metaquotes.metatrader5",
            "net.metaquotes.metatrader5.huawei",
            "com.exness.trader",
            "net.metaquotes.metatrader4"
        )
        for (pkg in candidates) {
            try {
                pm.getApplicationInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        // Fallback: scan all installed packages for metatrader / metaquotes
        return try {
            pm.getInstalledApplications(0).firstOrNull { ai ->
                val p = ai.packageName.lowercase()
                val l = runCatching { pm.getApplicationLabel(ai).toString().lowercase() }.getOrDefault("")
                p.contains("metatrader") || p.contains("metaquotes") || l.contains("metatrader")
            }?.packageName
        } catch (_: Exception) { null }
    }

    /**
     * Places a market order by driving the real MT5 order ticket:
     * launch → Quotes → symbol row → Trade → Volume/SL/TP → Buy/Sell.
     * Suspending: every caller (agent loop, local inference, analyzeAndTrade)
     * already runs in a coroutine. Returns an honest, verifiable result
     * string (never "sent" without proof).
     */
    suspend fun placeTrade(order: TradeOrder): String {
        val ctx = appContext ?: return "❌ Context not initialized"
        val pm = ctx.packageManager
        val mt5Package = findMT5Package(pm)
            ?: return "❌ MetaTrader app is not installed or not recognized on this device."

        return try {
            ctx.packageManager.getLaunchIntentForPackage(mt5Package)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }?.let { ctx.startActivity(it) } ?: return "❌ Unable to launch MetaTrader app ($mt5Package)"
            kotlinx.coroutines.delay(3000)

            // 1. Quotes tab (Market Watch list with all symbols).
            FritAccessibilityService.clickButtonWithText("Quotes")
            kotlinx.coroutines.delay(1200)
            var screen = FritAccessibilityService.getScreenText()
            if (!screen.contains(order.symbol, ignoreCase = true)) {
                return "⚠️ ${order.symbol} not visible in Quotes. Add it to Market Watch first (Quotes → + → ${order.symbol}), then retry. Open positions: ${FritAccessibilityService.getMT5Positions().joinToString { it.symbol }}"
            }

            // 2. Tap the symbol row to open its context actions.
            FritAccessibilityService.clickButtonWithText(order.symbol)
            kotlinx.coroutines.delay(1200)

            // 3. Open the order ticket.
            screen = FritAccessibilityService.getScreenText()
            val ticketOpened = FritAccessibilityService.clickButtonWithText("Trade") ||
                FritAccessibilityService.clickButtonWithText("New order") ||
                FritAccessibilityService.clickButtonWithText("New Order")
            if (!ticketOpened) {
                return "⚠️ Tapped ${order.symbol} but no Trade/New order button appeared. Screen shows: ${screen.take(300)}"
            }
            kotlinx.coroutines.delay(1500)

            // 4. Fill the ticket, VERIFYING each value stuck (MT5 often eats
            // the first attempt when the keypad/focus races). Refuse to place
            // naked or wrong-sized orders: any requested-but-unverified field
            // aborts the whole ticket.
            val volStatus = fillTicketField("Volume", order.volume.toString())
            val slStatus = if (order.sl > 0) fillTicketField("Stop Loss", order.sl.toString()) else "skipped"
            val tpStatus = if (order.tp > 0) fillTicketField("Take Profit", order.tp.toString()) else "skipped"
            val bad = listOf("Volume" to volStatus, "SL" to slStatus, "TP" to tpStatus)
                .filter { it.second != "ok" && it.second != "skipped" }
            if (bad.isNotEmpty()) {
                return "⛔ ABORTED — ${bad.joinToString { "${it.first} (${it.second})" }} never appeared on the ticket. Order NOT placed (refusing wrong-size/naked trade). Statuses: Volume=$volStatus SL=$slStatus TP=$tpStatus. Fix the field manually or retry — do NOT tap Buy/Sell blind."
            }

            // 5. Request-execution accounts need a price request first.
            screen = FritAccessibilityService.getScreenText()
            val side = if (order.action.uppercase() == "BUY") "Buy" else "Sell"
            if (!screen.contains(side, ignoreCase = true) && screen.contains("Request", ignoreCase = true)) {
                FritAccessibilityService.clickButtonWithText("Request")
                kotlinx.coroutines.delay(2000)
            }

            // 6. Place the order.
            val tapped = FritAccessibilityService.clickButtonWithText(side)
            if (!tapped) {
                return "⚠️ Ticket open for ${order.symbol} but no $side button found. Complete it manually in MT5."
            }
            kotlinx.coroutines.delay(3000)

            // 7. Verify on screen — look for proof, not absence of errors.
            screen = FritAccessibilityService.getScreenText()
            val low = screen.lowercase()
            val errorDetected = low.contains("error") || low.contains("failed") ||
                low.contains("invalid") || low.contains("not enough") ||
                low.contains("off quotes") || low.contains("requote") ||
                low.contains("trade is disabled")
            val confirmed = screen.contains(order.symbol, ignoreCase = true) &&
                (low.contains("position") || low.contains("deal") || low.contains("order") ||
                 low.contains("ticket") || low.contains("done") || low.contains("success"))
            when {
                errorDetected -> "⚠️ MT5 shows an error after placing ${order.action.uppercase()} ${order.volume} ${order.symbol} — check MT5 Trade tab. Snippet: ${screen.take(200)}"
                confirmed     -> "✅ ${order.action.uppercase()} ${order.volume} ${order.symbol} placed via MT5 ticket (Volume/SL/TP verified on ticket: $volStatus/$slStatus/$tpStatus) — verify in Trade tab"
                else          -> "⚠️ $side tapped for ${order.symbol} but confirmation unclear — VERIFY in MT5 Trade tab before retrying (do NOT blindly re-place)"
            }
        } catch (e: Exception) {
            Log.e("MT5Agent", "Trade error: ${e.message}")
            "❌ MT5 trade failed: ${e.message}"
        }
    }

    /**
     * Tap a ticket field label, type the value, dismiss the keypad, then VERIFY
     * the value actually stuck (MT5 tickets often eat the first attempt when
     * focus/keypad race). Retries once. Returns "ok" or "UNVERIFIED".
     */
    private suspend fun fillTicketField(label: String, value: String): String {
        repeat(2) {
            try {
                FritAccessibilityService.clickButtonWithText(label)
                kotlinx.coroutines.delay(700)
                FritAccessibilityService.executeStep("type_text", value = value)
                kotlinx.coroutines.delay(700)
                // Back dismisses the keypad first (standard Android); without
                // this the keypad covers the ticket and the value is dropped.
                FritAccessibilityService.executeStep("press_back")
                kotlinx.coroutines.delay(700)
                if (screenHasValue(FritAccessibilityService.getScreenText(), value)) return "ok"
            } catch (e: Exception) {
                Log.w("MT5Agent", "fillTicketField($label) attempt failed: ${e.message}")
            }
            kotlinx.coroutines.delay(500)
        }
        return "UNVERIFIED"
    }

    /** Numeric compare tolerant of trailing zeros ("1.5" matches "1.50"). */
    private fun screenHasValue(screen: String, value: String): Boolean {
        val want = value.toDoubleOrNull() ?: return screen.contains(value)
        return screen.split(Regex("[^0-9.]")).any { tok ->
            tok.isNotBlank() && tok.toDoubleOrNull()?.let { it == want } == true
        }
    }

    fun startMonitoring(onUpdate: (String) -> Unit) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val monitoredTickets = mutableSetOf<String>()
            while (isActive) {
                try {
                    val currentPositions = FritAccessibilityService.getMT5Positions()
                    val currentTickets = currentPositions.map { it.ticket }.toSet()

                    // Sync closed positions with server /positions/resolve
                    monitoredTickets.forEach { oldTicket ->
                        if (oldTicket.isNotBlank() && !currentTickets.contains(oldTicket)) {
                            Log.i("MT5Agent", "Ticket $oldTicket closed on device. Syncing resolution.")
                            val screenText = FritAccessibilityService.getScreenText().lowercase()
                            val outcome = when {
                                screenText.contains("tp") || screenText.contains("take profit") -> "TP_HIT"
                                screenText.contains("sl") || screenText.contains("stop loss") -> "SL_HIT"
                                else -> "CLOSED"
                            }
                            NetworkClient.resolvePosition(oldTicket, outcome)
                        }
                    }

                    monitoredTickets.clear()
                    monitoredTickets.addAll(currentTickets)

                    if (currentPositions.isNotEmpty()) {
                        openPositions.clear()
                        openPositions.addAll(currentPositions)
                        val summary = currentPositions.joinToString("\n") {
                            "${it.type} ${it.symbol} | Vol:${it.volume} | P/L: ${String.format("%.2f", it.profit)}"
                        }
                        onUpdate("📊 MT5 Positions:\n$summary")
                    }
                } catch (e: Exception) {
                    Log.e("MT5Agent", "Error in position watcher loop", e)
                }
                delay(30_000L)
            }
        }
    }

    fun stopMonitoring() { monitorJob?.cancel() }
    fun getPositions(): List<TradePosition> = openPositions.toList()

    /**
     * Modify SL/TP of an open position via guided UI steps.
     * Deep-links can't modify orders, so: open MT5 → Trade tab → long-press
     * the position row → tap Modify → type new SL/TP → tap Modify/Save.
     * Returns a human-readable result; cloud brain calls search_web first for
     * broker-specific quirks, then issues these concrete taps via local hands.
     */
    suspend fun modifyPosition(symbol: String, newSl: Double = 0.0, newTp: Double = 0.0): String {
        val ctx = appContext ?: return "❌ Context not initialized"
        val pkg = findMT5Package(ctx.packageManager)
            ?: return "❌ MetaTrader app not installed"
        try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }?.let { ctx.startActivity(it) }
            kotlinx.coroutines.delay(2500)
            // Navigate: Trade tab → position row
            FritAccessibilityService.clickButtonWithText("Trade")
            kotlinx.coroutines.delay(1200)
            var screen = FritAccessibilityService.getScreenText()
            if (!screen.contains(symbol, ignoreCase = true))
                return "⚠️ $symbol not visible in Trade tab. Open positions: ${FritAccessibilityService.getMT5Positions().joinToString { it.symbol }}"
            // Long-press the row to open the position menu, then Modify.
            FritAccessibilityService.executeStep("long_press_element", label = symbol)
            kotlinx.coroutines.delay(1200)
            val modified = FritAccessibilityService.clickButtonWithText("Modify")
            if (!modified) {
                FritAccessibilityService.executeStep("tap_button", label = "Modify")
                kotlinx.coroutines.delay(800)
            }
            if (newSl > 0) {
                FritAccessibilityService.executeStep("tap_button", label = "Stop Loss")
                kotlinx.coroutines.delay(600)
                FritAccessibilityService.executeStep("type_text", value = newSl.toString())
            }
            if (newTp > 0) {
                FritAccessibilityService.executeStep("tap_button", label = "Take Profit")
                kotlinx.coroutines.delay(600)
                FritAccessibilityService.executeStep("type_text", value = newTp.toString())
            }
            kotlinx.coroutines.delay(800)
            val saved = FritAccessibilityService.clickButtonWithText("Modify")
                || FritAccessibilityService.clickButtonWithText("Save")
            screen = FritAccessibilityService.getScreenText()
            return if (saved || screen.contains(symbol, ignoreCase = true))
                "✅ Modify dispatched for $symbol (SL=$newSl TP=$newTp) — verify in MT5 Trade tab"
            else "⚠️ Modify steps sent but confirmation unclear — check MT5 manually"
        } catch (e: Exception) {
            return "❌ Modify failed: ${e.message}"
        }
    }

    /** Close a position via guided UI steps (Trade → long-press → Close). */
    suspend fun closePosition(symbol: String): String {
        val ctx = appContext ?: return "❌ Context not initialized"
        val pkg = findMT5Package(ctx.packageManager)
            ?: return "❌ MetaTrader app not installed"
        return try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }?.let { ctx.startActivity(it) }
            kotlinx.coroutines.delay(2500)
            FritAccessibilityService.clickButtonWithText("Trade")
            kotlinx.coroutines.delay(1200)
            FritAccessibilityService.executeStep("long_press_element", label = symbol)
            kotlinx.coroutines.delay(1200)
            val closed = FritAccessibilityService.clickButtonWithText("Close")
                || FritAccessibilityService.clickButtonWithText("Close position")
            kotlinx.coroutines.delay(1500)
            if (closed) "✅ Close dispatched for $symbol — verify in MT5" else "⚠️ Could not find Close for $symbol — check MT5 manually"
        } catch (e: Exception) { "❌ Close failed: ${e.message}" }
    }

    suspend fun analyzeAndTrade(symbol: String, volume: Double, userCommand: String): String {
        val signal = MarketAnalysisEngine.analyze(symbol)
        
        if (signal.isNewsBlocked) {
            return "🚫 Trade Blocked: ${signal.aiOpinion}"
        }

        val isForce = userCommand.contains("force", ignoreCase = true)
        val isBuy   = userCommand.contains("buy",   ignoreCase = true)
        val isSell  = userCommand.contains("sell",  ignoreCase = true)

        if (isForce) {
            val action = if (isSell) "SELL" else "BUY"
            return placeTrade(TradeOrder(symbol, action, volume)) + "\n\n" + signal.summary
        }

        val plan = signal.tradePlan
        val sl = plan?.sl?.toDoubleOrNull() ?: 0.0
        val tp = plan?.tp?.toDoubleOrNull() ?: 0.0

        val shouldBuy  = signal.direction.contains("BULLISH") && signal.confidence >= 60
        val shouldSell = signal.direction.contains("BEARISH") && signal.confidence >= 60

        return when {
            shouldBuy  || (isBuy  && signal.direction != "BEARISH") -> {
                val finalSl = if (sl > 0) sl else (if (signal.support > 0) signal.support * 0.998 else 0.0)
                val finalTp = if (tp > 0) tp else (if (signal.resistance > 0) signal.resistance * 1.002 else 0.0)
                placeTrade(TradeOrder(symbol, "BUY", volume, finalSl, finalTp)) + "\n\n" + signal.summary
            }
            shouldSell || (isSell && signal.direction != "BULLISH") -> {
                val finalSl = if (sl > 0) sl else (if (signal.resistance > 0) signal.resistance * 1.002 else 0.0)
                val finalTp = if (tp > 0) tp else (if (signal.support > 0) signal.support * 0.998 else 0.0)
                placeTrade(TradeOrder(symbol, "SELL", volume, finalSl, finalTp)) + "\n\n" + signal.summary
            }
            else -> "No clear entry: ${signal.direction}(Conf:${signal.confidence}%)\n\n${signal.summary}\n\nSay: force buy $symbol"
        }
    }
}
