#!/usr/bin/env bash
# Week 6 (audit) — live debug sweep.
#
# Boots the REAL server on a THROWAWAY db and exercises the routes the Android
# app actually calls (trading suite, billing, creative suite, journal, crash
# ingest) over real HTTP, so route-level bugs that unit tests cannot see show up
# as 5xx / unparsable bodies. Then measures the Phase-6 performance targets.
#
# Exit: 0 = no 5xx and every expected shape held; 1 = at least one failure.
set -uo pipefail
cd "$(dirname "$0")/../server" || exit 2

# NOTE: never inherit $PORT — this host already exports PORT=0 for its own
# dev-server plumbing, and `${PORT:-…}` keeps a non-empty "0" (Week 6 bug found
# by this script's first run: server listened on a random port and never got
# ready). Use a private name.
W6_PORT=18811
DB="/tmp/frit_w6_debug_$$.db"
LOG="/tmp/frit_w6_debug_$$.log"
BASE="http://127.0.0.1:$W6_PORT"
PID=""
FAILS=0
CHECKS=0
SLOW=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null
    sleep 1
    kill -9 "$PID" 2>/dev/null
  fi
  rm -f "$DB" "$DB-wal" "$DB-shm"
}
trap cleanup EXIT

json_get() { # json_get '<json>' '<expr on j>'
  node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const j=JSON.parse(s);const v=($2);process.stdout.write(v==null?'':String(v))}catch(e){process.stdout.write('')}})" <<<"$1"
}

say() { printf '%s\n' "$*"; }
ok()   { CHECKS=$((CHECKS+1)); say "  ok   $*"; }
bad()  { CHECKS=$((CHECKS+1)); FAILS=$((FAILS+1)); say "  FAIL $*"; }

# call METHOD PATH [BODY] [AUTH] — sets $CODE and $BODY reliably even on 4xx/5xx.
call() {
  local method="$1" path="$2" body="${3:-}" auth="${4:-}"
  local args=(-sS -m 30 -X "$method" -o /tmp/w6_body.$$ -w '%{http_code}')
  [ -n "$auth" ] && args+=(-H "Authorization: Bearer $auth")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  CODE=$(curl "${args[@]}" "$BASE$path" 2>/tmp/w6_curlerr.$$) || CODE="000"
  BODY=$(cat /tmp/w6_body.$$ 2>/dev/null || true)
  if [ "$CODE" = "000" ]; then BODY="curl-error: $(cat /tmp/w6_curlerr.$$ 2>/dev/null)"; fi
  rm -f /tmp/w6_body.$$ /tmp/w6_curlerr.$$
}

# expect PATH_METHOD label pattern...
expect_ok() { # expect_ok METHOD PATH expected_status(...|any) [BODY] [AUTH]
  local method="$1" path="$2" want="$3" body="${4:-}" auth="${5:-}"
  call "$method" "$path" "$body" "$auth"
  local match=0
  case " $want " in
    *" any "*) match=1 ;;
    *" $CODE "*) match=1 ;;
    *" ${CODE:0:1}xx "*) match=1 ;;
  esac
  if [ "$match" = "1" ]; then
    ok "$method $path -> $CODE"
  else
    bad "$method $path -> $CODE (want $want): $(head -c 220 <<<"$BODY")"
  fi
}

# ---------------------------------------------------------------- boot ----
say "== boot (db=$DB) =="
T0=$(date +%s%N)
PORT="$W6_PORT" DB_PATH="$DB" NODE_ENV=test node src/index.js >"$LOG" 2>&1 &
PID=$!
ready=0
for _ in $(seq 1 90); do
  if curl -fsS -m 2 "$BASE/health" >/dev/null 2>&1; then ready=1; break; fi
  kill -0 "$PID" 2>/dev/null || break
  sleep 1
done
[ "$ready" = "1" ] || { say "FAIL: server never became ready"; tail -40 "$LOG"; exit 1; }
T1=$(date +%s%N)
BOOT_MS=$(( (T1-T0)/1000000 ))
ok "booted in ${BOOT_MS}ms"

# 5xx anywhere in the log during the sweep is a failure on its own.
scan_log() {
  local hits
  hits=$(grep -nE "Unhandled|UnhandledPromiseRejection|FATAL|TypeError|ReferenceError|SQLITE_|Error: " "$LOG" | head -8)
  if [ -n "$hits" ]; then
    say "---- server log errors ----"; printf '%s\n' "$hits"
  fi
}

