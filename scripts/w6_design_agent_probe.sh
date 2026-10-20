#!/usr/bin/env bash
# Week 6 — design agent probe. Boots the server on a throwaway DB, grants the
# test install an owner tier (so the paywall does not mask the pipeline), runs
# the agent once and prints the diagnostic trail. Costs one image generation.
set -uo pipefail
cd "$(dirname "$0")/../server" || exit 2

PROBE_PORT=18833
DB="/tmp/frit_da_probe_$$.db"
LOG="/tmp/frit_da_probe_$$.log"
BASE="http://127.0.0.1:$PROBE_PORT"
PID=""
cleanup() {
  [ -n "$PID" ] && kill "$PID" 2>/dev/null && sleep 1 && kill -9 "$PID" 2>/dev/null
  rm -f "$DB" "$DB-wal" "$DB-shm"
}
trap cleanup EXIT

PORT="$PROBE_PORT" DB_PATH="$DB" node src/index.js >"$LOG" 2>&1 &
PID=$!
for _ in $(seq 1 90); do
  curl -fsS -m 2 "$BASE/health" >/dev/null 2>&1 && break
  kill -0 "$PID" 2>/dev/null || { echo "server died"; tail -30 "$LOG"; exit 1; }
  sleep 1
done

UID_="app_deadbeef0009"
KEY=$(curl -fsS -X POST "$BASE/auth/install" -H 'Content-Type: application/json' -d "{\"install_id\":\"$UID_\"}" \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>process.stdout.write(JSON.parse(s).api_key||''))")
[ -n "$KEY" ] || { echo "no key"; exit 1; }

node -e '
const D=require("better-sqlite3");
const db=new D(process.argv[1]);
db.prepare("INSERT INTO subscriptions (user_id,tier,status,expires_at) VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET tier=excluded.tier,status=excluded.status,expires_at=excluded.expires_at")
  .run(process.argv[2], "owner", "active", "2099-01-01");
db.close();' "$DB" "$UID_" || { echo "grant failed"; exit 1; }

echo "== running design agent =="
CODE=$(curl -sS -m 170 -o /tmp/frit_da_body.$$ -w '%{http_code}' -X POST "$BASE/creative-suite/design/agent" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"goal":"Instagram flyer for a Lagos coffee shop launching a new cold brew, warm and premium","headline":"COLD BREW","cta":"Order now","aspect_ratio":"4:5","max_refs":2,"maxAttempts":1}')
echo "HTTP $CODE"
node -e '
const fs=require("fs");
const j=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
console.log("ok:", j.ok, "| error:", j.error||"none");
console.log("stages:", JSON.stringify(j.stages||[]));
console.log("refs:", (j.inspiration||{}).read, "of", ((j.inspiration||{}).refs||[]).length,
  "| queries:", JSON.stringify(((j.inspiration||{}).queries)||[]));
console.log("search errors:", JSON.stringify(((j.inspiration||{}).errors)||[]));
console.log("warnings:"); for (const w of (j.warnings||[])) console.log("  -", w);
const b=j.brief||{};
console.log("copy:", JSON.stringify(b.copy||{}));
console.log("palette:", JSON.stringify((b.palette||[]).map(p=>p.hex+" "+p.share_pct+"%")));
console.log("type:", JSON.stringify((b.typography||[]).map(t=>t.role+" "+t.font+" "+t.size_px+"px")));
console.log("layout:", JSON.stringify(b.layout||{}));
console.log("render:", JSON.stringify({model:j.render&&j.render.model_used, has_url:!!(j.render&&j.render.image_url), has_b64:!!(j.render&&j.render.image_b64)}));
console.log("verify:", JSON.stringify(j.verification));
' /tmp/frit_da_body.$$

echo "---- server log (agent lines) ----"
grep -nE "design|agent|Error|error" "$LOG" | tail -20
rm -f /tmp/frit_da_body.$$
