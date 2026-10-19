# FRIT AI-Powered Trading Suite - Implementation Plan

## Overview
Build a comprehensive mobile trading app with AI integration leveraging the existing sophisticated server-side trading engine (MTFStrategyEngine) and Android app framework.

## Live feed (Oct 2026) — 24/7 WS scanning, see docs/FEED.md
`server/src/strategy/feed/`: CandleStore (local 30M/4H rings) + Binance WS (crypto, keyless)
+ Finnhub WS (forex/metals/stocks via FINNHUB_KEY — the OANDA replacement for NG)
+ Dukascopy poll (keyless backup, often bot-blocked from hosts) + LiveFeed manager
(backfill budget, candle-close scans, per-user fan-out with anti-spam). Engine seam:
`analyze(symbol, {candles30M, candles4H})`. Always-on hosting required for true 24/7.

## Architecture

### Server-Side (Node.js/Express) - Extend Existing
- **Trading Engine**: MTFStrategyEngine (already exists - 30M/4H, trend/pullback/range, Fib zones, cost model)
- **Risk Management**: PortfolioRisk (already exists - position limits, correlation, kill-switch)
- **Position Tracking**: PositionMonitor + PullbackJournal (already exists)
- **New API Endpoints Needed**:
  - `/api/trading/scan` - Scan watchlist pairs for signals
  - `/api/trading/watchlist` - CRUD for user watchlist
  - `/api/trading/signals/history` - Signal history with favorite/delete
  - `/api/trading/portfolio` - Portfolio P&L, positions, risk metrics
  - `/api/trading/sentiment` - AI sentiment analysis (cached daily)
  - `/api/trading/ai/explain` - Explainable AI for signal reasoning
  - `/api/trading/ai/recommendations` - Smart recommendations based on risk profile
  - `/api/trading/learning` - Learning hub content
  - `/api/trading/notifications` - Push notification registration

### Android App (Kotlin/Jetpack Compose) - New Trading Module
**New Screens**:
1. **TradingDashboard** - Overview with market cards, active signals, portfolio snapshot
2. **WatchlistScreen** - Add/remove pairs, real-time prices, quick analyze
3. **SignalsScreen** - Signal history with filter, favorite, delete, detail view
4. **SignalDetailScreen** - Full signal with entry/SL/TP, reasoning, chart
5. **PortfolioScreen** - Positions, P&L, risk metrics, allocation
6. **RiskManagementScreen** - Position sizing, correlation, daily risk
7. **SentimentScreen** - AI sentiment for watchlist pairs
8. **AIAssistantScreen** - Explainable AI, recommendations, Q&A
9. **LearningHubScreen** - Tutorials, strategy guides, market explainers
10. **SettingsTradingScreen** - Notification prefs, risk profile, offline sync

**Core Services**:
- `TradingEngineClient` - API communication with offline cache
- `SignalNotificationManager` - FCM/local notifications for signals/TP/SL
- `TradingCache` - Room database for offline mode
- `TradingRepository` - Unified data layer

## Implementation Phases

### Phase 1: Server API Extensions (Week 1) — DONE (Oct 2026)
- [x] Add trading routes to server/index.js (+ POST /api/trading/alerts/check for TP/SL/entry touches)
- [x] Implement watchlist CRUD with user isolation (fixed PATCH param-order bug)
- [x] Build signal scanning endpoint using MTFStrategyEngine (fixed formatTradingSignal mapping: named scenarios, entry_ctx, tp_basis, regime; stores BUY/SELL/WAIT_PULLBACK/WAIT_RANGE only)
- [x] Add sentiment analysis endpoint (memory + SQLite daily cache, 7-day prune)
- [x] Create portfolio aggregation endpoint
- [x] Add explainable AI endpoint
- [x] Implement push notification registration

