# FRIT Live Feed — 24/7 scanning on free data tiers (`server/src/strategy/feed/`)

## Why this exists
Twelve Data free = **8 credits/min, 800/day** + trial WS (1 conn, 8 trial symbols only).
REST-polling 10 symbols every 5 min costs ~5,760 credits/day — impossible. So:

- **Ticks stream over free WebSockets** → `CandleStore` builds 30M/4H candles locally (SQLite `feed_candles`, survives restarts).
- **Engine scans on 30M-candle close** via `analyze({candles30M, candles4H})` → **0 Twelve Data credits** steady-state.
- **Twelve Data is backfill/repair only**: 2 credits/symbol, guarded by `feed_budget` (default 200/day).

## Provider tiers per symbol (first live one wins)
| Symbols | Tier 1 | Tier 2 | Last resort |
|---|---|---|---|
| Crypto (BTCUSD…) | Binance WS `data-stream.binance.vision`, native 30m+4h klines, no key | Finnhub WS `BINANCE:BTCUSDT` (needs key) | Binance REST klines (free, in `fetchCandles`) |
| Forex/metals (EURUSD, XAUUSD…) | Finnhub WS `OANDA:EUR_USD`, `OANDA:XAU_USD` (needs key) | Dukascopy poll (keyless, often **bot-blocked from datacenter IPs**) | Budgeted Twelve Data REST (~hourly) |
| Stocks | Finnhub WS (needs key) | Budgeted Twelve Data REST | — |

**Nigeria note:** OANDA accounts are unavailable in NG — that path is intentionally absent.
Finnhub works globally; paste your key as `FINNHUB_KEY` in `server/.env`.
Without it, XAUUSD/forex run on Dukascopy if reachable, else hourly TD refresh.

## Signal flow
`candle close → engine → BUY/SELL/WAIT_PULLBACK/WAIT_RANGE → per-user fan-out`
(`feed_last_signal` table). Anti-spam: BUY/SELL notifies only on decision flip or
>6h since last; WAIT_* stored (max 1/4h) without push. Manual scan route
`POST /api/trading/scan` is unchanged (on-demand, REST).

## Pricing path
`fetchSpotPrice()` checks the live-feed tick cache first (5-min freshness), so
`PositionMonitor` TP/SL resolution also stops spending REST quota.

## Routes
- `GET /api/trading/feed/status` — watched symbols, provider health, TD budget used, stored candles.
- `POST /api/trading/feed/resync` — re-read watchlists, backfill new/stale symbols now.

## Env (`server/.env`, see `.env.example`)
- `FINNHUB_KEY` — free key from finnhub.io/dashboard (forex/metals/stocks WS + free REST backfill).
- `FEED_ENABLED=1` — set `0` for REST-only mode.
- `FEED_BACKFILL_BUDGET=200` — max Twelve Data credits/day for gap backfill.

## 24/7 hosting warning
A sleeping host holds no WebSocket. Free Render spins down on inactivity —
use always-on hosting (paid Render, VPS, Pi, always-on PC) or the feed dies
with the process. On boot it resumes from SQLite + backfills gaps automatically.

## Pocket fixed-expiry signals (`strategy/pocket/` v2)
Manual-execution CALL/PUT signals with **multi-expiry (1m/5m/15m)** for Pocket
Option style trading — a NEW module, not a remod of `strategy/options/`
(which prices US equity insurance contracts; different instrument, different math).
- **TF-adaptive profiles**: expiry picks trend TF above it + trigger TF at/below
  (1m→5m/1m, 5m→15m/5m, 15m→1h/15m); all thresholds ATR-normalized so one rule
  adapts across timeframes. Closed candles only (never repaints).
- **Alignment gate (counter-trend fix)**: trigger-TF EMA9/21 must agree with the
  trend TF. Higher-TF trend + lower-TF pullback = WAIT ("never trade the bounce").
- **Pullback completion (dedicated setup)**: counter-trend WAITs are diagnosed —
  HEALTHY (shallow ≤50% retrace, strong self-relative ADX, no thrust/structure
  break) arms a watch; when the trigger TF re-aligns within N candles it fires
  as a `|pb` setup with its own calibration bucket. REVERSAL (structure break,
  deep+thrusty) kills the idea outright. Arms visible in `/pocket/signal.arm`.
- **Pro gating**: pocket routes require the `pocket` feature (pro/tester/owner
  tiers only — trader deliberately excluded); others get 402 + upgrade message.
  Set `POCKET_PAYOUT` to the broker's actual gold payout.
- **Order-flow confirm (crypto)**: Binance aggTrade buyer/seller imbalance (OFI);
  signals against the tape lose confidence, with it gain. FX/metals feeds carry
  no side — engine says so instead of faking it.
- **Self-calibration**: every setup bucket (`symbol|expiry|trend-strength|session`)
  tracks its own paper win rate. <30 trades → learning mode (confidence capped 65);
  ≥30 trades below breakeven+1% → **auto-disabled** with the reason shown;
  above → confidence blended toward the measured rate. Rivals show static
  confidence; ours is grounded in its own ledger.
- Fires on trigger-TF closes; dedupes paper to one per symbol+expiry window;
  settles every 20s. Stats report win-rate vs YOUR payout breakeven
  (`1/(1+payout)`) — set `POCKET_PAYOUT` to the broker's actual gold payout.
- Routes: `GET /api/trading/pocket/signal?symbol=XAUUSD&expiry=5`,
  `/pocket/history`, `/pocket/stats` (now includes `calibration` buckets).
  App: Trading Suite → Pocket tab (1m/5m/15m chips + countdown + scoreboard).
