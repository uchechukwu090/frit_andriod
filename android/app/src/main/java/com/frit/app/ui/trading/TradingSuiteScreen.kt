package com.frit.app.ui.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    POCKET("Pocket"), PORTFOLIO("Portfolio"), RISK("Risk"), SENTIMENT("Sentiment"),
    AI("AI Help"), LEARN("Learn"), SETTINGS("Settings")
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
fun TradingSuiteScreen(onClose: () -> Unit, initialTab: String? = null) {
    val ctx = LocalContext.current
    val ui = rememberTradingUi()
    val scope = rememberCoroutineScope()
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
                listOf(TradingTab.DASHBOARD, TradingTab.WATCHLIST, TradingTab.SIGNALS, TradingTab.POCKET, TradingTab.PORTFOLIO, TradingTab.AI).forEach { t ->
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
                        TradingTab.POCKET -> PocketScreen(ui, scope)
                        TradingTab.PORTFOLIO -> PortfolioScreen(ui)
                        TradingTab.RISK -> RiskScreen(ui, scope)
                        TradingTab.SENTIMENT -> SentimentScreen(ui, scope)
                        TradingTab.AI -> AIAssistantScreen(ui, scope)
                        TradingTab.LEARN -> LearningScreen(ui, scope)
                        TradingTab.SETTINGS -> TradingSettingsScreen(ui, scope)
                    }
                }
            }
        }
    }
}

// ================= Notification Bar =================
@Composable
private fun NotificationBar(ui: TradingUiState, scope: CoroutineScope) {
    var notifications by remember { mutableStateOf<List<BotNotification>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.notifications(20)) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { notifications = r.value }
                is TradingRepository.Result.Err -> { }
            }
        }
    }

    if (notifications.isNotEmpty()) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clickable { expanded = !expanded }
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Notifications (${notifications.size})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (expanded) "Hide" else "Show",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (expanded) {
                    notifications.take(10).forEach { n ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    n.bot,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${n.symbol} ${n.decision}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${n.confidence}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tabIcon(t: TradingTab) = when (t) {
    TradingTab.DASHBOARD -> Icons.Filled.Dashboard
    TradingTab.WATCHLIST -> Icons.Filled.List
    TradingTab.SIGNALS -> Icons.Filled.Notifications
    TradingTab.POCKET -> Icons.Filled.Timer
    TradingTab.PORTFOLIO -> Icons.Filled.AccountBalanceWallet
    TradingTab.RISK -> Icons.Filled.Shield
    TradingTab.SENTIMENT -> Icons.Filled.Mood
    TradingTab.AI -> Icons.Filled.SmartToy
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
@Composable
private fun WatchlistScreen(ui: TradingUiState, scope: CoroutineScope) {
    var input by remember { mutableStateOf("") }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var upgradeMessage by remember { mutableStateOf("") }
    var selectedPair by remember { mutableStateOf<String?>(null) }
    var botPairs by remember { mutableStateOf<List<BotPairConfig>>(emptyList()) }
    var availableBots by remember { mutableStateOf<List<BotConfig>>(emptyList()) }

    fun loadBotPairs() {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.botPairs()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { botPairs = r.value }
                is TradingRepository.Result.Err -> { }
            }
        }
    }

    fun loadBots() {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.bots()) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { availableBots = r.value }
                is TradingRepository.Result.Err -> { }
            }
        }
    }

    LaunchedEffect(Unit) { loadBotPairs(); loadBots() }
    LaunchedEffect(selectedPair) { if (selectedPair != null) loadBotPairs() }

    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text("Upgrade Required") },
            text = { Text(upgradeMessage) },
            confirmButton = {
                TextButton(onClick = { showUpgradeDialog = false }) { Text("OK") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it.uppercase().trim() },
                label = { Text("Symbol (e.g. EURUSD, BTCUSD)") }, modifier = Modifier.weight(1f))
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
                            if (r.message.contains("upgrade", ignoreCase = true) || r.message.contains("plan", ignoreCase = true)) {
                                upgradeMessage = r.message
                                showUpgradeDialog = true
                            }
                        }
                    }
                }
            }) { Text("Add") }
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.watchlist, key = { it.symbol }) { w ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(w.symbol, fontWeight = FontWeight.Bold)
                                Text("${w.name} · ${w.assetClass.displayName}", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = w.isEnabled, onCheckedChange = { en ->
                                scope.launch(Dispatchers.IO) { TradingRepository.setWatchEnabled(w.symbol, en) }
                                ui.watchlist = ui.watchlist.map { if (it.symbol == w.symbol) it.copy(isEnabled = en) else it }
                            })
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { TradingRepository.removeWatch(w.symbol) }
                                ui.watchlist = ui.watchlist.filterNot { it.symbol == w.symbol }
                            }) { Icon(Icons.Filled.Delete, "Remove") }
                        }
                        Spacer(Modifier.height(4.dp))
                        val pairBots = botPairs.filter { it.pair == w.symbol }
                        if (pairBots.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                pairBots.forEach { bp ->
                                    FilterChip(selected = false, onClick = { selectedPair = w.symbol },
                                        label = { Text(bp.botName, style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.padding(end = 2.dp))
                                }
                            }
                        }
                        TextButton(onClick = { selectedPair = w.symbol }) {
                            Text("+ Add bot", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (selectedPair != null) {
        BotPairDialog(
            pair = selectedPair!!,
            botPairs = botPairs.filter { it.pair == selectedPair },
            availableBots = availableBots,
            onDismiss = { selectedPair = null },
            onSave = { botName, timeframe, tradeEnabled, signalEnabled ->
                scope.launch(Dispatchers.IO) {
                    when (val r = TradingRepository.saveBotPair(botName, selectedPair!!, timeframe, tradeEnabled, signalEnabled, 60, 2)) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                            loadBotPairs()
                            selectedPair = null
                        }
                        is TradingRepository.Result.Err -> launch(Dispatchers.Main) {
                            ui.error = r.message
                            if (r.message.contains("upgrade", ignoreCase = true) || r.message.contains("plan", ignoreCase = true)) {
                                upgradeMessage = r.message
                                showUpgradeDialog = true
                            }
                        }
                    }
                }
            },
            onRemove = { botName ->
                scope.launch(Dispatchers.IO) {
                    TradingRepository.removeBotPair(botName, selectedPair!!)
                    loadBotPairs()
                }
            }
        )
    }
}

