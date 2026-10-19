#!/usr/bin/env bash
# Week 5 live boot smoke test — starts the real server on a THROWAWAY db,
# exercises the modules changed this week (the risk layer) over real HTTP,
# then shuts the server down. Non-zero exit on the first failed expectation.
set -uo pipefail
cd "$(dirname "$0")/../server" || exit 2

PORT=18799
DB="/tmp/frit_w5_smoke_$$.db"
LOG="/tmp/frit_w5_smoke_$$.log"
BASE="http://127.0.0.1:$PORT"
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null
    sleep 1
    kill -9 "$PID" 2>/dev/null
  fi
  rm -f "$DB" "$DB-wal" "$DB-shm"
}
trap cleanup EXIT

echo "== booting server (db=$DB log=$LOG) =="
PORT="$PORT" DB_PATH="$DB" node src/index.js >"$LOG" 2>&1 &
PID=$!

# --- wait for readiness (bounded) ---
ready=0
for i in $(seq 1 60); do
  if curl -fsS "$BASE/health" >/dev/null 2>&1; then ready=1; break; fi
  if ! kill -0 "$PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "FAIL: server did not become ready"
  echo "---- last 40 log lines ----"
  tail -40 "$LOG"
  exit 1
fi
echo "OK: /health responded"

fail() { echo "FAIL: $1"; echo "---- last 40 log lines ----"; tail -40 "$LOG"; exit 1; }

# --- /health content ---
curl -fsS "$BASE/health" | head -c 400; echo

# --- unauthenticated /risk/status must be rejected ---
code=$(curl -s -o /tmp/w5_unauth.json -w "%{http_code}" "$BASE/risk/status")
case "$code" in
  401|403) echo "OK: /risk/status without a token -> $code" ;;
  *) fail "/risk/status without a token returned $code (expected 401/403)" ;;
esac

# --- register an install key, then read the risk state ---
KEY=$(curl -fsS -X POST "$BASE/auth/install" -H 'Content-Type: application/json' \
  -d '{"install_id":"app_0123456789ab"}' | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{process.stdout.write(JSON.parse(s).api_key||'')}catch(e){process.stdout.write('')}})")
[ -n "$KEY" ] || fail "could not register an install key"
echo "OK: install key registered"

RISK=$(curl -fsS "$BASE/risk/status" -H "Authorization: Bearer $KEY")
echo "$RISK" | head -c 400; echo
echo "$RISK" | node -e "
let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
  const j=JSON.parse(s);
  if (j.riskStateError) { console.error('FAIL: risk state unreadable on a healthy db: '+j.riskStateError); process.exit(1); }
  if (typeof j.openCount !== 'number') { console.error('FAIL: no openCount'); process.exit(1); }
  console.log('OK: risk state readable (openCount='+j.openCount+', killSwitchTripped='+j.killSwitchTripped+')');
});" || fail "/risk/status returned an unreadable risk state"

# --- /risk/check must block an oversized trade over real HTTP ---
CHECK=$(curl -fsS -X POST "$BASE/risk/check" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"symbol":"EURUSD","action":"buy","lotSize":0.5,"entry":1.1,"sl":1.095,"balance":1000}')
echo "$CHECK" | head -c 300; echo
echo "$CHECK" | node -e "
let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
  const j=JSON.parse(s);
  if (j.allowed !== false) { console.error('FAIL: oversized trade was ALLOWED'); process.exit(1); }
  if (!Array.isArray(j.reasons) || !j.reasons.length) { console.error('FAIL: no blocking reason'); process.exit(1); }
  console.log('OK: oversized trade blocked -> '+j.reasons[0]);
});" || fail "/risk/check did not block an oversized trade"

echo "== all live boot checks passed =="
exit 0
