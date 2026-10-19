# FRIT MTF Strategy Engine (v1) — `server/src/strategy/`

The directional brain behind `/trade` (enhanced pipeline), `/enhanced/analyze`,
and `/market/strategy`. Built for **Twelve Data free tier** (8 credits/min,
800/day) — every fetch is cached and rate-budgeted.

## Design — low-lag by construction

Your instinct about indicator lag is correct, and this engine is built around it:

| Layer | Tool | Why it doesn't lag |
|---|---|---|
| Regime (1D) | EMA50/200 + EMA200 slope | Deliberately **slow** — it's a trend gate, not an entry trigger. Lag here is a feature: it filters chop. |
| Structure (4H) | Swing pivots → HH/HL/LH/LL | **Price action, not an indicator.** A pivot is confirmed by the market, never smoothed. |
| Entry (1H) | Volume-Profile POC/VAH/VAL + rolling VWAP + ATR | VWAP is **cumulative math — zero lag**. POC is a distribution read, not an average. ATR is **adaptive** (Wilder smoothing), it re-reads volatility every bar. |
| Trigger | Pin-bar / engulfing rejection candle | Pure price action — the market says "rejected" before any indicator catches up. |

## Pipeline

```
1D regime (bull/bear/neutral, score -2..+2)
  + 4H structure (up/down/neutral)
  + 1H pullback into POC–VAL (long) / POC–VAH (short) zone
  + rejection candle trigger
  → confidence 0–95 (transparent, reasons[] included)
  → BUY / SELL if ≥ 62, else WAIT
```

## Guards (each is a hard or strong filter)

- **News** — live Forex Factory high-impact calendar (reused from index.js).
- **Session** — London/NY only (07:00–17:00 GMT, weekdays). Gold is a London/NY asset.
- **Extension** — no entries > 2×ATR from POC (no chasing).
- **Spike** — no entries on the tail of a > 4×ATR bar (news/manipulation candle).
- **Stop sanity** — SL must be ≤ 2.5×ATR(1H); **R:R ≥ 1.8** required.
- **Lot size** — existing `calculateLotSize` (0.5–2% risk) — never more than 2% risk.

## Twelve Data free-tier budget

- 1D (240 bars) + 4H (220) + 1H (170) = **3 credits** on a cold run.
- Cache TTLs: 1D = 6h, 4H = 40min, 1H = 15min → a warm run costs **0 credits**.
- Rate guard: max 6 API calls/60s (budget is 8/min). Excess → stale cache or a
  clean `DATA_RATE_LIMITED` response — never a crashed request.

## Endpoints

| Route | Purpose |
|---|---|
| `GET /market/strategy?symbol=XAUUSD` | Full multi-TF directional report + reasons |
| `POST /enhanced/analyze` `{symbol, strategy?}` | Same report (default `mtf`, `smc_crt` opt-out) |
| `POST /trade` `{symbol, action, pipeline:"enhanced", strategy?}` | Engine decides; blocks on WAIT/NO_TRADE; paper/live via MT5 bridge |
| `GET /strategy/status` | Cache hits, API calls, rate budget, scheduler state |
| `POST /tasks/trade-schedule` `{symbol, time:"07:00", ...}` | Recurring daily task (once/day GMT) |
| `GET /tasks/trade-schedule` | Tasks + run history |

## Recurring tasks (your "everyday analyze XAUUSD" vision)

`TradeTaskScheduler` (zero deps, `setInterval`) fires each task once per GMT day.
Execution is **paper-first**: with `MT5_BRIDGE_URL` unset it logs a simulated
fill; the MT5/phone-tap execution layer plugs in as the executor later.

```json
POST /tasks/trade-schedule
{ "symbol": "XAUUSD", "time": "07:00", "riskPercent": 1, "balance": 1000 }
```

## Honesty notes

- This is a **trend-following + pullback** setup: expect ~35–45% winrate with
  2R targets — **positive expectancy, not high winrate**. High winrate + high
  profit simultaneously doesn't exist; anyone claiming it is selling something.
- Wyckoff is treated as narrative only (not backtestable as a signal engine).
  Volume Profile/VWAP are kept — they are the measurable parts.
- Paper-trade 100+ forward samples and measure real expectancy before real money.