@Composable
private fun BotPairDialog(
    pair: String,
    botPairs: List<BotPairConfig>,
    availableBots: List<BotConfig>,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, Boolean) -> Unit,
    onRemove: (String) -> Unit
) {
    var selectedBot by remember { mutableStateOf<BotConfig?>(null) }
    var timeframe by remember { mutableStateOf("5m") }
    var tradeEnabled by remember { mutableStateOf(false) }
    var signalEnabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bots for $pair") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Available bots:", fontWeight = FontWeight.Bold)
                availableBots.forEach { bot ->
                    FilterChip(
                        selected = selectedBot?.name == bot.name,
                        onClick = {
                            selectedBot = bot
                            timeframe = bot.allowedTimeframes.firstOrNull() ?: "5m"
                        },
                        label = { Text(bot.label) },
                        modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                    )
                }
                selectedBot?.let { bot ->
                    Spacer(Modifier.height(8.dp))
                    Text("Timeframe:", fontWeight = FontWeight.Bold)
                    bot.allowedTimeframes.forEach { tf ->
                        FilterChip(
                            selected = timeframe == tf,
                            onClick = { timeframe = tf },
                            label = { Text(tf) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trade", modifier = Modifier.weight(1f))
                        Switch(tradeEnabled, { tradeEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Signal", modifier = Modifier.weight(1f))
                        Switch(signalEnabled, { signalEnabled = it })
                    }
                }
                if (botPairs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Assigned bots:", fontWeight = FontWeight.Bold)
                    botPairs.forEach { bp ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${bp.botName} (${bp.timeframe})", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemove(bp.botName) }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedBot?.let { onSave(it.name, timeframe, tradeEnabled, signalEnabled) } },
                enabled = selectedBot != null
            ) { Text("Assign") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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

// ================= Portfolio: unified paper book, every trade bot-tagged =================
@Composable
private fun PortfolioScreen(ui: TradingUiState) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val r = com.frit.app.features.trading.service.TradingEngineClient.paperPortfolio()
                val list = mutableListOf<org.json.JSONObject>()
                r.optJSONArray("trades")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list += it }
                }
                val bots = mutableMapOf<String, org.json.JSONObject>()
                r.optJSONObject("byBot")?.let { o ->
                    for (k in o.keys()) o.optJSONObject(k)?.let { bots[k] = it }
                }
                launch(Dispatchers.Main) { ui.paperTrades = list; ui.paperByBot = bots }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { ui.error = e.message }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Paper portfolio — every bot, every trade tagged", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Paper while the MT5 bridge is unset. Pocket P&L is in payout units; the rest in $.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            (listOf("ALL") + ui.paperByBot.keys.sorted()).forEach { b ->
                FilterChip(selected = ui.paperBotFilter == b, onClick = { ui.paperBotFilter = b },
                    label = { Text(b) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            ui.paperByBot.filter { ui.paperBotFilter == "ALL" || it.key == ui.paperBotFilter }
                .forEach { (bot, s) ->
                    item(bot) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(bot.uppercase(), fontWeight = FontWeight.Bold)
                                Text("Trades ${s.optInt("trades")} · W ${s.optInt("wins")} · L ${s.optInt("losses")} · $${"%.2f".format(s.optDouble("pnlUsd"))} · ${"%.2f".format(s.optDouble("pnlUnits"))}u",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            val shown = ui.paperTrades.filter { ui.paperBotFilter == "ALL" || it.optString("bot") == ui.paperBotFilter }
            if (shown.isEmpty()) item { Text("No executions yet — saved bots paper-trade here once they fire.") }
            items(shown.size) { i ->
                val t = shown[i]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
                                Text(t.optString("bot"), color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("${t.optString("side")} ${t.optString("symbol")}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(t.optString("status"), style = MaterialTheme.typography.bodySmall)
                        }
                        val pnl = t.optDouble("pnl", 0.0)
                        val unit = if (t.optString("pnlKind") == "units") "u" else "$"
                        Text("Entry ${t.optDouble("entry")} → ${t.optDouble("exit")} · P&L $unit${"%.2f".format(pnl)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pnl >= 0) FritPositive else FritNegative)
                        if (t.optString("note").isNotBlank()) Text(t.optString("note"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ================= Risk =================
@Composable
private fun RiskScreen(ui: TradingUiState, scope: CoroutineScope) {
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
        Text("Position sizing (1% rule)", fontWeight = FontWeight.Bold)
        var balance by remember { mutableStateOf("1000") }
        var riskPct by remember { mutableStateOf("1") }
        OutlinedTextField(balance, { balance = it }, label = { Text("Balance") })
        OutlinedTextField(riskPct, { riskPct = it }, label = { Text("Risk % per trade") })
        val cash = (balance.toDoubleOrNull() ?: 0.0) * (riskPct.toDoubleOrNull() ?: 1.0) / 100.0
        Text("Max loss per trade: $cash")
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

// ================= AI assistant =================
@Composable
private fun AIAssistantScreen(ui: TradingUiState, scope: CoroutineScope) {
    var profile by remember { mutableStateOf(TradingCache.loadRiskProfile()) }
    var capital by remember { mutableStateOf("10000") }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Smart recommendations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Based on your risk profile + live engine regimes. Never risk more than 2% per trade.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("CONSERVATIVE", "MODERATE", "AGGRESSIVE").forEach { p ->
                FilterChip(selected = profile == p, onClick = { profile = p; TradingCache.saveRiskProfile(p) },
                    label = { Text(p.take(4)) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        OutlinedTextField(capital, { capital = it }, label = { Text("Capital") })
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                when (val r = TradingRepository.recommendations(profile, capital.toDoubleOrNull() ?: 10000.0)) {
                    is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { ui.recommendations = r.value }
                    is TradingRepository.Result.Err -> launch(Dispatchers.Main) { ui.error = r.message }
                }
            }
        }) { Text("Get recommendations") }
        ui.recommendations.forEach { rec ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${rec.action.name} ${rec.symbol} (${rec.confidence}%)", fontWeight = FontWeight.Bold)
                    Text("Entry ${fmtN(rec.entry)} · SL ${fmtN(rec.sl)} · TP ${fmtN(rec.tp1)}/${fmtN(rec.tp2)} · RR ${rec.rr}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Size: ${rec.positionSize} lots · Regime: ${rec.regime ?: "—"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(rec.reasoning.take(220), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ================= Trading bots (scalping / pocket / intraday / long-term / options) =================
// Bot-pair association is now managed per-pair in the Watchlist screen.
// This section is removed from AI Help — bots are assigned to pairs directly.
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
        Text("Offline mode: portfolio, signals, watchlist, sentiment and lessons are cached on every successful sync and stay readable without network.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ================= Pocket fixed-expiry (manual execution) =================
@Composable
private fun PocketScreen(ui: TradingUiState, scope: CoroutineScope) {
    // Pocket pairs come from the POCKET watchlist — a separate list from the
    // main trading watchlist, so the two never interfere.
    var pocketPairs by remember { mutableStateOf(listOf<String>()) }
    var pair by remember { mutableStateOf("") }
    var botOn by remember { mutableStateOf(true) }
    var botBusy by remember { mutableStateOf(false) }
    var customPair by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(5) }
    var decision by remember { mutableStateOf("…") }
    var confidence by remember { mutableStateOf(0) }
    var reasons by remember { mutableStateOf(listOf<String>()) }
    var payout by remember { mutableStateOf(0.8) }
    var statsText by remember { mutableStateOf("Loading stats…") }
    var historyText by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    // Load the POCKET watchlist (not the main one) and adopt its first pair.
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val wl = com.frit.app.features.trading.service.TradingEngineClient.pocketWatchlist()
                val list = mutableListOf<String>()
                wl.optJSONArray("items")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("symbol")?.let { list += it }
                }
                launch(Dispatchers.Main) {
                    pocketPairs = list
                    if (pair.isBlank()) list.firstOrNull()?.let { pair = it }
                }
            } catch (_: Exception) { }
        }
    }
    // Read the server-side bot switch.
    LaunchedEffect(Unit) {
        try {
            val on = com.frit.app.features.trading.service.TradingEngineClient.pocketEnabled()
            botOn = on
        } catch (_: Exception) { }
    }

    fun refresh() {
        if (pair.isBlank()) { decision = "—"; confidence = 0; reasons = emptyList(); return }
        scope.launch(Dispatchers.IO) {
            try {
                val s = com.frit.app.features.trading.service.TradingEngineClient.pocketSignal(pair, expiry)
                val sig = s.optJSONObject("signal") ?: org.json.JSONObject()
                launch(Dispatchers.Main) {
                    decision = sig.optString("decision", "WAIT")
                    confidence = sig.optInt("confidence", 0)
                    payout = s.optDouble("payoutAssumed", 0.8)
                    val arr = sig.optJSONArray("reasons")
                    reasons = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
                }
                val st = com.frit.app.features.trading.service.TradingEngineClient.pocketStats(pair)
                launch(Dispatchers.Main) {
                    val wr = st.optDouble("winRate", Double.NaN)
                    val ev = st.optDouble("expectancy", Double.NaN)
                    statsText = "Trades: ${st.optInt("trades", 0)} · Win: " +
                        (if (wr.isNaN()) "—" else "${(wr * 100).toInt()}%") +
                        " · EV: " + (if (ev.isNaN()) "—" else "%.3f".format(ev)) +
                        "\n${st.optString("verdict", "")}"
                }
                val h = com.frit.app.features.trading.service.TradingEngineClient.pocketHistory(pair, 10)
                launch(Dispatchers.Main) {
                    val arr = h.optJSONArray("trades")
                    historyText = if (arr == null || arr.length() == 0) "No paper trades yet."
                    else (0 until arr.length()).joinToString("\n") { i ->
                        val t = arr.optJSONObject(i)
                        "${t.optString("direction")} ${t.optString("symbol")} @ ${t.optDouble("entry_price")} -> ${t.optString("result")}"
                    }
                }
                launch(Dispatchers.Main) { err = null }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { err = e.message }
            }
        }
    }
    LaunchedEffect(pair, expiry) { refresh() }

    val boundary = expiry * 60_000L
    val msLeft = boundary - (nowMs % boundary)
    val mmss = "%d:%02d".format(msLeft / 60000, (msLeft % 60000) / 1000)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pocket signals — fixed expiry (Pro plan+)", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(if (botOn) "Bot ON" else "Bot OFF", style = MaterialTheme.typography.bodySmall,
                color = if (botOn) FritPositive else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Switch(checked = botOn, enabled = !botBusy, onCheckedChange = { want ->
                val prev = botOn
                botOn = want; botBusy = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val actual = com.frit.app.features.trading.service.TradingEngineClient.setPocketEnabled(want)
                        launch(Dispatchers.Main) { botOn = actual; botBusy = false; err = null }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { botOn = prev; botBusy = false; err = e.message }
                    }
                }
            })
        }
        if (!botOn) Text("Pocket bot is OFF — no paper trades are logged and no auto-execution fires for you. You can still read signals manually.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Expiry: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
            listOf(1, 5, 15).forEach { m ->
                FilterChip(selected = expiry == m, onClick = { expiry = m },
                    label = { Text("${m}m") }, modifier = Modifier.padding(end = 4.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ))
            }
        }
        Text("Manual execution only. Enter at the next candle open, exit at countdown end. Paper-tracked; broker OTC price may differ from our feed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = customPair, onValueChange = { customPair = it.uppercase().trim() },
                label = { Text("Any pair") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                if (customPair.isNotBlank()) { pair = customPair; customPair = "" }
            }) { Text("Analyze") }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val s = customPair.uppercase().trim()
                if (s.isBlank()) return@Button
                scope.launch(Dispatchers.IO) {
                    try {
                        com.frit.app.features.trading.service.TradingEngineClient.addPocketWatch(s, expiry)
                        launch(Dispatchers.Main) {
                            pocketPairs = (pocketPairs + s).distinct()
                            pair = s; customPair = ""
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { err = e.message }
                    }
                }
            }) { Text("+ Add") }
        }
        if (pocketPairs.isEmpty()) {
            Text("No pocket pairs yet — add one above.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pocketPairs.forEach { s ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = pair == s, onClick = { pair = s },
                    label = { Text(s) }, modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ))
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            com.frit.app.features.trading.service.TradingEngineClient.removePocketWatch(s)
                            launch(Dispatchers.Main) {
                                pocketPairs = pocketPairs.filterNot { it == s }
                                if (pair == s) pair = pocketPairs.firstOrNull() ?: ""
                            }
                        } catch (e: Exception) {
                            launch(Dispatchers.Main) { err = e.message }
                        }
                    }
                }) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(decision, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                    color = when (decision) {
                        "CALL" -> FritPositive
                        "PUT" -> FritNegative
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                Text(if (pair.isBlank()) "No pair selected — type one above and tap Analyze" else "$pair · $confidence% · payout ${(payout * 100).toInt()}%")
                Text("Next expiry window closes in $mmss", fontWeight = FontWeight.Bold)
                reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Scoreboard (needs ${(1 / (1 + payout) * 100).toInt()}% wins to break even)", fontWeight = FontWeight.Bold)
        Text(statsText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Recent paper trades", fontWeight = FontWeight.Bold)
        Text(historyText, style = MaterialTheme.typography.bodySmall)
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
