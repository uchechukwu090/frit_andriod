package com.frit.app.ui.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.features.trading.model.*
import com.frit.app.features.trading.repository.TradingRepository
import com.frit.app.features.trading.service.SignalNotificationManager
import com.frit.app.features.trading.service.TradingCache
import com.frit.app.features.trading.service.TradingScanScheduler
import com.frit.app.features.trading.service.TradingEngineClient
import com.frit.app.ui.theme.FritNegative
import com.frit.app.ui.theme.FritPositive
import com.frit.app.ui.theme.FritTextMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * TradingSuiteScreen — single entry point for the AI trading suite.
 *
 * Internal tab navigation (no nav-compose dep): Dashboard · Watchlist ·
 * Signals · Portfolio · Risk · Sentiment · AI · Learn · Settings.
 * Signal detail is an overlay state. All data flows through
 * TradingRepository (network-first, TradingCache fallback → offline mode).
 */
enum class TradingTab(val title: String) {
    DASHBOARD("Dashboard"), WATCHLIST("Watchlist"), SIGNALS("Signals"),
    PORTFOLIO("Portfolio"), RISK("Risk"), SENTIMENT("Sentiment"),
    LEARN("Learn"), SETTINGS("Settings")
}

class TradingUiState {
    var tab by mutableStateOf(TradingTab.DASHBOARD)
    var watchlist by mutableStateOf<List<WatchlistItem>>(emptyList())
    var signals by mutableStateOf<List<TradingSignal>>(emptyList())
    var positions by mutableStateOf<List<PortfolioPosition>>(emptyList())
    var summary by mutableStateOf<PortfolioSummary?>(null)
    var riskJson by mutableStateOf<JSONObject?>(null)
    var sentiment by mutableStateOf<Map<String, SentimentData>>(emptyMap())
    var recommendations by mutableStateOf<List<AIRecommendation>>(emptyList())
    var learning by mutableStateOf<List<LearningContent>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false)
    var lastScanAt by mutableStateOf(0L)
    var detailSignal by mutableStateOf<TradingSignal?>(null)
    var explanation by mutableStateOf<Pair<String, List<ExplanationFactor>>?>(null)
    var favOnly by mutableStateOf(false)
    // Unified paper portfolio (bot-tagged executions from every engine)
    var paperTrades by mutableStateOf(listOf<org.json.JSONObject>())
    var paperByBot by mutableStateOf(mapOf<String, org.json.JSONObject>())
    var paperBotFilter by mutableStateOf("ALL")
    // Bot-pair association + notifications
    var botPairs by mutableStateOf<List<BotPairConfig>>(emptyList())
    var notifications by mutableStateOf<List<BotNotification>>(emptyList())
    var showUpgradeDialog by mutableStateOf(false)
    var upgradeMessage by mutableStateOf("")
    // Lets the tier-gate dialog deep-link the user to their Wallet.
    var onOpenWallet by mutableStateOf<(() -> Unit)?>(null)
    // Full trade detail behind a tapped notification.
    var detailNotification by mutableStateOf<BotNotification?>(null)

    fun load(scope: CoroutineScope, what: String = "all") {
        scope.launch(Dispatchers.IO) {
            launch(Dispatchers.Main) { loading = true; error = null }
            try {
                if (what == "all" || what == "watchlist")
                    when (val r = TradingRepository.watchlist()) {
                        is TradingRepository.Result.Ok -> setWatch(r.value, r.fromCache)
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
                if (what == "all" || what == "signals")
                    when (val r = TradingRepository.signals(favoritesOnly = favOnly)) {
                        is TradingRepository.Result.Ok -> setSignals(r.value, r.fromCache)
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
                if (what == "all" || what == "portfolio")
                    when (val r = TradingRepository.portfolio()) {
                        is TradingRepository.Result.Ok -> {
                            launch(Dispatchers.Main) {
                                summary = r.value.first; positions = r.value.second
                                if (r.fromCache) offline = true
                            }
                        }
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
                if (what == "all" || what == "risk")
                    when (val r = TradingRepository.risk()) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { riskJson = r.value }
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
            } finally {
                launch(Dispatchers.Main) { loading = false }
            }
        }
    }

    private fun setWatch(v: List<WatchlistItem>, cached: Boolean) {
        watchlist = v; if (cached) offline = true
    }
    private fun setSignals(v: List<TradingSignal>, cached: Boolean) {
        signals = v; if (cached) offline = true
    }
    private fun setErr(m: String) { error = m; offline = true }
}

@Composable
fun rememberTradingUi(): TradingUiState = remember { TradingUiState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradingSuiteScreen(onClose: () -> Unit, initialTab: String? = null, onOpenWallet: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val ui = rememberTradingUi()
    val scope = rememberCoroutineScope()
    // The tier-gate dialog deep-links to the Wallet so the user can pay
    // without hunting for the subscribe screen.
    ui.onOpenWallet = onOpenWallet
    LaunchedEffect(Unit) {
        TradingCache.init(ctx)
        com.frit.app.features.trading.service.TradingEngineClient.init {
            ctx.getSharedPreferences("frit_prefs", android.content.Context.MODE_PRIVATE)
        }
        SignalNotificationManager.ensureChannels(ctx)
        initialTab?.let { t ->
            ui.tab = TradingTab.values().firstOrNull { it.name.equals(t, true) } ?: TradingTab.DASHBOARD
        }
        ui.load(scope)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Trading Suite" + if (ui.offline) " · offline" else "") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { ui.load(scope, "all") }) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                listOf(TradingTab.DASHBOARD, TradingTab.WATCHLIST, TradingTab.SIGNALS, TradingTab.PORTFOLIO, TradingTab.SETTINGS).forEach { t ->
                    NavigationBarItem(
                        selected = ui.tab == t || (t == TradingTab.SIGNALS && ui.detailSignal != null),
                        onClick = {
                            ui.detailSignal = null; ui.tab = t
                            val what = if (t == TradingTab.DASHBOARD) "all" else t.name.lowercase()
                            ui.load(scope, what)
                        },
                        icon = { Icon(tabIcon(t), t.title) }, label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                ui.detailSignal != null -> SignalDetailScreen(ui, scope)
                else -> Column(Modifier.fillMaxSize()) {
                    NotificationBar(ui, scope)
                    when (ui.tab) {
                        TradingTab.DASHBOARD -> DashboardScreen(ui, scope)
                        TradingTab.WATCHLIST -> WatchlistScreen(ui, scope)
                        TradingTab.SIGNALS -> SignalsScreen(ui, scope)
                        TradingTab.PORTFOLIO -> PortfolioScreen(ui, scope)
                        TradingTab.RISK -> RiskScreen(ui, scope)
                        TradingTab.SENTIMENT -> SentimentScreen(ui, scope)
                        TradingTab.LEARN -> LearningScreen(ui, scope)
                        TradingTab.SETTINGS -> TradingSettingsScreen(ui, scope)
                    }
                }
            }
        }
    }
}

// ================= Signal Bar =================
// A single bar at the top of the trading suite that receives signals from
// EVERY engine and bot (main engine, scalping, pocket, intraday, long-term,
// options, Deriv settlements). Each entry is TAGGED with the name of the bot
// that produced it, and tapping it opens the full trade detail (entry, SL,
// TP1/TP2, R:R, the Kelly lot size and the engine's reasoning).
@Composable
private fun NotificationBar(ui: TradingUiState, scope: CoroutineScope) {
    var expanded by remember { mutableStateOf(false) }

    // Poll for new signals so the bar is live without a manual refresh.
    LaunchedEffect(Unit) {
        while (true) {
            scope.launch(Dispatchers.IO) {
                when (val r = TradingRepository.notifications(30)) {
                    is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { ui.notifications = r.value }
                    is TradingRepository.Result.Err -> { }
                }
            }
            kotlinx.coroutines.delay(30_000)
        }
    }

    val notifications = ui.notifications
    if (notifications.isEmpty()) return

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, null, Modifier.height(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Signals", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("${notifications.size}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                notifications.take(25).forEach { n ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { ui.detailNotification = n }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bot tag — the name of the engine/bot that fired.
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(n.bot.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${n.symbol}  ${n.decision}",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            if (n.note.isNotBlank()) {
                                Text(n.note, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1)
                            }
                        }
                        if (n.confidence > 0) {
                            Text("${n.confidence}%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // ---- full trade detail (tap a notification) ----
    ui.detailNotification?.let { n ->
        val d = n.detail
        AlertDialog(
            onDismissRequest = { ui.detailNotification = null },
            title = {
                Column {
                    Text("${n.decision} ${n.symbol}", fontWeight = FontWeight.Bold)
                    Text("from ${n.bot}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (d != null) {
                        DetailRow("Entry", fmtN(d.entry))
                        DetailRow("Stop-loss", fmtN(d.sl))
                        DetailRow("TP1", fmtN(d.tp1))
                        DetailRow("TP2", fmtN(d.tp2))
                        DetailRow("R:R", d.rr.toString())
                        DetailRow("Confidence", "${n.confidence}%")
                        if (d.scenario.isNotBlank()) DetailRow("Scenario", d.scenario)
                        // Kelly lot-size recommendation (item 9).
                        if (d.lotSize != null && d.lotSize > 0) {
                            DetailRow("Recommended lot", "${d.lotSize}")
                            if (d.riskUsd != null) DetailRow("Risk", "$${"%.2f".format(d.riskUsd)}")
                        }
                        if (!d.lotReason.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Sizing", fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall)
                            Text(d.lotReason, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.reasoning.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Engine reasoning", fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall)
                            Text(d.reasoning, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(n.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ui.detailNotification = null }) { Text("Close") }
            }
        )
    }
}

private fun tabIcon(t: TradingTab) = when (t) {
    TradingTab.DASHBOARD -> Icons.Filled.Dashboard
    TradingTab.WATCHLIST -> Icons.Filled.List
    TradingTab.SIGNALS -> Icons.Filled.Notifications
    TradingTab.PORTFOLIO -> Icons.Filled.AccountBalanceWallet
    TradingTab.RISK -> Icons.Filled.Shield
    TradingTab.SENTIMENT -> Icons.Filled.Mood
    TradingTab.LEARN -> Icons.Filled.School
    TradingTab.SETTINGS -> Icons.Filled.Settings
}

// ================= Dashboard =================
@Composable
private fun DashboardScreen(ui: TradingUiState, scope: CoroutineScope) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Market overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        launch(Dispatchers.Main) { ui.loading = true }
                        when (val r = TradingRepository.scan()) {
                            is TradingRepository.Result.Ok -> {
                                launch(Dispatchers.Main) {
                                    ui.lastScanAt = r.value.scannedAt
                                    val merged = (r.value.signals + ui.signals).distinctBy { it.id }
                                        .sortedByDescending { it.timestamp }.take(200)
                                    ui.signals = merged
                                    r.value.signals.filter { it.decision == SignalDecision.BUY || it.decision == SignalDecision.SELL }
                                        .forEach { SignalNotificationManager.signalConfirmed(ctx, it) }
                                }
                            }
                            is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
                        }
                        launch(Dispatchers.Main) { ui.loading = false }
                    }
                }) { Text(if (ui.loading) "Scanning…" else "Scan now") }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            val s = ui.summary
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Portfolio", fontWeight = FontWeight.Bold)
                    Text("Equity: ${fmtMoney(s?.equity)}   P&L: ${fmtMoney(s?.totalPnl)}")
                    Text("Open: ${s?.totalPositions ?: 0}   Unrealized: ${fmtMoney(s?.unrealizedPnl)}")
                }
            }
        }
        item { Text("Latest signals", fontWeight = FontWeight.Bold) }
        val actionable = ui.signals.filter { it.decision == SignalDecision.BUY || it.decision == SignalDecision.SELL }.take(5)
        if (actionable.isEmpty()) item { Text("No BUY/SELL signals yet — add pairs in Watchlist, then Scan.") }
        items(actionable, key = { it.id }) { SignalCard(it, ui, scope) }
        item { Text("Watchlist (${ui.watchlist.size})", fontWeight = FontWeight.Bold) }
        items(ui.watchlist.take(8), key = { it.symbol }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${it.symbol}  ·  ${it.assetClass.displayName}")
                Spacer(Modifier.weight(1f))
                Text(if (it.isEnabled) "on" else "off", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ================= Watchlist =================
// One card per pair. Each card carries:
//   - the pair name + a 24/7 WATCH toggle (turn the engine's continuous
//     scanning on/off for this pair),
//   - a horizontal SCROLL TAB strip of every available bot, so the user picks
//     the bot they want for THIS pair by tapping its chip,
//   - per-bot controls: timeframe, and separate switches to TRADE (place
//     orders) and SIGNAL (just notify) — either can be stopped at any time,
//   - the account balance + risk profile used to size the trades.
//
// Everything here is tier-gated: adding a pair, toggling 24/7 watching,
// choosing a bot/timeframe or switching a bot on all require a paid plan.
// The 402 carries the wallet link so the user can upgrade in one tap.
@Composable
private fun WatchlistScreen(ui: TradingUiState, scope: CoroutineScope) {
    var input by remember { mutableStateOf("") }
    var selectedPair by remember { mutableStateOf<String?>(null) }
    var botPairs by remember { mutableStateOf<List<BotPairConfig>>(emptyList()) }
    var availableBots by remember { mutableStateOf<List<BotConfig>>(emptyList()) }
    var account by remember { mutableStateOf(TradingAccount()) }
    var balanceInput by remember { mutableStateOf("") }
    var expandedPair by remember { mutableStateOf<String?>(null) }

    fun refreshBotPairs() {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.botPairs()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                    botPairs = r.value
                    TradingCache.saveBotPairs(r.value)
                }
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) {
                    // Offline: show the locally cached assignment so the user
                    // still sees which bot runs on which pair.
                    val cached = TradingCache.loadBotPairs()
                    if (cached.isNotEmpty()) botPairs = cached
                }
            }
        }
    }

    fun refreshBots() {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.bots()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { availableBots = r.value }
                is TradingRepository.Result.Err -> { }
            }
        }
    }

    fun refreshAccount() {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.account()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                    account = r.value
                    balanceInput = if (r.value.balance > 0) r.value.balance.toLong().toString() else ""
                }
                is TradingRepository.Result.Err -> {
                    val cached = TradingCache.loadAccount()
                    if (cached != null) launch(Dispatchers.Main) {
                        account = cached
                        balanceInput = if (cached.balance > 0) cached.balance.toLong().toString() else ""
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshBotPairs(); refreshBots(); refreshAccount() }

    // Upgrade dialog (tier gating, item 5).
    if (ui.showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { ui.showUpgradeDialog = false },
            title = { Text("Upgrade required") },
            text = { Text(ui.upgradeMessage) },
            confirmButton = {
                TextButton(onClick = {
                    ui.showUpgradeDialog = false
                    ui.onOpenWallet?.invoke()
                }) { Text("Go to Wallet") }
            },
            dismissButton = {
                TextButton(onClick = { ui.showUpgradeDialog = false }) { Text("Later") }
            }
        )
    }

    // Helper: run a gated action and surface the upgrade prompt on 402.
    fun gated(block: suspend () -> TradingRepository.Result<*>) {
        scope.launch(Dispatchers.IO) {
            when (val r = block()) {
                is TradingRepository.Result.Ok -> Unit
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) {
                    ui.error = r.message
                    if (r.message.contains("upgrade", ignoreCase = true) ||
                        r.message.contains("plan", ignoreCase = true) ||
                        r.message.contains("isn't in your", ignoreCase = true)
                    ) {
                        ui.upgradeMessage = "${r.message}\n\nSubscribe in your Wallet to use bots, signals and the 24/7 watcher."
                        ui.showUpgradeDialog = true
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // ---- account balance + risk profile (items 9, 11) ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Account balance", fontWeight = FontWeight.Bold)
                        Text("Used to size every trade (Kelly). Saved to your account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = balanceInput,
                        onValueChange = { balanceInput = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                        label = { Text("Balance") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Risk profile", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    listOf("CONSERVATIVE", "MODERATE", "AGGRESSIVE").forEach { p ->
                        FilterChip(
                            selected = account.riskProfile == p,
                            onClick = {
                                gated { TradingRepository.saveAccount(riskProfile = p) }
                                account = account.copy(riskProfile = p)
                            },
                            label = { Text(p.take(4), style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val bal = balanceInput.toDoubleOrNull()
                        if (bal != null && bal > 0) {
                            gated { TradingRepository.saveAccount(balance = bal) }
                            account = account.copy(balance = bal)
                        }
                    },
                    enabled = (balanceInput.toDoubleOrNull() ?: 0.0) > 0
                ) { Text("Save balance") }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- add pair ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it.uppercase().trim() },
                label = { Text("Symbol (e.g. EURUSD, XAUUSD)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val sym = input.uppercase().trim(); if (sym.isBlank()) return@Button
                scope.launch(Dispatchers.IO) {
                    when (val r = TradingRepository.addWatch(sym)) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                            ui.error = null
                            if (ui.watchlist.none { it.symbol == r.value.symbol }) ui.watchlist = ui.watchlist + r.value
                            input = ""
                        }
                        is TradingRepository.Result.Err -> launch(Dispatchers.Main) {
                            ui.error = r.message
                            if (r.message.contains("upgrade", ignoreCase = true) ||
                                r.message.contains("plan", ignoreCase = true) ||
                                r.message.contains("isn't in your", ignoreCase = true)
                            ) {
                                ui.upgradeMessage = "${r.message}\n\nSubscribe in your Wallet to add pairs and run bots."
                                ui.showUpgradeDialog = true
                            }
                        }
                    }
                }
            }) { Text("Add") }
        }
        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.watchlist, key = { it.symbol }) { w ->
                val pairBots = botPairs.filter { it.pair == w.symbol }
                val expanded = expandedPair == w.symbol
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(w.symbol, fontWeight = FontWeight.Bold)
                                Text("${w.name} · ${w.assetClass.displayName}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            // 24/7 watching toggle
                            Text("24/7", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = w.isEnabled, onCheckedChange = { en ->
                                gated { TradingRepository.setWatchEnabled(w.symbol, en) }
                                ui.watchlist = ui.watchlist.map {
                                    if (it.symbol == w.symbol) it.copy(isEnabled = en) else it
                                }
                            })
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { TradingRepository.removeWatch(w.symbol) }
                                ui.watchlist = ui.watchlist.filterNot { it.symbol == w.symbol }
                                botPairs = botPairs.filterNot { it.pair == w.symbol }
                            }) { Icon(Icons.Filled.Delete, "Remove") }
                        }

                        // ---- horizontal scroll tab strip of ALL bots ----
                        Text("Bots", style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableBots.forEach { bot ->
                                val cfg = pairBots.firstOrNull { it.botName == bot.name }
                                val active = cfg != null
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        if (active) {
                                            // Tapping an active bot stops it (removes
                                            // the assignment) — the user can always
                                            // re-enable it.
                                            scope.launch(Dispatchers.IO) {
                                                TradingRepository.removeBotPair(bot.name, w.symbol)
                                                refreshBotPairs()
                                            }
                                        } else {
                                            gated {
                                                TradingRepository.saveBotPair(
                                                    bot.name, w.symbol,
                                                    bot.allowedTimeframes.firstOrNull() ?: "5m",
                                                    tradeEnabled = false, signalEnabled = true,
                                                    minConfidence = 60, maxTradesDay = 2
                                                )
                                            }
                                            refreshBotPairs()
                                        }
                                    },
                                    label = {
                                        Text(
                                            bot.label.replace(" bot", ""),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    leadingIcon = if (active) {
                                        { Icon(Icons.Filled.Check, null, Modifier.height(16.dp)) }
                                    } else null,
                                    modifier = Modifier.padding(end = 2.dp)
                                )
                            }
                        }

                        // ---- per-bot controls for this pair ----
                        pairBots.forEach { bp ->
                            val bot = availableBots.firstOrNull { it.name == bp.botName }
                            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(bp.botName, fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    // TIMEFRAME (gated)
                                    bot?.allowedTimeframes?.forEach { tf ->
                                        FilterChip(
                                            selected = bp.timeframe == tf,
                                            onClick = {
                                                gated {
                                                    TradingRepository.saveBotPair(
                                                        bp.botName, w.symbol, tf,
                                                        bp.tradeEnabled, bp.signalEnabled,
                                                        bp.minConfidence, bp.maxTradesDay
                                                    )
                                                }
                                                refreshBotPairs()
                                            },
                                            label = { Text(tf, style = MaterialTheme.typography.bodySmall) },
                                            modifier = Modifier.padding(end = 3.dp)
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Notifications, null, Modifier.height(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Signal", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 4.dp).weight(1f))
                                    Switch(
                                        checked = bp.signalEnabled,
                                        onCheckedChange = { on ->
                                            gated {
                                                TradingRepository.saveBotPair(
                                                    bp.botName, w.symbol, bp.timeframe,
                                                    bp.tradeEnabled, on,
                                                    bp.minConfidence, bp.maxTradesDay
                                                )
                                            }
                                            refreshBotPairs()
                                        })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.TrendingUp, null, Modifier.height(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Trade", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 4.dp).weight(1f))
                                    Switch(
                                        checked = bp.tradeEnabled,
                                        onCheckedChange = { on ->
                                            gated {
                                                TradingRepository.saveBotPair(
                                                    bp.botName, w.symbol, bp.timeframe,
                                                    on, bp.signalEnabled,
                                                    bp.minConfidence, bp.maxTradesDay
                                                )
                                            }
                                            refreshBotPairs()
                                        })
                                }
                                Text(
                                    if (bp.tradeEnabled)
                                        "Trading + signalling on ${bp.timeframe} — sized by Kelly against your balance."
                                    else "Signals only on ${bp.timeframe} — no orders are placed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (pairBots.isEmpty()) {
                            Text("No bot selected for ${w.symbol} — tap a bot above to start watching it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ================= Signals =================
@Composable
private fun SignalsScreen(ui: TradingUiState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Signals (${ui.signals.size})", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            FilterChip(selected = ui.favOnly, onClick = {
                ui.favOnly = !ui.favOnly
                scope.launch(Dispatchers.IO) {
                    when (val r = TradingRepository.signals(favoritesOnly = ui.favOnly)) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { ui.signals = r.value }
                        is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
                    }
                }
            }, label = { Text("Favourites") })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(ui.signals, key = { it.id }) { SignalCard(it, ui, scope, showFav = true) }
        }
    }
}

@Composable
private fun SignalCard(s: TradingSignal, ui: TradingUiState, scope: CoroutineScope, showFav: Boolean = false) {
    val color = when (s.decision) {
        SignalDecision.BUY -> FritPositive
        SignalDecision.SELL -> FritNegative
        SignalDecision.WAIT_PULLBACK -> MaterialTheme.colorScheme.onSurfaceVariant
        SignalDecision.WAIT_RANGE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> FritTextMuted
    }
    Card(Modifier.fillMaxWidth().clickable { ui.detailSignal = s; TradingRepository.apply { scope.launch(Dispatchers.IO) { markRead(s.id) } } }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color, shape = MaterialTheme.shapes.small) {
                    Text(s.decision.name, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(s.symbol, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${s.confidence}%", fontWeight = FontWeight.Bold)
                if (showFav) {
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) { TradingRepository.setFavorite(s.id, !s.isFavorite) }
                        ui.signals = ui.signals.map { if (it.id == s.id) it.copy(isFavorite = !it.isFavorite) else it }
                    }) { Icon(if (s.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favourite") }
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) { TradingRepository.deleteSignal(s.id) }
                        ui.signals = ui.signals.filterNot { it.id == s.id }
                    }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Entry ${fmtN(s.entry)} · SL ${fmtN(s.sl)} · TP1 ${fmtN(s.tp1)} · TP2 ${fmtN(s.tp2)} · RR ${s.rr}",
                style = MaterialTheme.typography.bodySmall)
            Text(s.reasoning.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ================= Signal detail + explainable AI =================
@Composable
private fun SignalDetailScreen(ui: TradingUiState, scope: CoroutineScope) {
    val s = ui.detailSignal ?: return
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ui.detailSignal = null; ui.explanation = null }) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text("${s.decision.name} ${s.symbol}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailRow("Confidence", "${s.confidence}%")
                DetailRow("Scenario", s.scenario.name)
                DetailRow("Entry", fmtN(s.entry))
                DetailRow("Stop-loss", fmtN(s.sl))
                DetailRow("TP1", fmtN(s.tp1))
                DetailRow("TP2", fmtN(s.tp2))
                DetailRow("R:R", s.rr.toString())
                s.pullbackHealth?.let {
                    DetailRow("Pullback", "${it.status} · healthy=${it.isHealthy}")
                    DetailRow("Pullback prob", "${it.pullbackProbability ?: "—"}% vs reversal ${it.reversalProbability ?: "—"}%")
                    it.note?.let { n -> Text(n, style = MaterialTheme.typography.bodySmall) }
                }
                s.costs?.let {
                    DetailRow("Net RR (after costs)", it.netRR.toString() + if (it.blocked) " — BLOCKED" else if (it.weak) " — weak" else "")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Why this signal (engine reasoning)", fontWeight = FontWeight.Bold)
        Text(s.reasoning, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                when (val r = TradingRepository.explain(s.id)) {
                    is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { ui.explanation = r.value }
                    is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
                }
            }
        }) { Text("Explain with AI") }
        ui.explanation?.let { (plain, factors) ->
            Spacer(Modifier.height(6.dp))
            Text(plain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            factors.forEach { f ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${f.name}: ${f.value} [${f.impact.name}]", fontWeight = FontWeight.Bold)
                        Text(f.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    Row { Text("$k: ", fontWeight = FontWeight.SemiBold); Text(v) }
}

// ================= Portfolio: the REAL unified paper book =================
// Every engine, every bot and every rail lands in ONE ledger: the main MTF
// engine, the scalping / pocket / intraday / long-term bots, the options
// book, Deriv contracts (broker truth) and the journal (setup accuracy).
// The old tab only showed a capped slice of pocket trades — this one shows
// the whole book, attributed per bot, with per-bot win rates and P&L.
@Composable
private fun PortfolioScreen(ui: TradingUiState, scope: CoroutineScope) {
    var portfolio by remember { mutableStateOf<TradingEngineClient.PaperPortfolio?>(null) }
    var botFilter by remember { mutableStateOf("ALL") }
    var showFilter by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.paperPortfolio()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { portfolio = r.value }
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val p = portfolio
    val trades = p?.trades.orEmpty().let { all ->
        if (botFilter == "ALL") all else all.filter { it.bot == botFilter }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // ---- headline ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paper portfolio", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                }
                if (p == null) {
                    Text("Loading the book…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Balance $${"%.2f".format(p.balance)}   Equity $${"%.2f".format(p.equity)}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("P&L $${"%.2f".format(p.totalPnlUsd)}   ·   ${p.total} trades",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.totalPnlUsd >= 0) FritPositive else FritNegative)
                    Spacer(Modifier.height(8.dp))
                    // Regenerate the balance from the realized ledger (item 6).
                    Button(
                        onClick = {
                            busy = true
                            scope.launch(Dispatchers.IO) {
                                when (val r = TradingRepository.regenerateBalance()) {
                                    is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                                        busy = false
                                        ui.error = null
                                        refresh()
                                    }
                                    is TradingRepository.Result.Err -> launch(Dispatchers.Main) {
                                        busy = false
                                        ui.error = r.message
                                    }
                                }
                            }
                        },
                        enabled = !busy
                    ) { Text(if (busy) "Rebuilding…" else "Regenerate balance") }
                    Text("Recomputes your balance from every settled trade in the book.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- per-bot performance ----
        if (p != null && p.byBot.isNotEmpty()) {
            Text("By bot", fontWeight = FontWeight.Bold)
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(selected = botFilter == "ALL", onClick = { botFilter = "ALL" },
                        label = { Text("All") })
                }
                items(p.byBot.keys.toList()) { bot ->
                    val b = p.byBot[bot]!!
                    FilterChip(
                        selected = botFilter == bot,
                        onClick = { botFilter = bot },
                        label = {
                            Column {
                                Text(bot.uppercase(), style = MaterialTheme.typography.bodySmall)
                                Text("${b.trades} · ${b.winRate?.let { "${it.toInt()}%" } ?: "—"}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Headline numbers per bot.
            p.byBot.values.filter { botFilter == "ALL" || it.bot == botFilter }
                .forEach { b ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(b.bot.uppercase(), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("T${b.trades} W${b.wins} L${b.losses}" +
                                    (b.winRate?.let { " ${it.toInt()}%" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text("$${"%.2f".format(b.pnlUsd)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (b.pnlUsd >= 0) FritPositive else FritNegative)
                        }
                    }
                }
            Spacer(Modifier.height(8.dp))
        }

        // ---- the full trade list ----
        Text("All trades (${trades.size})", fontWeight = FontWeight.Bold)
        if (trades.isEmpty()) {
            Text("No trades yet. Assign a bot to a pair in the Watchlist and it will start trading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(trades, key = { "${it.bot}_${it.openedAt}_${it.symbol}_${it.side}" }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small) {
                                Text(t.bot.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("${t.side} ${t.symbol}", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(t.status, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val unit = if (t.pnlKind == "units") "u" else "$"
                        Text(
                            "Entry ${fmtNum(t.entry)} → ${fmtNum(t.exit)}   ·   P&L $unit${"%.2f".format(t.pnl)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (t.pnl >= 0) FritPositive else FritNegative
                        )
                        if (t.note.isNotBlank()) {
                            Text(t.note, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private fun fmtNum(v: Double): String =
    if (!v.isFinite() || v == 0.0) "—" else "%.5f".format(v).trimEnd('0').trimEnd('.')

// ================= Risk =================
@Composable
private fun RiskScreen(ui: TradingUiState, scope: CoroutineScope) {
    // The balance comes from the persisted account, not a throwaway field —
    // so the risk figures match the sizing the bots actually use.
    var account by remember { mutableStateOf(TradingAccount()) }
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.account()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { account = r.value }
                is TradingRepository.Result.Err -> {
                    TradingCache.loadAccount()?.let { c -> launch(Dispatchers.Main) { account = c } }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Risk management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Engine guards: max positions, correlation blocks, daily-loss kill-switch, 0.5–2% risk per trade.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val r = ui.riskJson
        if (r == null) Text("Loading risk status…")
        else {
            Text(r.toString(2), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text("Position sizing", fontWeight = FontWeight.Bold)
        Text("Balance $${"%.2f".format(account.balance)} · profile ${account.riskProfile}",
            style = MaterialTheme.typography.bodySmall)
        Text("Trade size is set by the Kelly criterion against your balance and risk profile " +
                "(aggressive = 1/2 Kelly, moderate = 1/4, conservative = 1/8), capped at 2% per trade.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ================= Sentiment =================
@Composable
private fun SentimentScreen(ui: TradingUiState, scope: CoroutineScope) {
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.sentiment(ui.watchlist.map { it.symbol }.take(10))) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { ui.sentiment = r.value }
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("News/social mood — refreshed daily, cached for offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.sentiment.isEmpty()) item { Text("No sentiment yet — add watchlist pairs first.") }
        ui.sentiment.forEach { (sym, d) ->
            item(sym) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(sym, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(d.sentiment.name, color = when (d.sentiment) {
                                Sentiment.BULLISH -> FritPositive
                                Sentiment.BEARISH -> FritNegative
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { d.score / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Score ${d.score} · bull refs ${d.bullishSignals} · bear refs ${d.bearishSignals}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(d.summary.take(280), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ================= Trading bots (scalping / pocket / intraday / long-term / options) =================
// Bot-pair association is now managed per-pair in the Watchlist screen.
// The AI Help tab is removed — bots are assigned to pairs directly there.
// See WatchlistScreen for the new bot-pair selection UI.

// ================= Learning hub =================
@Composable
private fun LearningScreen(ui: TradingUiState, scope: CoroutineScope) {
    LaunchedEffect(Unit) {
        if (ui.learning.isEmpty()) scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.learning()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { ui.learning = r.value }
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.learning.groupBy { it.category }.forEach { (cat, items) ->
            item { Text(cat.uppercase(), fontWeight = FontWeight.Bold) }
            items(items, key = { it.id }) { l ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(l.title, fontWeight = FontWeight.Bold)
                        Text("${l.readTime} min read", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(l.content, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ================= Settings =================
@Composable
private fun TradingSettingsScreen(ui: TradingUiState, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var prefs by remember { mutableStateOf(TradingCache.loadNotifPrefs()) }
    var interval by remember { mutableStateOf(TradingCache.loadScanIntervalMin().toString()) }
    var bgScan by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Alerts", fontWeight = FontWeight.Bold)
        PrefToggle("Signal alerts", prefs.signalAlerts) { prefs = prefs.copy(signalAlerts = it) }
        PrefToggle("TP/SL alerts", prefs.tpSlAlerts) { prefs = prefs.copy(tpSlAlerts = it) }
        PrefToggle("News alerts", prefs.newsAlerts) { prefs = prefs.copy(newsAlerts = it) }
        PrefToggle("Daily summary", prefs.dailySummary) { prefs = prefs.copy(dailySummary = it) }
        Button(onClick = {
            TradingCache.saveNotifPrefs(prefs)
            scope.launch(Dispatchers.IO) {
                // F-16: the alert prefs are a money-adjacent write (they drive
                // TP/SL notifications) — a silent failure looked like the
                // button did nothing.
                try { com.frit.app.features.trading.service.TradingEngineClient.saveNotifPrefs(prefs) }
                catch (e: Exception) { Diag.warn("Trading", "save_notif_prefs", e) }
            }
        }) { Text("Save alert prefs") }
        Spacer(Modifier.height(12.dp))
        Text("Background scan", fontWeight = FontWeight.Bold)
        PrefToggle("Auto-scan watchlist", bgScan) {
            bgScan = it
            if (it) TradingScanScheduler.schedule(ctx, interval.toIntOrNull() ?: 30)
            else TradingScanScheduler.cancel(ctx)
        }
        OutlinedTextField(interval, { interval = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Interval minutes (15–480)") })
        Button(onClick = {
            val m = (interval.toIntOrNull() ?: 30).coerceIn(15, 480)
            TradingCache.saveScanIntervalMin(m)
            if (bgScan) TradingScanScheduler.schedule(ctx, m)
        }) { Text("Save schedule") }
        Spacer(Modifier.height(12.dp))

        // ---- broker credentials (item 10a) ----
        Text("Broker account", fontWeight = FontWeight.Bold)
        Text("Trade on YOUR own Deriv account instead of the shared one, and store your MT5 " +
                "details for when the VPS bridge goes live. Credentials are stored on the server and never shown back in full.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        var account by remember { mutableStateOf(TradingAccount()) }
        var derivToken by remember { mutableStateOf("") }
        var useOwnDeriv by remember { mutableStateOf(false) }
        var mt5Server by remember { mutableStateOf("") }
        var mt5Login by remember { mutableStateOf("") }
        var mt5Password by remember { mutableStateOf("") }
        var mt5Broker by remember { mutableStateOf("") }
        var credMsg by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            scope.launch(Dispatchers.IO) {
                when (val r = TradingRepository.account()) {
                    is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                        account = r.value
                        useOwnDeriv = r.value.derivTokenMode == "own"
                        mt5Server = r.value.mt5Server ?: ""
                        mt5Login = r.value.mt5Login ?: ""
                        mt5Broker = r.value.mt5Broker ?: ""
                    }
                    is TradingRepository.Result.Err -> { }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use my own Deriv token", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (account.hasDerivToken) "A token is saved — bots will trade your account."
                    else "Bots trade the shared platform account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = useOwnDeriv, onCheckedChange = { useOwnDeriv = it })
        }
        if (useOwnDeriv) {
            OutlinedTextField(
                value = derivToken,
                onValueChange = { derivToken = it.trim() },
                label = { Text("Deriv API token") },
                singleLine = true
            )
        }
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                try {
                    val r = TradingRepository.saveAccount(
                        derivToken = if (derivToken.isNotBlank()) derivToken else null,
                        derivTokenMode = if (useOwnDeriv) "own" else "platform"
                    )
                    launch(Dispatchers.Main) {
                        credMsg = when (r) {
                            is TradingRepository.Result.Ok -> "Saved."
                            is TradingRepository.Result.Err -> r.message
                        }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { credMsg = e.message ?: "Could not save the Deriv settings" }
                }
            }
        }) { Text("Save Deriv settings") }

        Spacer(Modifier.height(8.dp))
        Text("MT5 account (for the VPS bridge)", fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(mt5Server, { mt5Server = it }, label = { Text("Server") }, singleLine = true)
        OutlinedTextField(mt5Login, { mt5Login = it }, label = { Text("Login") }, singleLine = true)
        OutlinedTextField(mt5Password, { mt5Password = it }, label = { Text("Password") }, singleLine = true)
        OutlinedTextField(mt5Broker, { mt5Broker = it }, label = { Text("Broker") }, singleLine = true)
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                try {
                    val r = TradingRepository.saveAccount(
                        mt5Server = mt5Server, mt5Login = mt5Login,
                        mt5Password = mt5Password.ifBlank { null }, mt5Broker = mt5Broker
                    )
                    launch(Dispatchers.Main) {
                        credMsg = when (r) {
                            is TradingRepository.Result.Ok -> "MT5 details saved."
                            is TradingRepository.Result.Err -> r.message
                        }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { credMsg = e.message ?: "Could not save the MT5 details" }
                }
            }
        }) { Text("Save MT5 details") }
        if (credMsg.isNotBlank()) {
            Text(credMsg, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text("Offline mode: portfolio, signals, watchlist, bot assignments, account balance and lessons are cached on every successful sync and stay readable without network.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrefToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}

private fun fmtN(v: Double): String = if (!v.isFinite()) "—" else v.toString()

private fun fmtMoney(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    return try { "%,.2f".format(v) } catch (_: Exception) { v.toString() }
}
