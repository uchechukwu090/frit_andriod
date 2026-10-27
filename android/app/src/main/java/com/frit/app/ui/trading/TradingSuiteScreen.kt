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
    // Performance recommendations across FX, Crypto, Stocks, Indices, Synthetics
    var marketRecommendations by mutableStateOf<List<MarketRecommendation>>(emptyList())
    var selectedRecMarket by mutableStateOf("ALL")
    var selectedRecType by mutableStateOf("ALL")
    var selectedRecommendation by mutableStateOf<MarketRecommendation?>(null)
    // Economic Forex News Calendar & Historical Analysis
    var upcomingNews by mutableStateOf<List<MarketNewsItem>>(emptyList())
    var previousNews by mutableStateOf<List<MarketNewsItem>>(emptyList())
    var newsMode by mutableStateOf("UPCOMING") // UPCOMING or PREVIOUS
    var selectedNewsImpact by mutableStateOf("ALL")
    var selectedNewsItem by mutableStateOf<MarketNewsItem?>(null)
    // Recent signals gotten across the system
    var recentSignalsList by mutableStateOf<List<TradingSignal>>(emptyList())

    fun refreshRecommendations(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.performanceRecommendations(selectedRecMarket, selectedRecType)) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { marketRecommendations = r.value }
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) { error = r.message }
            }
        }
    }

    fun refreshNews(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            when (val r = TradingRepository.newsCalendar(selectedNewsImpact)) {
                is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                    upcomingNews = r.value.first
                    previousNews = r.value.second
                }
                is TradingRepository.Result.Err -> launch(Dispatchers.Main) { error = r.message }
            }
        }
    }

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
                if (what == "all" || what == "dashboard" || what == "recommendations") {
                    when (val r = TradingRepository.performanceRecommendations(selectedRecMarket, selectedRecType)) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { marketRecommendations = r.value }
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
                    when (val r = TradingRepository.newsCalendar(selectedNewsImpact)) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) {
                            upcomingNews = r.value.first
                            previousNews = r.value.second
                        }
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
                    when (val r = TradingRepository.recentSignals(30)) {
                        is TradingRepository.Result.Ok -> launch(Dispatchers.Main) { recentSignalsList = r.value }
                        is TradingRepository.Result.Err -> setErr(r.message)
                    }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(ui: TradingUiState, scope: CoroutineScope) {
    val ctx = LocalContext.current

    // Dialog for recommendation details
    ui.selectedRecommendation?.let { rec ->
        AlertDialog(
            onDismissRequest = { ui.selectedRecommendation = null },
            confirmButton = {
                TextButton(onClick = { ui.selectedRecommendation = null }) { Text("Close") }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${rec.symbol}  ·  ${rec.grade}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = when {
                            rec.action.contains("BUY") || rec.action.contains("RISE") -> FritPositive.copy(alpha = 0.18f)
                            rec.action.contains("SELL") || rec.action.contains("FALL") -> FritNegative.copy(alpha = 0.18f)
                            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = rec.action,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                rec.action.contains("BUY") || rec.action.contains("RISE") -> FritPositive
                                rec.action.contains("SELL") || rec.action.contains("FALL") -> FritNegative
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rec.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Performance Score", style = MaterialTheme.typography.bodySmall)
                        Text("${rec.performanceScore}/100", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("24h Return / Bias", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${if (rec.return24h >= 0) "+" else ""}${"%.2f".format(rec.return24h)}%",
                            fontWeight = FontWeight.Bold,
                            color = if (rec.return24h >= 0) FritPositive else FritNegative
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Win Rate Edge", style = MaterialTheme.typography.bodySmall)
                        Text("~${rec.winRateEst}% (backtested)", fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("RSI (14)", style = MaterialTheme.typography.bodySmall)
                        Text("${rec.rsi}", fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Risk / Reward", style = MaterialTheme.typography.bodySmall)
                        Text("1 : ${"%.1f".format(rec.rr)}", fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    if (rec.isSynthetic) {
                        Text("Synthetic Contract", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text("Recommended Contract: ${rec.preferredContract ?: "Rise/Fall"}", style = MaterialTheme.typography.bodySmall)
                        Text("Engine Horizon: ${rec.horizon}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Trade Execution Setup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        Text("Entry: ${rec.entry}   |   SL: ${rec.sl}", style = MaterialTheme.typography.bodySmall)
                        Text("Target 1 (TP): ${rec.tp1}   |   Target 2: ${rec.tp2}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Quantitative Analysis", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rec.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        )
    }

    // Dialog for news details
    ui.selectedNewsItem?.let { item ->
        AlertDialog(
            onDismissRequest = { ui.selectedNewsItem = null },
            confirmButton = { TextButton(onClick = { ui.selectedNewsItem = null }) { Text("Close") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = item.currency,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Impact Level", style = MaterialTheme.typography.bodySmall)
                        Text(
                            item.impact,
                            fontWeight = FontWeight.Bold,
                            color = when (item.impact.uppercase()) {
                                "HIGH" -> FritNegative
                                "MEDIUM" -> Color(0xFFB8860B)
                                else -> FritPositive
                            }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Release Date & Time", style = MaterialTheme.typography.bodySmall)
                        Text(item.date.take(16).replace("T", " "), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Forecast: ${item.forecast}", style = MaterialTheme.typography.bodySmall)
                        Text("Previous: ${item.previous}", style = MaterialTheme.typography.bodySmall)
                        item.actual?.let { Text("Actual: $it", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (item.outcome != null || item.marketAnalysis != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Text("Economic Reaction & Market Impact", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        item.outcome?.let {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Consensus Outcome", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    it,
                                    fontWeight = FontWeight.Bold,
                                    color = if (it == "BEAT") FritPositive else if (it == "MISSED") FritNegative else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        item.bias?.let {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Market Sentiment Bias", style = MaterialTheme.typography.bodySmall)
                                Text(it, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item.volatilityRating?.let {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Volatility Observed", style = MaterialTheme.typography.bodySmall)
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item.marketAnalysis?.let {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                    if (item.affectedPairs.isNotEmpty()) {
                        Text("Affected Currency Pairs / Instruments:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                        Text(item.affectedPairs.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Header Controls ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FRIT Market Dashboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Multi-Market Intelligence, Economic News & Signals", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ui.load(scope, "dashboard") }) {
                    Icon(Icons.Filled.Refresh, "Refresh All")
                }
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            launch(Dispatchers.Main) { ui.loading = true }
                            when (val r = TradingRepository.scanWatchlist()) {
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
                    },
                    modifier = Modifier.padding(start = 4.dp)
                ) { Text(if (ui.loading) "Scanning…" else "Scan Now") }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        // ================= SECTION 1: PERFORMANCE RECOMMENDATIONS =================
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Performance Recommendations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "QUANTITATIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        "Ranked strictly by current performance & momentum across FX, Crypto, Stocks, Indices, and Synthetics (Rise & Fall) — not random picking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    // Market Filter Chips
                    val markets = listOf("ALL", "FX", "CRYPTO", "STOCKS", "INDICES", "SYNTHETICS")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        markets.forEach { m ->
                            val sel = ui.selectedRecMarket == m
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    ui.selectedRecMarket = m
                                    ui.refreshRecommendations(scope)
                                },
                                label = {
                                    Text(
                                        when (m) {
                                            "FX" -> "Forex (FX)"
                                            "SYNTHETICS" -> "Synthetics (Rise/Fall)"
                                            else -> m
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }

                    // Type Filter Chips
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ALL" to "All Types", "TRADE" to "Trades", "INVEST" to "Investments").forEach { (k, label) ->
                            val sel = ui.selectedRecType == k
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    ui.selectedRecType = k
                                    ui.refreshRecommendations(scope)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        // List of recommendations
        val recs = ui.marketRecommendations
        if (recs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Loading performance recommendations across all markets…", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { ui.refreshRecommendations(scope) }) { Text("Load Recommendations") }
                    }
                }
            }
        } else {
            items(recs.take(10), key = { "${it.symbol}_${it.market}" }) { rec ->
                Card(
                    onClick = { ui.selectedRecommendation = rec },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    rec.market,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            // Action badge
                            Surface(
                                color = when {
                                    rec.action.contains("BUY") || rec.action.contains("RISE") -> FritPositive.copy(alpha = 0.16f)
                                    rec.action.contains("SELL") || rec.action.contains("FALL") -> FritNegative.copy(alpha = 0.16f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                },
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    rec.action,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        rec.action.contains("BUY") || rec.action.contains("RISE") -> FritPositive
                                        rec.action.contains("SELL") || rec.action.contains("FALL") -> FritNegative
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    rec.grade,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Metrics row
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Score ${rec.performanceScore}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "${if (rec.return24h >= 0) "+" else ""}${"%.2f".format(rec.return24h)}%",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rec.return24h >= 0) FritPositive else FritNegative
                            )
                            Text("RSI ${rec.rsi}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Win ~${rec.winRateEst}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("R:R 1:${"%.1f".format(rec.rr)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Quantitative reasoning snippet
                        Text(
                            rec.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )

                        // Trade levels
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (rec.isSynthetic) {
                                Text("Synthetic Rise/Fall  ·  ${rec.horizon}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("Entry: ${rec.entry}   SL: ${rec.sl}   TP: ${rec.tp1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("Details →", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ================= SECTION 2: FOREX NEWS CALENDAR & ANALYSIS =================
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Economic News Calendar & Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "CALENDAR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        "Forex economic calendar with impact classification levels and analytical post-release breakdown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    // Upcoming vs Previous Mode Toggle
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { ui.newsMode = "UPCOMING" },
                            colors = if (ui.newsMode == "UPCOMING") ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Upcoming (${ui.upcomingNews.size})", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { ui.newsMode = "PREVIOUS" },
                            colors = if (ui.newsMode == "PREVIOUS") ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Previous & Analyzed (${ui.previousNews.size})", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Impact Level Filter Chips
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "ALL" to "All Impacts",
                            "HIGH" to "🔴 High Impact",
                            "MEDIUM" to "🟠 Medium",
                            "LOW" to "🟢 Low"
                        ).forEach { (code, label) ->
                            val sel = ui.selectedNewsImpact == code
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    ui.selectedNewsImpact = code
                                    ui.refreshNews(scope)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        // News items list depending on selected mode
        val newsList = if (ui.newsMode == "UPCOMING") ui.upcomingNews else ui.previousNews
        if (newsList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Loading economic news calendar...", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { ui.refreshNews(scope) }) { Text("Load News") }
                    }
                }
            }
        } else {
            items(newsList.take(8), key = { it.id }) { news ->
                Card(
                    onClick = { ui.selectedNewsItem = news },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Currency tag
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    news.currency,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                news.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            // Impact Badge
                            Surface(
                                color = when (news.impact.uppercase()) {
                                    "HIGH" -> FritNegative.copy(alpha = 0.16f)
                                    "MEDIUM" -> Color(0xFFB8860B).copy(alpha = 0.16f)
                                    else -> FritPositive.copy(alpha = 0.16f)
                                },
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    news.impact,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when (news.impact.uppercase()) {
                                        "HIGH" -> FritNegative
                                        "MEDIUM" -> Color(0xFFB8860B)
                                        else -> FritPositive
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Figures & Timing row
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Forecast: ${news.forecast}   |   Previous: ${news.previous}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                fmtTimeAgo(news.time),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // For Previous news: show analytical breakdown block
                        if (!news.isUpcoming && (news.actual != null || news.marketAnalysis != null)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Actual: ${news.actual ?: "-"}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        news.outcome?.let {
                                            Surface(
                                                color = if (it == "BEAT") FritPositive.copy(alpha = 0.2f) else if (it == "MISSED") FritNegative.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (it == "BEAT") FritPositive else if (it == "MISSED") FritNegative else MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    news.bias?.let {
                                        Text("Market Sentiment: $it", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                                    }
                                    news.marketAnalysis?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    if (news.affectedPairs.isNotEmpty()) {
                                        Text("Affected: ${news.affectedPairs.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        } else if (news.affectedPairs.isNotEmpty()) {
                            Text(
                                "Watch pairs: ${news.affectedPairs.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // ================= SECTION 3: RECENT SIGNALS GOTEN FEED =================
        val signalsToShow = if (ui.recentSignalsList.isNotEmpty()) ui.recentSignalsList else ui.signals
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent Signals Feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${signalsToShow.size} signals", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Preserved list of the most recent confirmed signals generated across all engines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        if (signalsToShow.isEmpty()) {
            item {
                Text(
                    "No signals recorded yet — tap 'Scan Now' above or enable pairs in Watchlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        } else {
            items(signalsToShow.take(12), key = { it.id }) { s ->
                Card(
                    onClick = {
                        ui.detailSignal = s
                        TradingRepository.apply { scope.launch(Dispatchers.IO) { markRead(s.id) } }
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = when (s.decision) {
                                    SignalDecision.BUY, SignalDecision.CALL -> FritPositive.copy(alpha = 0.16f)
                                    SignalDecision.SELL, SignalDecision.PUT -> FritNegative.copy(alpha = 0.16f)
                                    else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                },
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    s.decision.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when (s.decision) {
                                        SignalDecision.BUY, SignalDecision.CALL -> FritPositive
                                        SignalDecision.SELL, SignalDecision.PUT -> FritNegative
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                fmtTimeAgo(s.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Entry: ${s.entry}   SL: ${s.sl}   TP: ${s.tp1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("R:R 1:${"%.1f".format(s.rr)}  ·  Conf ${s.confidence}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (s.reasoning.isNotBlank()) {
                            Text(
                                s.reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
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
    // Lot sizing: AUTO = the lot-size engine picks every lot (Kelly vs balance
    // + risk profile), FIXED = the user's own lot, used exactly as entered.
    var autoLot by remember { mutableStateOf(true) }
    var lotInput by remember { mutableStateOf("0.01") }
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
                    autoLot = r.value.lotMode != "FIXED"
                    lotInput = fmtLot(r.value.fixedLot)
                }
                is TradingRepository.Result.Err -> {
                    val cached = TradingCache.loadAccount()
                    if (cached != null) launch(Dispatchers.Main) {
                        account = cached
                        balanceInput = if (cached.balance > 0) cached.balance.toLong().toString() else ""
                        autoLot = cached.lotMode != "FIXED"
                        lotInput = fmtLot(cached.fixedLot)
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
                        Text("Used to size every trade. Saved to your account.",
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

                // ---- positions per signal ----
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Positions per signal", fontWeight = FontWeight.Bold)
                        Text("How many entries to open each time a signal fires.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { n ->
                            FilterChip(
                                selected = account.positionsPerSignal == n,
                                onClick = {
                                    gated { TradingRepository.saveAccount(positionsPerSignal = n) }
                                    account = account.copy(positionsPerSignal = n)
                                },
                                label = { Text("$n", style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }

                // ---- lot size: engine-chosen vs the user's own lot ----
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoLot,
                        onCheckedChange = { on ->
                            autoLot = on
                            val lot = lotInput.toDoubleOrNull()?.coerceIn(0.01, 100.0)
                            gated {
                                TradingRepository.saveAccount(
                                    lotMode = if (on) "AUTO" else "FIXED",
                                    fixedLot = lot ?: account.fixedLot.coerceIn(0.01, 100.0)
                                )
                            }
                            account = account.copy(
                                lotMode = if (on) "AUTO" else "FIXED",
                                fixedLot = (lot ?: account.fixedLot).coerceIn(0.01, 100.0)
                            )
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Let the bot choose the lot size",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (autoLot)
                                "Lot size engine on — every trade is sized from your balance, " +
                                    "risk profile and the setup's stop distance."
                            else "Auto sizing off — every bot trades ${fmtLot(account.fixedLot)} lot " +
                                "(the per-trade risk guard still applies).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!autoLot) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0.01, 0.05, 0.1, 0.25, 0.5, 1.0).forEach { preset ->
                            FilterChip(
                                selected = lotInput.toDoubleOrNull() == preset,
                                onClick = { lotInput = fmtLot(preset) },
                                label = { Text(fmtLot(preset), style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = lotInput,
                            onValueChange = { lotInput = sanitizeLot(it) },
                            label = { Text("Lot size") },
                            singleLine = true,
                            isError = !isValidLot(lotInput),
                            supportingText = {
                                if (!isValidLot(lotInput)) Text("0.01 – 100, up to 2 decimals")
                            },
                            modifier = Modifier.width(160.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val lot = lotInput.toDoubleOrNull() ?: return@Button
                                gated { TradingRepository.saveAccount(lotMode = "FIXED", fixedLot = lot) }
                                account = account.copy(lotMode = "FIXED", fixedLot = lot)
                            },
                            enabled = isValidLot(lotInput)
                        ) { Text("Save lot size") }
                    }
                }
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
                                scope.launch(Dispatchers.IO) {
                                    TradingRepository.removeWatch(w.symbol)
                                    // Also drop any cached signals for this symbol so they
                                    // stop appearing immediately, not just after the next scan.
                                    TradingCache.saveSignals(TradingCache.loadSignals().filterNot { it.symbol == w.symbol.uppercase() })
                                }
                                ui.watchlist = ui.watchlist.filterNot { it.symbol == w.symbol }
                                botPairs = botPairs.filterNot { it.pair == w.symbol }
                                ui.signals = ui.signals.filterNot { it.symbol == w.symbol.uppercase() }
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
                                                    minConfidence = 60, maxTradesDay = 2,
                                                    positionsPerSignal = 1
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
                                                        bp.minConfidence, bp.maxTradesDay,
                                                        bp.positionsPerSignal
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
                                                    bp.minConfidence, bp.maxTradesDay,
                                                    bp.positionsPerSignal
                                                )
                                            }
                                            refreshBotPairs()
                                        })
                                }
                                // Per-signal position count for this bot-pair.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Positions/signal", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 4.dp).weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        (1..3).forEach { n ->
                                            FilterChip(
                                                selected = bp.positionsPerSignal == n,
                                                onClick = {
                                                    gated {
                                                        TradingRepository.saveBotPair(
                                                            bp.botName, w.symbol, bp.timeframe,
                                                            bp.tradeEnabled, bp.signalEnabled,
                                                            bp.minConfidence, bp.maxTradesDay,
                                                            n
                                                        )
                                                    }
                                                    refreshBotPairs()
                                                },
                                                label = { Text("$n", style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.height(24.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    if (bp.tradeEnabled)
                                        "Trading + signalling on ${bp.timeframe} — " +
                                            if (account.lotMode == "FIXED")
                                                "fixed ${fmtLot(account.fixedLot)} lot per trade, " +
                                                "${bp.positionsPerSignal} position(s) per signal."
                                            else "sized by the lot engine (Kelly) against your balance, " +
                                                "${bp.positionsPerSignal} position(s) per signal."
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

// ---- lot-size helpers ----
// Retail MT5/FBS lots are 0.01-step, so the field accepts at most 2 decimals.
private const val MIN_LOT = 0.01
private const val MAX_LOT = 100.0

private fun fmtLot(v: Double): String =
    if (!v.isFinite() || v <= 0) "0.01"
    else if (v == v.toLong().toDouble()) v.toLong().toString()
    else "%.2f".format(v).trimEnd('0').trimEnd('.')

private fun sanitizeLot(s: String): String {
    val cleaned = s.filter { it.isDigit() || it == '.' }.take(6)
    val firstDot = cleaned.indexOf('.')
    if (firstDot < 0) return cleaned
    return cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "").take(2)
}

private fun isValidLot(s: String): Boolean {
    val v = s.toDoubleOrNull() ?: return false
    return v in MIN_LOT..MAX_LOT
}

private fun fmtMoney(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    return try { "%,.2f".format(v) } catch (_: Exception) { v.toString() }
}

private fun fmtTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) {
        val future = -diff
        val mins = future / 60000
        val hrs = mins / 60
        val days = hrs / 24
        return when {
            days > 0 -> "In ${days}d ${hrs % 24}h"
            hrs > 0 -> "In ${hrs}h ${mins % 60}m"
            mins > 0 -> "In ${mins}m"
            else -> "Imminent"
        }
    }
    val mins = diff / 60000
    val hrs = mins / 60
    val days = hrs / 24
    return when {
        days > 0 -> "${days}d ago"
        hrs > 0 -> "${hrs}h ago"
        mins > 0 -> "${mins}m ago"
        else -> "Just now"
    }
}