- Limits: settles on OUR feed (broker OTC price may differ); no auto-execution
  (Pocket Option bans automation); gold needs FINNHUB_KEY for a true stream.

## Unified paper portfolio + bot monitoring semantics
- `GET /api/trading/paper-portfolio` — every execution from every engine with a
  **bot tag**: `intraday` / `longterm` (`positions.source = bot:*`), `scheduler`,
  `manual`, `options` (options_paper, $), `pocket` (pocket_paper, payout units).
  The Portfolio tab shows this — per-bot totals + filter chips. Paper while the
  MT5 bridge is unset; live fills tracked by the same monitor when set.
- **Monitoring semantics**: watchlist = monitored 24/7 by the live feed
  (close-event scans). Saved bots (`POST /api/trading/bots/:name` with
  auto_enabled + pair) are monitored by the 60s server runner on their own
  cadence — YES, they keep working with no app open and no execution needed.
  AI Help recommendations = one-shot snapshot, monitors nothing. The app's AI
  tab now exposes the full Bots UI (pair/TF/auto/save + run history).
- Free execution paths (researched, NOT wired): Deriv's free API does Rise/Fall
  on demo+live (works in NG) and the MT5 bridge already covers FX. Wiring real
  binary execution waits for paper to prove edge + explicit approval.

## Execution venues (all free, all safe-default)
| What | Venue | Money | Live switch |
|---|---|---|---|
| Pocket Rise/Fall (FX/metals/crypto) | Deriv (`strategy/deriv.js`) | Demo default | `DERIV_REAL_TOKEN` + `DERIV_LIVE=1` |
| US stocks (intraday/longterm/`/trade`) | Alpaca (`strategy/alpaca.js`) | Paper default | `ALPACA_LIVE_KEY/SECRET` + `ALPACA_LIVE=1` |
| FX/metals/CFD (intraday/longterm) | MT5 bridge (existing) | Paper while unset | `MT5_BRIDGE_URL` |
Auto flags: `POCKET_DERIV_AUTO` (**default 0 = signals + paper only; set `=1`
for demo auto-execution**), `ALPACA_AUTO` (default 1 = paper auto). Caps: stake/notional + 20/day +
5 concurrent Deriv. Fills land in the monitor as `pocket-deriv` / `alpaca*`
and settle on broker truth. Test: `node deriv_selftest.mjs`, `node alpaca_selftest.mjs`.

## Deriv data + execution (`strategy/deriv.js`)
Deriv is BOTH a full market-data provider (keyless public socket, all 89
markets: synthetics, OTC_* derived indices, frx* forex/metals, cry* crypto)
and a Rise/Fall execution venue for pocket signals. Demo default.
- **Symbols**: every Deriv code is feed-able (`BOOM1000`, `R_100`, `OTC_DJI`,
  `frxXAUUSD`, plain aliases like `EURUSD`/`XAUUSD`/`US30` via `DERIV_MAP`).
  Execution only refuses RNG-stream synthetics (`R_*`, `1HZ*`, `BOOM*`,
  `CRASH*`, `stpRNG`, `JD*`, `RB*`, `WLD*`, `RD*`) — no edge can exist on a
  random stream. OTC_* derived indices mirror real prices and MAY be
  demo-executed. Same rule as `rails/router.js`.
- The feed also falls back to Deriv for ANY mapped symbol when its usual
  providers are dead/absent (free `ticks_history` polls on the public socket).
- **Why nothing executed**: no `DERIV_DEMO_TOKEN` in `.env`. Diagnose with
  `GET /api/trading/deriv/status` → `diag` (tokenPresent, autoEnabled,
  account, lastRefusals) — every refusal is stored in `deriv_refusals` with
  its reason, so silent non-execution can't happen.
- Verify end-to-end: `POST /api/trading/deriv/test {symbol, direction, stake,
  duration}` — buys ONE real demo contract so you can see it in the Deriv app.
- Tokens: `DERIV_DEMO_TOKEN` (active), `DERIV_REAL_TOKEN` (ONLY with
  `DERIV_LIVE=1`; otherwise refused). `DERIV_APP_ID` must be an app registered
  on developers.deriv.com (PAT tokens reject legacy ids like `1089`).
- Auto-buy needs `POCKET_DERIV_AUTO=1` (default 0 = signals + paper only),
  `POCKET_DERIV_STAKE` (default $1, capped $0.35–50), `POCKET_DERIV_MAX_DAY`
  (default 20) + max 5 concurrent contracts.
- Fills register as `pocket-deriv` positions and settle on BROKER outcome
  (proposal_open_contract polled every 15s) via `positionMonitor.resolve` —
  visible separately in the unified paper-portfolio.
- `GET /api/trading/deriv/status` (pocket-gated): mode demo/REAL, balance,
  open contracts, cap usage.
- Test: `node deriv_selftest.mjs` (offline safety: no-token/cap refuses).

## Files
- `feed/candle_store.js` — tick→OHLC aggregation, 30m/4h rings, `feed_candles` table.
- `feed/ws_base.js` — reconnecting WS base (backoff, 23h proactive recycle).
- `feed/binance_ws.js`, `feed/finnhub_ws.js` (+ free REST backfill), `feed/dukascopy_poll.js`.
- `feed/live_feed.js` — routing, backfill budget, close-event scans, fan-out.
- `feed_selftest.mjs` — offline test: `node feed_selftest.mjs` (synthetic ticks → candles → engine, no network).
- Engine seam: `analyze(symbol, {candles30M, candles4H})` in `strategy/engine.js`.
- Fixed alongside: `normalizeInterval("30m")`/`toBinanceInterval` now map the engine's `"30m"` correctly (previously silently fell back to 1h data).