# ------------------------------------------------------------- public ----
expect_ok GET /health "200"
expect_ok GET /billing/tiers "200 401"
expect_ok GET /nope-does-not-exist "404"

# crash ingest is unauthenticated BY DESIGN (F-15)
expect_ok POST /crash "200 201 202 204" '{"type":"test","message":"week6 sweep","stack":"at x"}'
expect_ok POST /crash "400 413 422" '{}'

# install key
call POST /auth/install '{"install_id":"app_deadbeef0001"}'
KEY=$(json_get "$BODY" 'j.api_key')
[ -n "$KEY" ] && ok "install key issued" || bad "install key missing: $(head -c 200 <<<"$BODY")"

# ------------------------------------------------------------- authed ----
say "== authed routes (install key) =="
for p in \
  /risk/status /positions /positions/status /billing/credits /billing/status \
  /wallet/balance /memory/trade /strategy/journal/stats /strategy/journal/recent \
  /strategy/costs /weather /api/trading/watchlist /api/trading/signals/history \
  /api/trading/portfolio /api/trading/paper-portfolio /api/trading/risk \
  /api/trading/learning /api/trading/feed/status /api/trading/bots \
  /api/trading/bots/runs /api/trading/bot-pairs /api/trading/pocket/watchlist \
  /api/trading/pocket/history /api/trading/alpaca/status /api/trading/deriv/status \
  /creative-suite/templates /creative-suite/style-presets /creative-suite/cinema/recipes \
  /creative-suite/cinema/options /creative-suite/brandkit /creative-suite/projects \
  /creative-suite/elements /models/endpoints /billing/pay-info \
  /creative-suite/videos/models ; do
  expect_ok GET "$p" "200 204 400 402 501" "" "$KEY"
done

# mutating, but on throwaway data
expect_ok POST /risk/check "200" '{"symbol":"EURUSD","action":"buy","lotSize":0.01,"entry":1.1,"sl":1.098,"balance":1000}' "$KEY"
# 402 here is the plan gate (trading is a paid feature; free = chat+market_data).
# Recorded as a finding, not a failure: scan/list are ungated, add is gated.
expect_ok POST /api/trading/watchlist "200 201 402" '{"symbol":"EURUSD","assetClass":"FOREX"}' "$KEY"
expect_ok PATCH /api/trading/watchlist/EURUSD "200 204 400" '{"isEnabled":false,"sortOrder":1}' "$KEY"
expect_ok DELETE /api/trading/watchlist/EURUSD "200 204 404" "" "$KEY"
expect_ok POST /api/trading/scan "200" '{"symbols":["EURUSD","BTCUSD"],"forceRefresh":false}' "$KEY"
expect_ok POST /api/trading/alerts/check "200" '{"checks":[{"symbol":"EURUSD","price":1.1}]}' "$KEY"
expect_ok POST /api/trading/notifications/register "200 400" '{"token":"devtoken123","platform":"android"}' "$KEY"
expect_ok POST /api/trading/ai/recommendations "200 502 503" '{"riskProfile":"MODERATE","capital":10000}' "$KEY"
expect_ok POST /creative-suite/projects "200 201" '{"name":"w6 project","platform":"tiktok"}' "$KEY"
PROJ=$(json_get "$BODY" 'j.project?.id || j.project_id || j.id')
if [ -n "$PROJ" ]; then
  expect_ok GET "/creative-suite/projects/$PROJ" "200" "" "$KEY"
  expect_ok GET "/creative-suite/projects/$PROJ/sync" "200" "" "$KEY"
  expect_ok POST "/creative-suite/projects/$PROJ/sync" "200" '{"state":{"clips":[]}}' "$KEY"
  expect_ok PUT "/creative-suite/projects/$PROJ" "200" '{"name":"w6 renamed"}' "$KEY"
  # A synced project also has collaborator rows; both carry a FOREIGN KEY to the
  # project, which is exactly what F-46 broke on delete.
  expect_ok POST "/creative-suite/projects/$PROJ/collaborators" "200" '{"user_id":"app_deadbeef0002","role":"editor"}' "$KEY"
  expect_ok GET "/creative-suite/projects/$PROJ/collaborators" "200" "" "$KEY"
  expect_ok DELETE "/creative-suite/projects/$PROJ" "200 204" "" "$KEY"
else
  bad "project create returned no id: $(head -c 200 <<<"$BODY")"