### Phase 2: Android Trading Core (Week 2) — DONE (Oct 2026, no new deps: OkHttp + SharedPreferences cache)
- [x] Create TradingEngineClient (OkHttp + org.json, all /api/trading/* endpoints)
- [x] Build TradingRepository pattern (network-first, cache-fallback)
- [x] Implement SignalNotificationManager (framework channels, no compat lib)
- [x] Create base trading UI components (SignalCard etc. in TradingSuiteScreen.kt)
- [x] Build TradingDashboard screen (+ Scan now)
- [x] Build WatchlistScreen with add/remove/enable toggle

### Phase 3: Signal System & Notifications (Week 3) — DONE
- [x] SignalsScreen with history, favourites filter, favourite, delete
- [x] SignalDetailScreen with full analysis + AI explain
- [x] Local notification system (FCM token registration kept server-side for future)
- [x] TP/SL/entry-touch notifications via /api/trading/alerts/check
- [x] Background signal scanning (AlarmManager + TradingScanReceiver, boot re-arm)

### Phase 4: Portfolio & Risk Management (Week 4) — DONE (except allocation chart + correlation viz)
- [x] PortfolioScreen with P&L, positions
- [x] RiskManagementScreen with sizing calculator + live risk JSON (kill-switch, exposure)
- [ ] Correlation visualization
- [ ] AI rebalancing suggestions

### Phase 5: AI Features (Week 5) — DONE
- [x] SentimentScreen with news/social analysis (daily cached)
- [x] AIAssistantScreen with explainable AI
- [x] Smart recommendations based on risk profile
- [x] LearningHubScreen with curated content (cached offline)

### Phase 6: Offline Mode & Polish (Week 6) — DONE (except perf testing)
- [x] Offline-first architecture (TradingCache JSON mirror, 24h+ usable)
- [x] Background sync (offline ops queue + replay on next sync)
- [x] Cached portfolio/view
- [ ] Performance optimization + comprehensive testing (Gradle build + device run still required)

## Data Models

### Watchlist Item
```kotlin
data class WatchlistItem(
    val symbol: String,
    val name: String,
    val assetClass: AssetClass, // FOREX, CRYPTO, STOCKS, COMMODITIES
    val isEnabled: Boolean,
    val sortOrder: Int
)
```

### Trading Signal (matches engine output)
```kotlin
data class TradingSignal(
    val id: String,
    val symbol: String,
    val decision: SignalDecision, // BUY, SELL, WAIT, WAIT_PULLBACK, WAIT_RANGE
    val confidence: Int,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val scenario: SignalScenario, // IMMEDIATE, PULLBACK, RANGE, REVERSAL
    val reasoning: String,
    val pullbackHealth: PullbackHealth?,
    val rangeInfo: RangeInfo?,
    val costs: CostAnalysis,
    val timestamp: Long,
    val isFavorite: Boolean = false,
    val isRead: Boolean = false
)
```

### Portfolio Position
```kotlin
data class PortfolioPosition(
    val id: String,
    val symbol: String,
    val side: String, // BUY/SELL
    val entryPrice: Double,
    val currentPrice: Double,
    val lotSize: Double,
    val sl: Double,
    val tp: Double,
    val unrealizedPnl: Double,
    val realizedPnl: Double,
    val status: PositionStatus, // OPEN, CLOSED_WIN, CLOSED_LOSS
    val openedAt: Long,
    val closedAt: Long?
)
```

### Risk Metrics
```kotlin
data class RiskMetrics(
    val openPositions: Int,
    val maxPositions: Int,
    val dailyPnl: Double,
    val dailyLossLimit: Double,
    val killSwitchActive: Boolean,
    val netUsdExposure: Double,
    maxUsdExposure: Double,
    val groupExposure: Map<String, Int>,
    val riskPerTrade: Double
)
```

## API Contracts

### POST /api/trading/scan
```json
{
  "symbols": ["XAUUSD", "EURUSD", "BTCUSD"],
  "forceRefresh": false
}
```
Response: `{ signals: TradingSignal[], scannedAt: timestamp }`

### GET /api/trading/watchlist
Response: `{ items: WatchlistItem[] }`

### POST /api/trading/watchlist
```json
{ "symbol": "XAUUSD", "assetClass": "COMMODITIES" }
```

### DELETE /api/trading/watchlist/:symbol

### GET /api/trading/signals/history
Query: `?symbol=XAUUSD&limit=50&favoritesOnly=false`
Response: `{ signals: TradingSignal[], total: Int }`

### POST /api/trading/signals/:id/favorite
### DELETE /api/trading/signals/:id/favorite
### DELETE /api/trading/signals/:id

### GET /api/trading/portfolio
Response: `{ positions: PortfolioPosition[], metrics: RiskMetrics, summary: PortfolioSummary }`

### GET /api/trading/sentiment
Query: `?symbols=XAUUSD,EURUSD`
Response: `{ sentiment: Map<Symbol, SentimentData> }`

### POST /api/trading/ai/explain
```json
{ "signalId": "signal_123", "detailLevel": "full" }
```
Response: `{ explanation: String, factors: Factor[], confidence: Int }`

### POST /api/trading/ai/recommendations
```json
{ "riskProfile": "MODERATE", "capital": 10000 }
```
Response: `{ recommendations: Recommendation[] }`

## Testing Strategy (TDD)
- Unit tests for all engine logic (already in engine.js)
- Integration tests for API endpoints
- Android unit tests for ViewModels, Repository
- UI tests for critical flows (scan → signal → notify → portfolio)
- E2E tests with Playwright for web dashboard (if needed)

## Security & Privacy
- All trading data encrypted at rest (SQLCipher for Room)
- API tokens in Android Keystore
- No PII in trading logs
- Local-only mode option (no cloud sync)

## Performance Targets
- Signal scan < 2s for 20 pairs
- Dashboard load < 1s (cached)
- Notification delivery < 5s from signal
- Offline mode fully functional
- Battery impact < 2%/day with background scanning

## Success Metrics
- 80%+ test coverage
- Zero critical bugs in signal generation
- Notification reliability > 99%
- Offline mode works for 24h+ without sync
- User can place trade from signal in < 3 taps