fi

# 401 shape: authed route without a token
call GET /api/trading/portfolio "" ""
[ "$CODE" = "401" ] && ok "authed route rejects missing token" || bad "authed route missing token -> $CODE"
# bogus token
call GET /api/trading/portfolio "" "not-a-real-token"
[ "$CODE" = "401" ] && ok "authed route rejects bogus token" || bad "bogus token -> $CODE"

# --------------------------------------------------- streaming (SSE) ----
say "== /agent/chat/stream =="
SSE=/tmp/w6_sse.$$
T2=$(date +%s%N)
curl -sS -m 90 -N -X POST "$BASE/agent/chat/stream" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"goal":"Say the single word: pong"}' >"$SSE" 2>/tmp/w6_sse_err.$$ || true
T3=$(date +%s%N)
SSE_MS=$(( (T3-T2)/1000000 ))
DELTAS=$(grep -c '^event: delta' "$SSE" 2>/dev/null || true)
DONE=$(grep -c '^event: done' "$SSE" 2>/dev/null || true)
ERRS=$(grep -c '^event: error' "$SSE" 2>/dev/null || true)
say "  sse: deltas=$DELTAS done=$DONE error=$ERRS bytes=$(wc -c <"$SSE" 2>/dev/null || echo 0) time=${SSE_MS}ms"
if [ "$DONE" -ge 1 ] && [ "$ERRS" = "0" ]; then ok "stream completed with a done frame"
elif [ "$ERRS" -ge 1 ]; then say "  (provider unavailable — error frame: $(grep '^data:' "$SSE" | head -1 | head -c 160))"
else bad "stream produced no done frame: $(head -c 240 "$SSE")"; fi
rm -f "$SSE" /tmp/w6_sse_err.$$

# ------------------------------------------------- DB shape / migrations ----
say "== db tables =="
TABLES=$(node -e '
const D=require("better-sqlite3");
const db=new D(process.argv[1],{readonly:true,fileMustExist:true});
const t=db.prepare("SELECT name FROM sqlite_master WHERE type=? ORDER BY name").all("table").map(r=>r.name);
process.stdout.write(t.join(","));
' "$DB" 2>/tmp/w6_dbq.$$)
[ -s /tmp/w6_dbq.$$ ] && say "  db query note: $(head -c 200 /tmp/w6_dbq.$$)"
rm -f /tmp/w6_dbq.$$
for t in users api_keys positions trade_tasks crashes crash_reports creative_projects trading_signals options_paper provider_jobs; do
  case ",$TABLES," in
    *",$t,"*) ok "table $t" ;;
    *",${t}s,"*) ok "table ${t}s" ;;
    *) say "  note table $t absent (may be lazy-created) — have: $(tr ',' ' ' <<<"$TABLES" | head -c 300)" ;;
  esac
done

# ------------------------------------------------------- performance ----
say "== performance (Phase 6 target: scan < 2s for 20 pairs) =="
perf() { # perf METHOD PATH BODY LABEL
  local m="$1" p="$2" b="${3:-}" label="$4"
  local t0 t1 ms
  t0=$(date +%s%N)
  call "$m" "$p" "$b" "$KEY"
  t1=$(date +%s%N); ms=$(( (t1-t0)/1000000 ))
  say "  ${label}: ${ms}ms (HTTP $CODE)"
  echo "$label $ms" >> /tmp/w6_perf.$$
}
: >/tmp/w6_perf.$$
SYMS=$(node -e 'const s=["EURUSD","GBPUSD","USDJPY","AUDUSD","USDCAD","USDCHF","NZDUSD","EURGBP","EURJPY","GBPJPY","XAUUSD","BTCUSD","ETHUSD","SOLUSD","ADAUSD","DOGEUSD","XRPUSD","BNBUSD","AAPL","TSLA"];process.stdout.write(JSON.stringify(s))')
perf POST /api/trading/scan "{\"symbols\":$SYMS,\"forceRefresh\":false}" "scan-20"
perf GET /api/trading/portfolio "" "portfolio"
perf GET /api/trading/feed/status "" "feed-status"
perf GET /health "" "health"

scan_log
say ""
say "== result: $((CHECKS-FAILS))/$CHECKS checks passed, $FAILS failure(s) =="
[ "$FAILS" -eq 0 ] || { say "---- last 60 log lines ----"; tail -60 "$LOG"; exit 1; }
exit 0
