# FRIT — COMPLETE APP AUDIT (living file — any AI can resume from here)

> **Purpose**: Single source of truth for the whole-app audit so it is never redone.
> **How to resume**: Read this file first. Then read only the files listed under "UNREAD" below,
> in full, and append findings under "DEEP AUDIT FINDINGS" with `file:line` evidence.
> Mark fixes done with ✅ DONE. **Last updated**: 2026-10-20 Week 6 — live route sweep, F-45 scan deadline, F-46 project delete, F-08 closed

---

## 0. HARD CONSTRAINTS (user-mandated — do not violate)

1. **The Android auth token must NOT be touched and must NOT be moved to XML.**
   The user's per-install token handling in `TokenStore.kt` stays exactly as-is.
   Note: the token actually lives in `BuildConfig.FRIT_AUTH_TOKEN` (Gradle `buildConfigField`,
   not hard-coded Kotlin and not XML) — leave this mechanism alone regardless.
2. **Google Play work is DEFERRED** — see section 6. Noted, not done, by user's explicit request.
3. Do not commit/push without asking. `server/` is NOT a git checkout on this host — server changes
   exist on disk only. **CORRECTION (Week 5 → SUPERSEDED 2026-10-20): the repo ROOT is not a git
   checkout either** — there is
   no `.git` anywhere in the project directory (the `git repo/` folder is not a checkout). So
   nothing here is version-controlled on this host; keep backups before large refactors.
   → **SUPERSEDED 2026-10-20: the project IS under version control now.** It is split across
   THREE GitHub repos, all owned by `uchechukwu090`:
   - `uchechukwu090/frit_andriod` — the ROOT repo (Android app, docs, scripts, CI, the vendored
     llama.cpp tree). **`server/` and `sandbox and docker/` are SUBMODULES** pointing at the two
     repos below. Use `git clone --recursive`.
   - `uchechukwu090/frit-android` — the API server (`server/`).
   - `uchechukwu090/sandbox` — the ffmpeg/code sandbox service (`sandbox and docker/`).
   Still ask before pushing, and note that a root-level `git add -A` sweeps up any
   half-finished work sitting in the sub-repos.

4. **Rotate the production `AUTH_TOKEN`.** It was pasted in plaintext into `PLAYSTORE_RELEASE.md`
   (a tracked file) and has now been redacted — but a credential that has been in a tracked
   file must be treated as compromised. `server/.env` itself is gitignored and was never
   committed; verify that stays true before making any repo public.

---

## 0b. ✅ ANDROID BUILD TOOLCHAIN IS AVAILABLE (Week 5 — this changes the plan)

Earlier rounds of this audit recorded "no compile toolchain on this host (no kotlinc, no java)"
and deferred every Android change to CI. **That is wrong — the toolchain IS here.** Verified
2026-10-19:

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # JDK ships with Android Studio
.\gradlew.bat :app:compileDebugKotlin      # ~1.5-4 min (first run ~6 min)
.\gradlew.bat :app:testDebugUnitTest       # JVM unit tests
```

- SDK: `C:\Users\User\AppData\Local\Android\Sdk` (platforms, build-tools, ndk, cmake present)
- `android/local.properties` already points `sdk.dir` at it.
- Gradle 9.3.1 wrapper downloads on first run; the **first** build needs network (the
  `androidx.security:security-crypto:1.1.0-alpha06` dep added by F-26 is not in the local
  cache, so `--offline` FAILS until it has been fetched once). Subsequent builds are offline-OK.
- **The Android app has now been compiled end-to-end for the first time.** It DID NOT compile:
  see F-42 below for the compile-breaker this uncovered.

**Consequence for the backlog:** every item previously deferred to "needs an Android Studio
build" is now verifiable on this host. Do not defer again — build and test.


---

## 1. ✅ COMPLETED FIXES (done — do not redo)

These were implemented and verified in the 2026-10-18 fix round:

1. ✅ **DONE** — Completion sweeper for `provider_jobs` (`server/src/index.js` ~L1915) with `provider`
   column migration via guarded `ALTER TABLE`; fresh DBs and existing DBs both fine.
2. ✅ **DONE** — Lip-sync billing moved to completion: Hedra charged on status-poll completion
   (~L6080); Sync Labs charged exactly once in webhook behind a status-transition guard (~L6012).
3. ✅ **DONE** — Auto-Pilot frame ordering: frames sorted by shot index before assembly (~L6221).
4. ✅ **DONE** — `GET /creative-suite/images/batch/:id/status` added with ownership check (~L5340);
   response shape matches client `pollBatchStatus`.
5. ✅ **DONE** — API catalog updated with `/creative-suite/director` + batch-status route (~L3939).
6. ✅ **DONE** — Test suite `server/test/creative.test.mjs` (23 tests) + `npm test` script using an
   explicit glob (the `node --test test/` directory form errors on Windows host).

**Verification state (re-run only if server files change):**
- `cd server && node --check src/index.js` → clean
- `cd server && npm test` → 23/23 pass, exit 0 (capture via `echo "EXIT=${PIPESTATUS[0]}"`)
- NOT verified live: provider billing behavior (sweeper charge, Hedra completion, webhook
  idempotency) — no API keys/running stack on this host; staging pass still needed.
- Not unit-testable as-is: `checkEntitlement`/`debitCredits`/`chargeProviderJob` are inline in
  index.js; extracting them is an open refactor.

---

## 2. DEEP AUDIT FINDINGS (evidence-based, `file:line`)

### 2.1 Server — money & reliability (P0)

- **F-01 ✅ DONE — graceful shutdown implemented** in the 2026-10-19 fix round:
  `gracefulShutdown()` in index.js handles SIGTERM/SIGINT — `server.closeAllConnections()`, 8s
  drain via `server.close()`, then `db.close()`, exit 0 (with force timer).
- **F-02 ✅ DONE — exit-on-fatal implemented**: `uncaughtException` now logs `FATAL` and `process.exit(1)`
  after a 1s flush grace; the platform restarts clean (WAL protects committed rows).
- **F-03 ✅ DONE — fixed + tested**: (a) the `trendStart` structure-breakout bonus was chained to
  `if (session.ok) … else if`, unreachable during trading hours — now an independent check;
  (b) NEW finding F-30 below fixed in the same pass; (c) golden tests added in
  `server/test/engine.test.mjs` (fixtures: 30M breakout + 4H fresh-ADX-leg, seeded LCG,
  deterministic — no network).
- **F-04 ✅ DONE — fail-closed**: `PortfolioRisk.check()` on internal error now pushes a reason
  (→ `allowed:false`) and logs `fail-closed`. Guard math covered by tests (per-trade cap,
  daily-loss kill-switch with `CURRENT_TIMESTAMP` semantics, clean-book pass).
- **F-05 ✅ DONE — task persistence**:  `trade_tasks` table (add/remove/fired-day all persisted, restored in constructor); index.js passes `db`. Memory remains runtime-authoritative;
  persistence is best-effort. Tested incl. a restart-simulation (new instance restores rows +
  fired-day guard so no double-fires after deploys).

**F-01 live-verification limitation (recorded 2026-10-19):** full server boot was smoke-tested on
this host (`PORT=18799 node src/index.js` → banner, monitors, livefeed, scheduler-with-db all
initialized cleanly — validates the whole index.js construction path including the new
scheduler `db` wiring). **However, Windows cannot deliver SIGTERM to Node** (Node does not
support receiving SIGTERM on Windows), so `kill -TERM` exits 143 with no handler invocation —
this is the OS, not a code defect. Final SIGTERM→clean-exit proof must come from the
Linux staging box (Render/Railway where it actually matters).
- **F-06 ✅ DONE (2026-10-19, Week 4):** `executeTradeSignal` now sizes MT5-bridge trades from the risk
  budget for `source` starting `bot:` (`calculateLotSize` with balance × riskPercent, 2% cap, 5 lot max)
  instead of the placeholder 0.01 from `bots.js`; Deriv/Alpaca rails were already dollar-sized. Risk gate
  still runs after sizing. `node --check` clean. Not live-verified (no MT5 bridge on this host).
- **F-07 ✅ DONE (2026-10-19, Week 2) — this entry is stale, see §4 Week 2.** The strategy layer
  IS covered: `server/test/strategy.test.mjs` (39 tests) exercises `hysteresis.js`,
  `rails/router.js`, `options/pricing.js` (Black-Scholes parity, IV round-trip, greeks) and
  `options/expectedMove`. The line below is the ORIGINAL Week-1 wording, kept for history:
  ~~No tests for strategy layer beyond creative: `risk.js`, `bots.js`, `hysteresis.js`,
  `rails/router.js`, `position_monitor.js`, `costs.js` are all pure/testable…~~ Still untested
  (not yet written, low value vs. effort): `bots.js`, `position_monitor.js`, `costs.js`,
  `structures.js`, `synthetics.js`, `volumematrix.js`, `volengine.js`, `longterm.js`,
  `pocket/engine.js` signal core, `journal.js` scoring.
- **F-08 ✅ DONE (2026-10-20, Week 6) — the server-side sweep is finished.** New
  `scripts/js_catch_audit.js` (string/comment-aware, mirrors the Kotlin one) scanned **all 49
  JS files under `server/src`: 0 EMPTY catch blocks**, 22 SILENT (non-empty, no log/rethrow). It also flagged 22 catches whose
  body neither logs nor rethrows; every one was read in context and each *does* hand the
  failure to the caller or record it — `alpaca.js`/`deriv-rest.js` return `{error}`,
  `options/paper.js` marks the row `stale`, `bots.js` writes an `ERROR` bot run,
  `deep_search.js` collects the failure into its result, `risk.js` stores `error = e.message`
  and `fail-closed`s, `index.js` returns it in the route body / per-frame results map. So the
  remaining "silent" hits are silent-on-log but not swallowed. Week 4's index.js money-path
  logging stands; nothing further is needed here.
- **F-09 ✅ DOCUMENTED (2026-10-19, Week 4):** deployment build notes: Node >=18 (CI/Render should pin the
  same major used locally); `better-sqlite3` compiles a native addon — on Linux hosts you need
  `build-essential` + `python3` unless a prebuilt binary matches the Node ABI; keep `package-lock.json` committed
  and deploy with `npm ci`, not `npm install`; `node-fetch@3` is ESM-only (the server is already ESM).
  `"node": ">=18"` but uses `node-fetch@3` + `better-sqlite3@13` (needs Node 18+ native build;
  document build deps for deployment).

### 2.2 Server — security (carried over from earlier round)

- **F-10 ✅ DONE (2026-10-19):** CORS anonymized allowlist — `origin("*")` replaced by an
  allowlist check (`CORS_ORIGINS` env, default empty = no browser cross-site client gets ACAO
  headers; native Android/curl/same-origin always pass). PLUS F-10b: new `ENFORCE_BOUNDED_MONEY=1`
  env makes `boundUser()` refuse master-token callers that self-assert a user_id on money paths
  (default unset = legacy dev behavior; flip in production). Remaining open by choice: none for
  CORS; the master-token fallback in `requireAuth` itself stays (dev/legacy escape hatch).
- **F-11 ✅ DONE (2026-10-19):** `WEBHOOK_SECRET` env — the Sync Labs webhook registration now
  appends `&secret=…` to the webhookUrl and `/webhooks/lipsync` rejects requests with a bad/missing
  secret (401) once set. Legacy in-flight jobs settle via the provider_jobs completion sweeper.
  Unset = legacy behavior; set it in production ".env".
- **F-12 ✅ DONE (2026-10-19, Week 4):** uploads here are base64-in-JSON (no multer). New
  `decodeImageB64()` in index.js validates size (15MB cap) and png/jpeg/webp/gif magic bytes, wired into
  all `/creative-suite/images/*` routes (edit, remove-background, retouch, style-transfer, upscale,
  generative-fill, batch). Bad input now returns 400 before any credits are checked or debited.
  Video/audio ingest paths: ✅ DONE (F-12b, 2026-10-19) — `mediaToB64` now has an SSRF guard
  (`isPrivateHost`: localhost/.local/.internal, 127/10/172.16-31/192.168/169.254, IPv6 loopback/ULA/mapped;
  redirects refused), a media magic-byte check (`looksLikeMedia`: mp4/mov/m4a, webm/mkv, wav/avi, ogg, flac,
  mp3), `/transcribe` caps audio at ~15MB (413), and the lip-sync audio/video decode caps 15MB/30MB with
  the same sniff. `node --check` EXIT=0. Known gap: DNS rebinding (a public hostname resolving to a private
  IP) is not checked.
- **F-13 🟠→✅ DONE (2026-10-19, Week 5) — all three creative-quality items fixed:**
  1. **Subtitle timestamps are real now.** `aiFeatures.generateSubtitles` used to fabricate
     timings with a fixed "0.4 seconds per word" guess, so every burned-in caption drifted
     out of sync. It now asks the STT endpoint for `response_format=verbose_json` +
     `timestamp_granularities=["word","segment"]` (verified live against OpenRouter with
     `openai/whisper-large-v3` — it returns real `words[]` with `start`/`end`), and falls back
     to segments, then to a duration-weighted estimate over the REAL audio length.
     New pure module `server/src/creative/subtitles.js` (word→cue grouping, sentence
     breaks, overlap/gap normalisation, SRT formatting) — 14 unit tests in
     `server/test/subtitles.test.mjs`.
  2. **Translation no longer shifts the timings.** The old `translateSubtitles` translated
     the whole transcript as one blob and re-chunked the translated words by count, so every
     cue after the first was misaligned. It now translates cue-by-cue (numbered lines,
     parsed back by index), keeping the original `start`/`end` intact.
  3. **Video export actually re-encodes.** `/creative-suite/export` used to return the source
     clip untouched with a note that re-encoding "isn't available here", so a 4:5 clip shared
     to TikTok stayed 4:5. The sandbox now has a real `export` op (exact platform canvas,
     exact fps, `cover` crop to fill / `contain` to letterbox, tail cut to the platform's
     max duration) and every ffmpeg op moved to fixed quality profiles:
     `-c:v libx264 -preset medium -crf 18 -profile:v high -level 4.1 -pix_fmt yuv420p`
     + `-c:a aac -b:a 192k -ar 48000 -ac 2` + `-movflags +faststart`. (Was `-preset veryfast`
     with x264's default CRF 23 and no audio control — visibly lossy and needlessly large.)
     The Android `ExportScreen` now consumes the new `video_base64` response.
  4. **Brand Kit injection is server-side.** It used to be built CLIENT-side (the app
     formatted a "Brand lock [...]" string and pasted it into the prompt), so only the
     Design Studio honoured it and any retry lost it. New `server/src/creative/brand.js`
     resolves the kit from the DB (ownership enforced in SQL) and appends the lock
     server-side; `/creative-suite/images/generate`, `/videos/generate` and
     `/cinema/generate` all accept `brandkit_id` now. The client sends only the id.
     10 unit tests in `server/test/brand.test.mjs`.
  - Verified: `node --check` clean on index.js/aiFeatures.js/subtitles.js/brand.js and on the
    sandbox server; `npm test` → **113/113 pass** (was 48).

### 2.3 Android

- **F-14 🔴 `MainActivity.kt` = 3,951 lines — FULLY READ (2026-10-18), split plan concrete.**
  It contains ~15 whole screens/composables, each self-contained and mechanically movable to its
  own file: `SettingsScreen`, `WalletScreen` (~700 lines incl. all billing flows), `CreatorScreen`
  (admin inbox/mint/model-slug console), `OwnerConsole` (owner-tier endpoint console),
  `OnDeviceModelSection` (GGUF download/import), `ChatsScreen`, `AutomationScreen`,
  `ChatContent`, `WakeWordTrainingDialog`, `OnboardingDialog`, `UserProfileSection`,
  `WaveformBars`, `RecordingOverlay`, `ExpandableSection`/`SettingToggle` (shared widgets → ui/),
  plus helpers (`exportChat`, `saveEditedImageToDownloads`, `shareExportAsset`, `downscaleForUpload`).
  Nav = ~30 `showX` boolean `remember` flags in one `when {}` (NOT `rememberSaveable` — process
  death loses the screen). Trade-confirmation dialog (`tradeToConfirm`) DOES exist for agent
  trades — good; keep it in any refactor.
  - **Week 5 status: NOT DONE — and now it is safe to do.** The blocker was "no compile
    toolchain", which turned out to be false (§0b). The app compiles and the unit tests run here,
    so the split can be validated in batches. It was deliberately left until the higher-value
    fixes (F-13, F-18a, F-42, streaming) were done, because a 3,951-line refactor with no
    compiler is exactly how F-42 happened. **Do it next, in batches, with a compile after each.**

- **F-15 ✅ DONE (2026-10-19, both sides):** server gained `POST /crash` (unauthenticated BY
  DESIGN — crashes can happen pre-auth; IP rate-limit 10/min; type/message/stack caps; SQLite
  `crash_reports` table) + `GET /admin/crashes` (requireAdmin). Client gained
  `NetworkClient.sendCrash` + CrashGuard uploader: dumps are scrubbed via `PrivacyScrubber`,
  files stay on disk until the server acks, `install()` re-uploads the backlog on every boot,
  and a 20-file on-disk ring bounds storage. NOT compiled on this host (no Android SDK) — the
  repo's Android CI (`.github/workflows/android-ci.yml`) will build+unit-test on the next PR;
  check it green before shipping.
- **F-16 ✅ DONE (2026-10-19, Week 5) — the Android sweep is finished.** `core/Diag.kt`
  (`Diag.warn(tag, where, e)` → Logcat + 60-entry in-memory ring, never throws, logs
  class+message only). Week 4 applied it to: install-key registration (NetworkClient),
  SensitiveGate audit-ring write, profile learning (IntegrationCoordinator), image save,
  FaceEnroll/overlay/security-settings intent launches (MainActivity).
  **Week 5 completed the sweep** across the files the audit had listed as unswept — voice
  (VoiceEngine TTS, OfflineSttEngine system+Vosk), veil (VeilManager scheduled-work check,
  FaceVerifier ref clear), vision (ScreenCaptureService auto-start), market
  (FritAccessibilityService tap), automation (AutoModeService init/sweep,
  FritBackgroundService teardown), trading (TradingSuiteScreen alert-prefs save) and all
  `ui/creative/*` screens (brand-kit create, project/template/element/storyboard loads,
  lip-sync poll, prompt suggestions, cinema recipes+options).
  Deliberately NOT touched, by design: every catch around `TokenStore`/`FRIT_AUTH_TOKEN`
  (Constraint #1), and probe-and-ignore patterns where a throw is the EXPECTED outcome
  (package-lookup loops, `ALTER TABLE` migration guards, `audioRecord.release()`,
  `recognizer.destroy()`, `resolver.delete()`, TTS `setSpeechRate`, JSON parsers).
  Brace scan clean on 121/121 Kotlin files; `:app:compileDebugKotlin` BUILD SUCCESSFUL.
- **F-17 🟡** `NetworkClient.kt` (793 lines) is well-structured (error mapping, fallbacks) — keep.
  `TokenStore.kt` / `PrivacyGuard.kt` are clean — **do not regress, do not modify token logic**.
- **F-18 ✅ DONE (2026-10-19, Week 5) — (a) and (b) both closed.** (a) ✅ DONE Week 5 — the
  call-confirm gate is now real (see §2.3c). (b) ✅ DONE — `resolveContact` now prefers an exact
  display-name match, accepts a single partial match, and returns null when several different people match
  (numbers de-duped by last 9 digits so +234/0 formats count once). `call X` then falls through to the cloud
  agent instead of dialling the first row; SMS/WhatsApp still fall back to the typed name and open compose,
  so nothing is sent without the user. (a) NOT changed on purpose: background tasks run through
  `IntegrationCoordinator.process` (FritBackgroundService.kt:218), which already has SensitiveGate
  confirmation for invented calls; the local `call X` fast path only fires on the user's own utterance.
  Revisit only if a scheduled task is ever able to reach `AutomationEngine.makeCall` directly.
  Brace scan clean; not compiled (CI is the gate). Phone-side actions are calls/SMS/
  settings/music/navigation — no trade execution on-device (trades go through the server bridge).
  Two hardening notes: (a) `makeCall` fires `Intent.ACTION_CALL` directly (places the call with no
  confirmation step) — add a confirm gate for automation-initiated calls; (b) `resolveContact`
  matches `LIKE %name%` and takes the first row — "call John" can hit the wrong John; prefer exact
  match then disambiguate. Otherwise clean (setting-intent hijack guard, safe parsers).
- **F-26 ✅ DONE (2026-10-19, Week 4 — needs Android Studio build):** new `core/SecureStore.kt`
  (EncryptedSharedPreferences, AES-256 GCM/SIV via MasterKey, file `frit_secure`) now holds the App PIN
  (`app_pin`) and the creator `admin_token`. Lazy one-way migration: first read copies the plaintext value
  from `frit_prefs` (commit) then deletes it; if Keystore is unavailable it degrades to the legacy file rather
  than losing the value. Wired: `AdminStore` get/set, `IntegrationCoordinator` `get_app_pin`, `MainActivity`
  (initial load + save + clear). Gradle: `androidx.security:security-crypto:1.1.0-alpha06` added to
  app/build.gradle.kts. `DataWipe` now also wipes the encrypted file (the App PIN was NEVER wiped before).
  Backup rules + data_extraction_rules now exclude `frit_secure`, `frit_veil`, `frit_veil_face`,
  `frit_trading_cache` (resolves F-38e for those; chat archives still back up deliberately). **TokenStore /
  BuildConfig.FRIT_AUTH_TOKEN untouched (Constraint #1).** Veil PIN is a salted hash and stays in
  `frit_veil` (F-38b). Brace scan clean on all edited files; first Gradle sync will pull the new dependency.
  Android Studio checks: (1) sync + build; (2) set an App PIN, force-stop, reopen — PIN persists; (3) on a
  device that had a PIN before the update, confirm it survives (migration) and `frit_prefs.xml` no longer
  contains `app_pin`. `app_pin` (documented for use like OPay
  transaction PINs) and the veil/admin stores sit in plaintext `SharedPreferences ("frit_prefs")`.
  The AI can fetch the PIN via `get_app_pin` and auto-type it — that's by design, but at minimum
  migrate these keys to `EncryptedSharedPreferences`/Keystore. **This is NOT the auth token —
  Constraint #1 (never touch/move the token) still applies to `TokenStore`/BuildConfig.**
- **F-29 ✅ DONE (2026-10-19, Week 4):** `OnDeviceModelSection` poll is now adaptive
  (500ms while `downloading`, 2000ms idle) — `ModelDownloadService.isRunning` is a plain flag so it can't be
  observed, but idle cost drops 4x. `kotlin_paren_check.js` passes on the edited MainActivity.kt; not compiled
  (CI is still the gate).
- **F-19 🟡 `FritVoiceOverlayService.kt` (1,089 lines)** — second-largest Kotlin file; sampled only.
- **F-31 ✅ DONE (2026-10-19):** SensitiveGate audit ring is now a joined-`\u0001`-String with a
  true LIFO 100-entry cap; legacy `StringSet`-typed value migrates on first write (Catch
  `ClassCastException` → remove → store fresh). No reader existed before the fix.
  Meanwhile the wider file (841 lines, fully read 2026-10-19) is excellent: SensitiveGate
  (confirmation-required on invented calls/SMS/trades + kill switches + the call dedupe guard),
  read_screen diagnostics with auto-vision fallback, resume retries, 30-step cap with critique
  notes. Only F-18's ACTION_CALL confirm note stands open.
- **F-20** `android/app/src/main/jni/llama.cpp` is a **vendored third-party tree** (C++/Python) —
  excluded from app audit; do not audit or reformat it.

- **F-42 🔴→✅ DONE (2026-10-19, Week 5) — COMPILE-BREAKER in `core/CrashGuard.kt` (F-15 code):**
  `CrashGuard.uploadPending()` called `NetworkClient.isAuthorized()` / `NetworkClient.sendCrash(...)`
  but the file never imported `com.frit.app.data.NetworkClient` → `Unresolved reference 'NetworkClient'`
  at CrashGuard.kt:83 and :92. The F-15 crash-upload feature (server + client) had therefore
  **never compiled** — the audit's brace scanner cannot see unresolved references, which is
  why the static checks passed. Fixed by adding the import. The app now compiles.
  - **Lesson for this audit: the brace/paren scanner is NOT a type checker.** Any Android change
    must be validated with `:app:compileDebugKotlin`, not just the scanner. The scanner stays
    useful as a quick gate but is not sufficient.

### 2.3c Android — Week 5 (2026-10-19): build gate + F-18a call confirmation

- **F-18 🟠→✅ DONE (2026-10-19, Week 5) — (a) the call-confirm gate is now real.**  The open note said: "`makeCall` fires `Intent.ACTION_CALL` directly (places the call with no
  confirmation step) — add a confirm gate for automation-initiated calls". The gate was NOT
  actually complete, and the audit had reasoned that "the local `call X` fast path only fires on
  the user's own utterance". That reasoning was wrong — there were three real bypasses:
  1. `AutomationEngine.tryLocalAction` handles "call X" itself and is reached from
     `executeLocalOnly`, i.e. the brain's generic `execute_local_action` tool. SensitiveGate
     only knows the explicit `make_call` tool name, so this path never passed the gate.
  2. The on-device LLM's tool loop (`LocalInferenceEngine.runLocalAgenticLoop`) can emit a
     `call` tool on its own initiative — and it is influenced by on-screen text.
  3. `LocalInferenceEngine.executeLocal("call", …)` fired `ACTION_CALL` directly.
  - **Fix:** new pure `core/CallGuard.kt` decides PLACE_NOW / CONFIRM_FIRST / DENY. A call is
    only placed outright when the user's OWN utterance named the target; otherwise
    `Intent.ACTION_DIAL` opens the dialer with the number pre-filled and the user taps call.
    The `allow_calls` kill switch is honoured on every path. Wired into
    `AutomationEngine.makeCall/tryLocalAction`, `LocalInferenceEngine.executeLocal` (which now
    takes the originating `userUtterance`), and `IntegrationCoordinator`'s `make_call` +
    `execute_local_action` handlers. 12 JVM unit tests in `CallGuardTest.kt`.
  - (b) `resolveContact` exact-match disambiguation was already ✅ DONE (F-18b, Week 4).

- **F-43 🟠→✅ DONE (2026-10-19, Week 5) — STREAMING AI CHAT (server + Android).**
  The chat was one blocking request: the user waited in silence for the whole reply to be
  generated, downloaded and parsed. Now:
  - **Server:** new `POST /agent/chat/stream` returns `text/event-stream`.
    `event: delta` carries text as the model produces it, `event: done` carries the final
    reply, `event: error` carries the failure. Billing is unchanged: ONE credit per turn,
    debited after success; a client that disconnects mid-stream is not billed.
    - The chat-vs-task router is itself streamed, so the classification arrives in the first
      few characters: "CONVERSATION …" streams as the answer, "TASK" stops the stream and hands
      the request back to the agent loop (no double work, no double billing).
    - New pure modules: `src/streaming.js` (SSE parsing, delta extraction, route
      classification) and `src/streamProvider.js` (provider stream → events).
      **23 unit tests** in `server/test/streaming.test.mjs`.
  - **Android:** `NetworkClient.streamChat()` (OkHttp, reads the SSE line by line) +
    a pure `data/SseParser.kt` (**9 JVM unit tests**). `IntegrationCoordinator.chatStreamed()`
    drives it and publishes the partial text on a `StateFlow`; `MainActivity` renders a live
    provisional bubble that grows as the tokens arrive, and the final message is added WITHOUT
    the typing animation (so the text is not shown twice). A task falls through to the existing
    agent loop unchanged.
  - **Two real bugs found and fixed while proving this works (both would have made streaming
    silently useless):**
    1. `node-fetch` v3's `res.body` is a Node `PassThrough`, NOT a web `ReadableStream`, so
       `res.body.getReader()` does not exist. The reader now handles BOTH shapes; the naive
       `res.json()` fallback had buffered the entire response, which defeats streaming entirely.
    2. **`req.on("close")` fires as soon as the request body is consumed** (express.json()
       already read it), so the disconnect handler aborted the upstream fetch instantly and every
       stream came back as a single chunk. The handler must listen on **`res.on("close")`**.
       This one is a trap worth remembering for any future SSE/streaming route.
  - **Verified live** against the running server: a 200-word reply arrives as **91 delta frames
    across 14 socket reads** (first frame ~2.9s, last ~4.2s) instead of one buffered blob; a
    task request correctly returns `task: true` with no deltas.



Covered this round (full reads): `core/` remainder (ChatHistory, ChatSession, DataWipe, AdminStore,
CreatorWatcher, DeviceStateCollector, A11yKeeper, RouteController, NotificationSanitizer,
SubAgentOrchestrator, LocalMacroEngine, MacroAction, UserProfile, FritAgentService,
FritAssistant{,Session}Service), `core/llm/*` (LocalInferenceEngine, LocalLLMEngine, ModelManager,
ModelDownloadService, NativeLib), `features/trading/*` (TradingEngineClient, TradingRepository,
TradingCache, TradingScanScheduler, SignalNotificationManager), `features/market/*` (MT5Agent,
MarketEngine, MarketAnalysisEngine, FritAccessibilityService), `features/automation/*`
(AutoModeService, FritBackgroundService, AutomationScheduler, AutoModeApps, SessionWakeLock),
`features/call/CallConversationManager`, `features/veil/*` (VeilManager, FaceVerifier,
VeilActivity, PickupMonitor), `features/wake/*` (LocalWakeWordEngine, FritWakeWordService),
`features/vision/*` (VisionEngine, ScreenCaptureService, ContinuousVisionPipeline),
`features/memory/MemoryEngine`, `data/NetworkClient` (all 801 lines), `ui/trading/TradingSuiteScreen`,
plus `AndroidManifest.xml`, `app/build.gradle.kts`, `res/xml/*` (@xml resources all exist).
Still not fully read: `FritVoiceOverlayService.kt` (1,089 — F-19 note stands), `FritDesign.kt`,
`ArtifactViews.kt`, `FritVoiceUI.kt`, creative screens (ImageEditor/Cinema/Design/Storyboard/Vide—
low-risk UI), MainActivity remainder beyond F-14. **No compile toolchain on this host (no kotlinc,
no java)** — verification below is static; the repo's `android-ci.yml` remains the build gate.

- **F-35 🔴→✅ DONE (2026-10-19) — COMPILE-BREAKER in TradingSuiteScreen.kt:499:** `var signalEnabled
  by mutableStateOf(true) }` — stray `}` closed the `BotPairDialog` composable body early and the
  `remember {` wrapper was missing. This file could never have compiled; nothing ever built this
  side locally, which is exactly why it survived. Fixed to `var signalEnabled by remember {
  mutableStateOf(true) }`. A tree-wide brace/depth scanner (`scripts/kotlin_paren_check.js`,
  string-literal-aware) now passes on **all 117 Kotlin files** (0 imbalances, 0 negative depths) —
  keep running it after any hand-edit until CI builds exist.
- **F-36 🟠→✅ DONE (2026-10-19) — Android market analysis silently dead for forex:**
  `MarketAnalysisEngine.fetchViaGet/fetchViaPost` hit `/market/analyze` with **no Authorization
  header**, while the server route is `requireAuth` (index.js:4191) — every call got HTTP 401 and
  forex analysis degraded silently to the crypto-only offline fallback / "NO DATA". Fixed: both
  calls now send `Bearer installKey|TokenStore|FRIT_AUTH_TOKEN` (same identity order as
  NetworkClient). (Note: `MarketEngine.kt`'s `/market/batch` also lacks auth, but the whole object
  is dead code — zero callers — so it is recorded, not fixed.)
- **F-33 🟠→✅ DONE (2026-10-19) — DataWipe was a half-erasure:** cleared ChatSession/memory/token
  but NOT the saved multi-chat archives (`frit_chat_history`), the creator ADMIN_TOKEN + seen-ids
  (financial), the veil PIN hash/salt + enrolled face refs (`frit_veil`, `frit_veil_face`), the
  offline trading cache (`frit_trading_cache`, wallet/portfolio rows), or the persisted task queue
  (`frit_task_queue`). "Delete my data" now really deletes it (GDPR-style erasure complete).
- **F-37 🟡→✅ DONE (2026-10-19) — foreground-notification ID collision:** `AutoModeService` and
  `FritBackgroundService` both posted `startForeground(NOTIF_ID=3)` with different channels — two
  FGS sharing one notification id clobber each other's content on every tick. AutoModeService id
  is now 33. (FritAgentService=4, ScreenCapture=2, ModelDownload=77, wake=1, A11yKeeper=41,
  CreatorWatcher=9000+, SignalNotificationManager dailySummary=9001 — no remaining collisions.)
- **F-34 🟢 FALSE LEAD (2026-10-19, caught before shipping):** suspected MACD misalignment in
  `MarketAnalysisEngine.macd()` was WRONG — re-derived algebra shows `e12.takeLast(e26.size).zip(e26)`
  already pairs EMA12/EMA26 correctly (offset = e12.size − e26.size = 14 = 26−12 window gap).
  Original code restored byte-identical, with a comment warning future auditors not to "fix" it.
- **F-38 🟢 Hardening notes (unchanged code, recorded):** (a) `FaceVerifier.verify()` fuses the
  separately-best geometry (any ref) and separately-best thumbnail (possibly a different ref) —
  weaker than a per-ref joint threshold; fine for the pickup convenience guard, note before any
  security lift; (b) veil PIN uses single-iteration salted SHA-256 — brute-forceable on-device by
  design (documented convenience lock); (c) `MT5Agent.analyzeAndTrade()`'s `force` keyword path
  and `MarketEngine` have ZERO call sites — dead; if ever re-wired, the force path must go through
  SensitiveGate + the call-confirm gate (F-18); (d) `TradingScanScheduler` wakelock is held across
  the inter-repeat delay — bounded by SessionWakeLock's 10-min timeout, acceptable; (e) backup
  rules exclude `frit_prefs.xml`/models but `frit_chat_session/chat_history/trading_cache` still
  ride Drive backups — deliberate? decide before Play review; (f) network_security_config lists
  `192.168.0.0`-style entries with includeSubdomains — domain matching is hostname-literal, so LAN
  IP cleartext likely doesn't actually open (dev-only inconvenience, not a security hole).
- **Verification this round (static, no build toolchain):** 117/117 Kotlin files pass the
  string-aware brace/paren scanner incl. negative-depth scan (exit codes captured; the scanner
  itself was fixed mid-round — it stripped `//` before string literals and truncated URL-bearing
  lines; fixed order = strings, then comments). Referenced-symbol spot-checks passed (TokenStore
  `get()`/`installKey()`, OfflineSttEngine/VoskSttEngine `transcribePcm`, SessionWakeLock API,
  ChatHistory.chats, UserProfile.clear). Server untouched this round — Week-2's 39/39 suite result
  stands (no re-run needed per ledger). Android CI (`android-ci.yml`) still the real gate.
- **No compile toolchain could be conjured on this host** (kotlinc AND java both absent; installing
  a JDK+NDK was judged out of scope) — Kotlin correctness beyond the static scanner rides on CI.

### 2.3d Server — Week 6 (2026-10-20): live route sweep findings

Method: new **`scripts/w6_debug.sh`** boots the real server on a throwaway SQLite DB and
hammers the routes the Android app actually uses (68 checks over HTTP, 5xx-sensitive), then
measures the Phase-6 performance targets. Two real bugs fell out of it; both are fixed.

- **F-45 ✅ DONE (2026-10-20) — `/api/trading/scan` could hang forever on a stalled provider.**
  The market-data fetches had **no `AbortSignal` at all** (`fetchCandles` Binance ×2 + TwelveData
  + Finnhub stock, `fetchSpotPrice` Finnhub/CoinGecko/TwelveData/Frankfurter/er-api,
  `fetch24hDelta`) and the route awaited symbols **one at a time** with no overall budget. Measured
  on a cold DB: a **2-symbol** scan blew past a 30s client timeout while the same 20-symbol scan
  took **0.9–1.8s** warm — i.e. one black-holed upstream call blocked the whole scan. The Android
  read timeout is 90s (`TradingEngineClient.kt:34`), so this looked like "the app is broken".
  Fix (two parts):
  1. `boundedFetch()` (index.js:72) — every market-data/weather call now carries
     `AbortSignal.timeout(15s)`.
  2. New pure `server/src/concurrency.js` — `runBounded()` with concurrency 3, **12s per symbol**
     and a **20s total budget**; the scan route (index.js:7338) always answers. The response now
     carries `partial: true` plus `missed: [{symbol, reason}]` so an incomplete scan is visible
     instead of silently looking like the whole list. **6 unit tests** (`test/concurrency.test.mjs`).
  Scan of 20 pairs after the fix: **853–1843ms** with 0 failures in the sweep.
- **F-46 ✅ DONE (2026-10-20) — deleting a Creative project returned HTTP 500.**
  `DELETE /creative-suite/projects/:id` (index.js:5979) removed `creative_assets` and then the
  project, but `creative_sync_state` and `creative_collaborators` carry a
  `FOREIGN KEY(project_id) REFERENCES creative_projects(id)` (index.js:349/357). Any project that
  had ever been **synced** (the app syncs on open/edit) or shared therefore failed with
  `FOREIGN KEY constraint failed` — i.e. it could not be deleted at all. Fixed with a child-first
  `db.transaction` (assets → collaborators → sync state → project) plus a real 500 body. Verified
  live in the sweep: create → sync → add collaborator → delete = 200.
- **F-47 🟠 NEW (recorded 2026-10-20) — cold start can block on the remote backup restore.**
  `restoreFromBackup()` (index.js:102) is a top-level `await` (index.js:121) that runs **before**
  `app.listen`. With Supabase configured and a missing DB file, boot waits for bucket-create +
  a 60s object GET while nothing serves `/health` — a slow/half-down Supabase can make a cold
  deploy fail its health check and restart-loop. The restore logic itself is correct (only when
  `DB_PATH` is absent). Week 6 lowered the best-effort bucket create to 5s (was 20s); the 60s
  object fetch was left alone on purpose because capping it could truncate a genuine restore.
  **Suggested fix (needs a data-safety decision, not implemented):** listen first with a 503
  "restoring" health state, or cap the whole restore (~20s) and boot on an empty DB with a loud log.
  Measured across the Week-6 sweep runs: **3.4s** boot when Supabase answered fast, **9.9s** on
  another cold DB — the whole difference was the restore, and none of it serves traffic.
- **F-48 🟡 NEW (recorded 2026-10-20) — the trading suite is half-gated.**
  `POST /api/trading/watchlist` is behind `requireBotAccess` → `checkEntitlement(uid,"trading")`
  (index.js:7227/7264) and `free` only includes `chat`+`market_data`, so a fresh install gets
  **402** adding a pair. Meanwhile `POST /api/trading/scan` (the expensive one — 20 symbols and
  upstream market data), the signal history, portfolio and risk reads are **not gated at all**.
  So the paid feature's cheapest write is locked while its most expensive read is free. Product
  decision needed (gate scan too, or ungate watchlist-add); recorded, deliberately not changed.
- **F-49 ✅ DONE (2026-10-20) — the Design Studio's graphics model was retired upstream.**
  The default `OR_DESIGN_MODEL` was `inclusionai/ming-image-0.1-design`, and OpenRouter's
  `/images` catalogue (61 models, checked live) no longer serves it: every Design Studio job
  burned a failed call before falling back, and the new agent (which used one model) failed
  outright with `No endpoint found for model "inclusionai/ming-image-0.1-design"`. Fixed:
  the default is now `bytedance-seed/seedream-5-0-flash` (served), and the agent walks a
  **model chain** (graphics → image) so a retired slug can never kill a finished brief again.
  Ops note: any deployment still overriding `OR_DESIGN_MODEL`/`OR_GRAPHICS_MODEL` via env or
  the Creator console should be re-pointed at a served slug.
- **Design Agent — NEW FEATURE (2026-10-20).** The Design Studio was one shot; it is now a
  three-stage agent, exactly as requested: **(1) research** the web for visual references,
  **(2) turn them into one detailed brief** (canvas, grid + alignment, 60-30-10 palette with
  hexes, per-role typography with real font/size/weight/tracking/case, elements with placement,
  imagery, background, exact copy), **(3) render, then verify the render against the brief** and
  retry once with the reviewer's own complaints as corrections.
  - New `server/src/creative/designAgent.js` (~600 lines, fully dependency-injected) +
    `POST /creative-suite/design/agent` (requireAuth, limitCostly, image entitlement, billed
    once per DELIVERED design, never on failure; job row in `creative_jobs`, kind
    `design_agent`). Catalog updated.
  - Research is keyless and degrades gracefully: Bing image search → Openverse API fallback,
    with URL ranking that drops avatars/icons/SVGs and prefers Pinterest/Behance/Dribbble.
    References are read by the vision model and reduced to transferable design DNA.
  - **22 unit tests** (`test/designAgent.test.mjs`): query planning, HTML URL extraction,
    ranking, hex/brief normalisation (user copy wins, sizes/weights clamped, shares normalised),
    prompt assembly, verification judgement, vision-JSON retry, orchestrator success /
    inspiration-failure / verify-fail-retry / verify-off / no-generator paths.
  - **Live run (paid, throwaway DB): 2 references read → brief (copy, palette, type, layout) →
    render on Seedream → review 0.82 PASS** with 5 concrete issues in 49s end-to-end.
  - Two real defects were found while proving it, both fixed: a bare `image_b64` was passed to
    the vision model as-is (it replied "no render was provided" — now normalised to a data URI
    and downscaled to 1024px JPEG), and a prose (non-JSON) reviewer/brief reply silently
    produced defaults (now retried once with a JSON-only nudge).
  - Android: `NetworkClient.designAgent(...)` + an **Agent mode** checkbox in
    `DesignStudioScreen` that shows the brief (layout/palette/typography), the reference count
    and the review verdict. `:app:compileDebugKotlin` BUILD SUCCESSFUL.
- **Notes (no code change):** (a) `/api/trading/paper-portfolio` on a fresh DB logs
  `[paper-portfolio] options: no such table: options_paper` once and returns 200 without the
  options section — graceful and self-heals; (b) the chat router is conservative: "hi" streams
  deltas, but "what is the capital of France?" is classified **TASK** (falls back to the agent
  loop, no deltas). Not a defect in code (prompt judgment), but stream tests should use a
  greeting; the Week-5 streaming verification stands.

### 2.4 Strategy layer — minor findings (full read 2026-10-18)

- **F-30 🟠→✅ DONE `percentileRank` tie-saturation scored 0%** (engine.js): a series whose last
  value equals many prior values (clean monotonic tapes pin Wilder DI- at exactly 0 → ADX
  exactly 100) counted zero "strictly below" → percentile 0 → strong 4H trend judged NEUTRAL.
  Fixed with midpoint tie handling (ties count ½; all-tie → neutral 50; unique max stays top).
  Regression test included. NOTE: `>= 90` (not `== 100`) is the correct unique-max expectation —
  self-inclusion means unique max = `(n-1+0.5)/n`.

- **F-21 ✅ DONE (2026-10-19):** finnhub `fetchCandles` 4h aggregation now groups by true 4h bucket
  (`Math.floor(ts / 14400e3)`, time = bucket start) — matches CandleStore's live carving.
- **F-22 ✅ DONE (2026-10-19):** alpaca_feed WS reconnects with 1s→60s backoff on close, resetting
  on auth success; `stop()` clears the retry timer (no zombie reconnects).
- **F-32 ✅ DONE (2026-10-19, found by F-07 tests):** `sessionClass()` returned "fx" for crypto,
  contradicting its own documented "24/7 (crypto+synthetics)" contract (the engine is unaffected —
  `sessionStatus` double-covers via CRYPTO_SET). Now returns "always" for the crypto pairs in the
  Binance universe (with or without the USD quote).
- **F-23 ✅ DOCUMENTED (2026-10-19):** "daily cap" in DerivExecutor/AlpacaExecutor is a rolling 24h count;
  `bots.js` `runsToday` is calendar-day (UTC via SQLite `date('now')`). Intentional difference, no code change —
  describe caps as "per 24h" in any user-facing copy for the executors.
- **F-24 🟡 ACCEPTED:** `chains.js` dead `void cutoff` left as is (zero behavior impact); Yahoo scraping stays
  provisional, Tradier is the stable path.
- **F-23 (original note):** **Semantics**: "daily cap" in DerivExecutor/AlpacaExecutor/bots is a rolling-24h count,
  not calendar day (bots.js `runsToday` IS calendar-day). Harmless but worth documenting.
- **F-24 🟡 `chains.js` dead code** (`void cutoff`); Yahoo chain scraping is intentionally
  provisional/unofficial (documented) — expect breakage; Tradier path is the stable one.
- **F-25 🟢 Strategy layer overall is exceptionally clean** — demo-first executors with refusal
  audit trails (deriv.js), live-locked Alpaca with market-hours gate, generator-aware synthetics
  (unknown = WAIT), conservative journal scoring (both-touch = SL first), touch-fill options
  modeling with fee gates, ledger maturity gate (30 trades/60 days before any performance claim),
  budget-guarded backfill, WsBase reconnect+23h recycle. Do NOT refactor these for style.

### 2.5 Clean modules (full read — no action needed, don't re-audit)

- `strategy/engine.js` — sound MTF design; only defect is F-03's trendStart bug.
- `strategy/risk.js` — clean design except F-04 fail-open.
- `strategy/bots.js`, `scheduler.js` (except F-05), `hysteresis.js`, `rails/router.js` — clean;
  hysteresis + router are pure & testable.
- `strategy/position_monitor.js`, `costs.js`, `deriv.js`, `deriv-rest.js`, `alpaca.js`,
  `synthetics.js`, `longterm.js`, `journal.js`, `news.js`, `ledger.js`, `volengine.js`,
  `volumematrix.js` — clean.
- `strategy/feed/*` (ws_base, binance_ws, finnhub_ws, dukascopy_poll, deriv_poll, alpaca_feed,
  candle_store, live_feed) — clean except F-21/F-22.
- `strategy/options/*` (pricing, structures, scanner, backtest, paper, chains, fx) — clean except
  F-24; `pocket/engine.js` clean; `deep_search.js` clean (HTML-scrape fragility is inherent —
  markup changes will silently break engines; the 4-engine parallel design absorbs it).
- `strategy/hysteresis.js`, `rails/router.js` (fixed by F-32), `options/pricing.js` — now covered
  by unit tests in `server/test/strategy.test.mjs` (39-test suite total as of 2026-10-19).
- All `server/src/creative/*` + `model_config.js` — clean.
- Android creative stack (`CreativeSuiteEngine.kt`, `CreativeModels.kt`, `CreativeSuiteScreen.kt`,
  `CreativePlanGate.kt`, `TokenStore.kt`, `PrivacyGuard.kt`) — clean.

---

## 3. COVERAGE LEDGER (so nobody re-reads what's done)

**FULLY READ ANDROID (findings recorded above):**
- `MainActivity.kt` (all 3,951 lines), `features/automation/AutomationEngine.kt` (all 557),
  `core/IntegrationCoordinator.kt` (all 841)
- `core/TokenStore.kt`, `core/PrivacyGuard.kt`, features/creative/* (4 files),
  `ui/creative/CreativeSuiteScreen.kt`
- `.github/workflows/android-ci.yml`, README.md, PLAYSTORE_RELEASE.md, TRADING_SUITE_PLAN.md

**SERVER IS 100% READ (2026-10-18)** — all files under `server/src/` (index.js 7,725;
strategy/ = 17 root files + feed/ 8 + options/ 7 + pocket/ 1 + rails/ router.js; creative/ 6;
deep_search.js; model_config.js), plus `server/test/` and `package.json`. **Do not re-read the
server.** Week 6 added `server/src/concurrency.js` (+6 unit tests) and exercised the routes over
live HTTP instead of re-reading them — see §2.3d. Corrected inventory note: `rails/` contains ONLY `router.js`; `deriv.js`/`alpaca.js`
live at `strategy/` root; there is no `signal_store.js` (journal.js covers setup recording).

**PARTIALLY READ — finish these (append findings, keep the ledger current):**
- `android/.../data/NetworkClient.kt` — first ~200 of 793 read (rest = endpoint methods + error map;
  skim for: which calls send the master token vs install key, retry behavior, file-upload paths)
  — **Week 5: read/edited the streaming path (`streamChat`) and the auth/post helpers.**
- `android/.../core/IntegrationCoordinator.kt` — read for the SensitiveGate, the call tools and
  the agent loop; the streaming path was added in Week 5.

**UNREAD ANDROID (read in this order):**
1. `core/` — list via `find android/app/src/main/java/com/frit/app/core -name '*.kt'` and read all:
   CrashGuard, DataWipe, AdminStore, ChatHistory, UserProfile, A11yKeeper, RouteController,
   PrivacyScrubber (rest), llm/ModelManager, llm/LocalLLMEngine, llm/ModelDownloadService, etc.
   — **Week 5: `core/` was swept in Week 3; Week 5 added `core/Diag.kt` (F-16), `core/CallGuard.kt`
   (F-18a) and read `CrashGuard`/`SecureStore`/`TokenStore` closely.**
2. `features/` remainder — automation (AutoModeService, AutoModeApps, AutomationScheduler,
   NotificationWatcher), voice (VoiceEngine, VoiceActionManager, FritVoiceOverlayService 1,089),
   wake, veil, memory, market, calls
   — **Week 5: the catch audit (F-16) swept voice/veil/vision/market/automation. Still not fully
   read: `FritVoiceOverlayService.kt` (1,089 lines — F-19 note stands), `FritDesign.kt`,
   `ArtifactViews.kt`, `FritVoiceUI.kt`.**
3. `ui/` remainder — trading suite screens, FritSidebar/TopBar/InputBar/ChatBubble, creative extras
   — **Week 5: `ui/creative/*` swept for F-16; `ui/trading/TradingSuiteScreen.kt` swept.**
4. `android/app/build.gradle(.kts)` + `AndroidManifest.xml` + `res/xml/*` (small; config hygiene:
   check exported=false everywhere, permissions minimality, network security config)
   — **Week 5: `build.gradle.kts` edited (added `org.json:json` test dependency).**
5. SKIP `android/app/src/main/jni/` entirely (vendored llama.cpp — see F-20)


---

## 4. RECOMMENDED UPGRADE BACKLOG (ordered — pick up anywhere)

**Week 1 ✅ DONE (2026-10-19):** F-01 graceful shutdown, F-02 exit-on-fatal, F-03 trendStart fix
+ golden tests, F-04 fail-closed risk, F-05 scheduler persistence, F-30 percentileRank regression.
Verified: `npm test` 32/32 (incl. all 23 original), `node --check` clean on index.js/engine.js/
risk.js/scheduler.js — exit statuses captured. NOT verified live (staging): SIGTERM on Deploy,
sweeper/first-restart task restore with real scheduler timers.
**Week 2 ✅ DONE (2026-10-19):** F-10 CORS allowlist + F-10b ENFORCE_BOUNDED_MONEY flag,
F-11 WEBHOOK_SECRET, F-15 crash ingest (server + Android client), F-21 finnhub 4h alignment,
F-22 alpaca_feed reconnect, F-31 SensitiveGate audit ring, F-32 sessionClass crypto contract
(found by the new F-07 tests). F-07 ✅ DONE — `server/test/strategy.test.mjs` (hysteresis,
rails router, BS put-call parity, impliedVol round-trip, greeks) — full suite 39/39.
Deployment notes: set `WEBHOOK_SECRET`, `CORS_ORIGINS` (optional), `ENFORCE_BOUNDED_MONEY=1`
in production ".env". Android changes build on the next CI PR run (no local SDK here).
Still open: F-26 EncryptedSharedPreferences (needs an androidx.security gradle dependency —
not verifiable without a build; schedule for a booth pass with Android Studio), F-18 call
confirm gate + exact-match contacts (behavioral change — coordinate with call flows first).
**Week 3 ✅ HEAD START (2026-10-19, this round):** Android side (the "never built" half) swept.
Findings + fixes below (F-33..F-36). Remaining Week-3+ backlog: F-14 MainActivity split
(incremental) → streaming responses for AI chat → entitlement/credit middleware extraction
(enables F-07 server testing) → F-18 automation confirmations (Android Studio pass together
with F-26/F-16), plus the new notes F-37..F-41 recorded this round.
**Week 4 ✅ PARTIAL (2026-10-19):** F-06 bot lot sizing, F-08 money-path catch logging, F-12 image upload
validation, F-29 adaptive poll, F-18b contact disambiguation (details in §2). Verified: `node --check` clean on
index.js (EXIT=0), decoder spot-tested (png ok; text/empty/25MB rejected), Kotlin brace scanner clean on both
edited files. NOT re-run: `npm test` (server not copied whole to the sandbox) — run `cd server && npm test`
(expect 39/39) before shipping. Still open for Week 5+: F-26 (needs androidx.security gradle dep + Android
Studio), F-16 Kotlin catch audit, F-14 MainActivity split, streaming chat, entitlement middleware extraction,
F-12 video/audio ingest validation, F-13 creative quality, F-23/F-24/F-09 doc notes.
**Anytime cheap:** F-08/F-16 catch audits, F-13 creative quality items.
**Ops (~$5/mo):** always-on hosting for the trading feed (TRADING_SUITE_PLAN says 24/7 WS scanning
needs it; free-tier sleep fights cold-start UX) — revenue protection for the billing fixes in §1.

**Week 5 ✅ (2026-10-19) — the "never compiled" problem is over:**
- **Entitlement/credit middleware extraction ✅ DONE** (the item Claude had started). New
  `server/src/billing.js` — `createBilling({db, SUB_TIERS, activeTier, monthStr, CREDIT_COSTS,
  FEATURE_CHARGE, FEATURE_LABELS})` returns `creditBalance / creditTxn / grantMonthlyCredits /
  planAllows / checkEntitlement / debitCredits / trackProviderJob / chargeProviderJob`. index.js keeps
  thin wrappers with the same names (lazy `billing()` because the config consts are declared further
  down), so **no route code changed**. 9 unit tests in `server/test/billing.test.mjs` (anti-farming
  grant, mid-month upgrade, gate-never-debits, atomic debit/no-overdraft, bill-once-on-completion).
- **F-13 creative quality ✅ DONE** — see §2.2 for the three sub-items.
- **F-18a call-confirm gate ✅ DONE** — see §2.3c.
- **F-42 compile-breaker ✅ DONE** — see §2.3c.
- **F-16 Kotlin catch sweep ✅ DONE (creative/voice/veil/vision/trading)** — the unswept files now log
  through `core/Diag.kt` on their network/IO/money catches (brand-kit create, project/template/
  element/storyboard loads, lip-sync poll, prompt suggestions, cinema recipes+options, TTS language,
  offline STT engines, veil scheduled-work check, face-ref clear, screen-capture auto-start,
  accessibility tap, trading alert-prefs save, background-service teardown, AutoMode init/sweep).
  Deliberately NOT touched (probe-and-ignore patterns where a throw is the expected outcome):
  package-lookup loops, `ALTER TABLE` migration guards, `audioRecord.release()`, `recognizer.destroy()`,
  `resolver.delete()`, TTS setSpeechRate, and every catch around `TokenStore`/`FRIT_AUTH_TOKEN`
  (Constraint #1).
- **F-43 streaming AI chat ✅ DONE** — see §2.3c. New `POST /agent/chat/stream` (SSE) on the
  server, `NetworkClient.streamChat` + `data/SseParser.kt` on the phone, and a live
  provisional bubble in the chat. Two real streaming bugs were found and fixed on the way
  (`node-fetch` v3's body is a Node stream, not a web ReadableStream; and `req.on("close")`
  fires as soon as the request body is read, so the abort handler must listen on `res`).
- **Test-count note (important for whoever runs the suite next):** the unit-test count went
  48 → 136. The old notes that say "expect 39/39" or "23/23" are STALE — the `npm test` glob
  picks up every file in `server/test/`, so adding a suite raises the total. The suites now are:
  `billing` (9) + `brand` (10) + `creative` (23) + `engine` (16) + `streaming` (23) +
  `strategy` (39) + `subtitles` (14) = **136**.
- **Verification:** `cd server && npm test` → **136/136 pass** (was 48). `node --check` clean on
  index.js, billing.js, streaming.js, streamProvider.js, creative/aiFeatures.js,
  creative/subtitles.js, creative/brand.js and the sandbox server. Android:
  `:app:compileDebugKotlin` **BUILD SUCCESSFUL** and `:app:testDebugUnitTest` **32/32 pass**
  (PrivacyGuard 11, CallGuard 12, SseParser 9). The Kotlin brace/paren scanner passes on
  **121/121** files (0 imbalances). The SSE endpoint was verified LIVE against the running
   server (25 deltas over 5685ms→6487ms for a chat reply; `task: true` for a task request).

**Week 5 (second pass, 2026-10-20) — the remaining open items, closed.**
This pass was asked to finish everything still open except the Play Store work. It ran
*alongside* the Week 6 agent, so both sets of changes are in the tree.

- **F-14 ✅ DONE — `MainActivity.kt` split.** 3,850 → **1,313 lines**. The 15 self-contained
  screens/composables now live in their own files under `ui/`:
  `ui/chat/ChatContent.kt`, `ui/chat/ChatLeafComposables.kt`, `ui/chats/ChatsScreen.kt`,
  `ui/settings/SettingsScreen.kt`, `ui/automation/AutomationScreen.kt`, `ui/wallet/WalletScreen.kt`,
  `ui/creator/CreatorScreen.kt`, `ui/model/OnDeviceModelSection.kt`, `ui/MainUiHelpers.kt`.
  It was a PURE move — every moved block is byte-identical to the original, `class MainActivity`
  and `fun MainScreen` stayed put, and the `tradeToConfirm` trade dialog is untouched. Two
  duplicated helpers that had drifted were consolidated at the same time:
  `normalizeAssistantReplyForUi` (existed in BOTH the voice overlay and `ui/`, so the overlay
  could parse a different envelope shape than the chat UI) and the `naira()` formatter
  (existed in the wallet AND creator packages) — one copy each now.
- **F-19 ✅ DONE — `FritVoiceOverlayService.kt` fully read** (it had only been "sampled"). 30
  findings (2 HIGH / 15 MEDIUM / 13 LOW). All 2 HIGH and 8 MEDIUM fixed — see §2.3c for the
  list. The two HIGH ones were real safety bugs:
  1. `chatTaskJob` was a SINGLE slot, so a second in-flight send made the live job
     untracked — Stop / overlay-close / the stranger-pickup pause all cancelled nothing, and an
     agent that can drive the phone and place trades kept running after the user cancelled it.
  2. The overlay's "End all" button called `AutomationScheduler.cancelAll()`, which cancels
     running jobs AND permanently deletes every saved schedule — one tap in a floating overlay
     destroyed the whole automation config, with no warning and no undo.
- **F-07 ✅ mostly DONE — 86 new strategy unit tests** (`server/test/strategyMore.test.mjs`),
  which surfaced **7 real bugs**, all fixed: `volumematrix` over-counted volume (float drift
  made the bucket walk visit 11 buckets where the divisor said 10, mislocating the POC and the
  70% value area); `pocket/engine` published confidence 89 despite a documented cap of 78 (the
  clamp was applied before the calibration blend and never re-applied); `volengine` had no
  IV-history fallback on the CHEAP side, so a 50% discount to realized vol was reported as
  "fair premium" and `LONG_STRADDLE` was unreachable; `costs` published a rounded R-R while
  the gate used the raw value (a trade could show "1.0" and be flagged blocked simultaneously);
  `longterm` never mentioned the RSI veto (users were told "no breakout" when the breakout had
  happened); `synthetics` returned no `details` object on the TREND SELL branch; and
  `pocket/engine.assessPullback` threw a TypeError on an empty trigger array.
  **Still untested: `structures.js` only.**
- **F-08 ✅ CLOSED (both halves).** The Week 6 agent closed the server half; this pass swept
  the same tree for error-LOSS (not empty catches): 0 empty catches, 19 non-logging catches read
  and verified to hand the error back to the caller, and 4 places that genuinely lost the error
  now log it (weather lookup, chat-router fallback, provider retry, storyboard retry).
- **The two streaming bugs are now PINNED by tests** — `server/test/streamRegression.test.mjs`
  (10 tests). Both bugs were found by LIVE testing while the unit tests were still green, so
  the tests are mutation-verified: re-introducing either bug makes them fail.
- **F-26 — `SecureStore.clearAll` bug found and fixed.** The encrypted side was cleared
  wholesale but the legacy plaintext file only had a hand-typed list (`app_pin`, `admin_token`)
  removed — so the moment a third key was added, "delete my data" would leave that key's
  PLAINTEXT copy in `frit_prefs` forever. One `MANAGED_KEYS` list now drives both sides.
- **BACKUP — the project is now under version control** (see §0 constraint 3, which was wrong):
  - `uchechukwu090/frit_andriod` — the root repo (Android app, docs, scripts, CI, the vendored
    llama.cpp tree). **`server/` and `sandbox and docker/` are now proper SUBMODULES** pointing
    at `uchechukwu090/frit-android` and `uchechukwu090/sandbox`. Before this they were bare
    gitlinks with no `.gitmodules`, so a plain `git clone` produced a tree with an EMPTY
    `server/` — the entire API server was missing from the backup. `git clone --recursive` now
    restores the full three-repo project (verified by a clean clone).
  - The `llama.cpp` tree is **vendored** (tracked directly, not a submodule): the copy that
    arrived here had a `.git` index out of sync with the files on disk (2,194 phantom
    deletions + 2,051 untracked entries, zero genuinely modified files). Committing the
    sources keeps a plain clone buildable with no network. Upstream commit recorded in
    `android/app/src/main/jni/llama.cpp/VENDORED.md`.
  - **SECURITY — the live `AUTH_TOKEN` was committed in plaintext** in `PLAYSTORE_RELEASE.md`
    (the "grep <token>" instruction pasted the real value). It has been redacted and the check
    now reads the value from `server/.env` at runtime. **That token must be rotated before the
    repo is made public** — a credential that has been in a tracked file is compromised.

### 5d. HOUSE RULES LEARNED THE HARD WAY (read before touching this repo)

1. **The project is THREE repos, not one.** `frit_andriod` (root) + `frit-android` (server) +
   `sandbox`. Change the right one, and `git clone --recursive` to restore.
2. **Never `git add -A` at the root while another agent is working in the tree** — it sweeps up
   their half-finished files into your commit. (It happened here; nothing was lost, but the
   commit was messy.)
3. **A file can be corrupted to all-NULL bytes in place, with its size preserved.** Two files
   (`ui/creator/CreatorScreen.kt`, `ui/wallet/WalletScreen.kt`) were found this way after a
   concurrent agent had written them. Detection: read the bytes and count zeros — a Kotlin
   source file should have none. Recovery: the pre-split `MainActivity.kt` was still in git, so
   the moved blocks could be extracted back byte-for-byte. **Commit early and often.**
4. **PowerShell 5.1 `>` redirection writes UTF-16**, not UTF-8. `git show HEAD:file > out.kt`
   silently produces a UTF-16 file that then fails to compile. Use `cmd /c "git show ... > file"`
   for raw bytes, or `-Encoding utf8` on `Out-File`.
5. **The brace/paren scanner is NOT a type checker** (F-42) and it also mis-reports on files
   whose strings contain braces. The Gradle compile is the only real gate.
6. **`scripts/kotlin_paren_check.js` needs file arguments** — it does not walk a tree.
7. **The `npm test` count moves.** `server/test/*.test.mjs` is a glob, so every new suite
   raises the total. Read the current number off a fresh run rather than trusting a note.


**Week 6 ✅ (2026-10-20) — the debug round: exercise the app's routes over real HTTP.**
Previous weeks proved things with tests and static reading; this one booted the real server on a
throwaway DB and called 68 app-facing routes (trading, billing, creative, journal, crash ingest,
SSE chat), which is where the two real bugs below were hiding.
- **F-45 ✅ scan deadline** — unbounded market-data fetches + sequential scan could hang forever.
  New `boundedFetch` (15s) + `runBounded` (3 concurrent, 12s/symbol, 20s budget) + `partial`/`missed`
  in the response. 6 new unit tests. 20-pair scan now 0.85–1.8s.
- **F-46 ✅ project delete 500** — FK from `creative_sync_state`/`creative_collaborators` made any
  synced project impossible to delete; now a child-first transaction. Verified live.
- **F-08 ✅ server catch sweep closed** — `scripts/js_catch_audit.js`: 49 files, 0 empty catches,
  all 22 non-logging catches reviewed as handled (see §2.1).
- **Design Agent shipped** — `POST /creative-suite/design/agent` (research → detailed brief →
  render → verify-with-retry), 22 tests, Android agent mode in the Design Studio. Proving it
  live also exposed **F-49** (retired graphics model slug), now fixed.
- **F-47 / F-48 🟠🟡 recorded** — cold-start restore blocks `listen` (up to 80s worst case); the
  trading paywall gates watchlist-add (402) but not the expensive scan endpoint. Both need a
  product/ops decision, so they are documented rather than silently changed.
- **Phase-6 performance numbers (local, throwaway DB, warm feed):** boot 3.4–9.9s (all of the
  variance is the Supabase restore on a cold DB — see F-47), then
  Supabase restore on a cold DB), `/health` 0.39–0.72s, `/api/trading/feed/status` 0.44–0.68s,
  `/api/trading/portfolio` 0.48–0.76s, **scan 20 pairs 0.85–1.84s (target <2s — met)**,
  streamed chat: done frame in ~2–5s, deltas on a greeting. The Android-side cached-dashboard
  target needs a device run (unchanged blocker).
- **Verification:** `node --check src/index.js` clean; `npm test` **142/142** (was 136, +6);
  `bash scripts/w6_debug.sh` **68/68 checks, exit 0** (and it exits non-zero on any 5xx / shape
  break, so it is a reusable regression gate); Android `:app:compileDebugKotlin`
  **BUILD SUCCESSFUL** and `:app:testDebugUnitTest --rerun` **32/32**
  (CallGuard 14, PrivacyGuard 9, SseParser 9 — the Week-5 note said 12/11/9, the real split is 14/9/9).

### 5c. WHAT IS STILL OPEN after Week 6

*(updated 2026-10-20 — the Week 5 second pass closed F-14, F-19, F-08 and most of F-07; see
the Week 5 second-pass block in §4. Only what is genuinely still open is listed here.)*

- **F-07 (strategy layer tests) - ONE module left.** `strategy.test.mjs` covers `hysteresis`,
  `rails/router`, `options/pricing`; `strategy_money.test.mjs` (41 tests) covers `risk.js`,
  `costs.js`, `journal.js`, `position_monitor.js`, `bots.js`, `volumematrix.js`, `volengine.js`,
  `synthetics.js`, `longterm.js` and `ledger.js`; **`strategyMore.test.mjs` (86 tests, Week 5
  second pass) adds `pocket/engine.js` and edge cases for the rest.** Still untested:
  **`structures.js`** (plus `deep_search.js` scraping, which is inherently network-bound).
- **F-26 EncryptedSharedPreferences - the on-device pass is still the only thing missing.** The
  CODE is done (`SecureStore.kt`) and the gradle dependency resolves; `clearAll` was hardened
  this pass (it used to leave a newly-added key's PLAINTEXT copy behind after a wipe). What
  remains is the real-device check: set a PIN, force-stop, reopen, confirm it survives, and
  confirm `frit_prefs.xml` no longer holds `app_pin`. Needs a device or emulator.
- **F-47 cold-start restore blocks `listen`** - needs an ops/data-safety decision (see 2.3d).
- **F-48 trading suite half-gated** - watchlist-add is paywalled (402) while scan is free; pick a
  side before the next pricing change (see 2.3d).
- **Play Store work** - still deferred by explicit user request (6).
- **Ops:** always-on hosting for the trading feed (~$5/mo) - see the Ops note in 4.
- **Rotate the production `AUTH_TOKEN`.** It was committed in plaintext in
  `PLAYSTORE_RELEASE.md`; it is redacted now, but the credential must be treated as compromised.
  See the Week 5 second-pass block in 4.
- **Decide whether `frit_andriod` should be public.** It is currently PRIVATE. It is a
  backup today; if it is going to be the working repo, the (now redacted) secret history and
  the `server/.env` exposure deserve a look before flipping it.




---

## 5. VERIFICATION RECIPE (for whoever changes server code)

```bash
cd server
node --check src/index.js          # syntax gate
npm test                           # 254/254 at the time of writing (billing, brand, concurrency, creative, designAgent, engine, strategy, strategy_money, streaming, streamRegression, subtitles, strategyMore — other sessions keep adding suites, so the total only goes up)
echo "EXIT=${PIPESTATUS[0]}"       # real exit code, not the filtered one
```

**Week 6 addition — the live route sweep (run this after ANY route change):**

```bash
bash scripts/w6_debug.sh           # boots a throwaway DB, 68 checks, exits non-zero on any 5xx
node scripts/js_catch_audit.js     # F-08 gate: expect 0 EMPTY, review any SILENT hits

# Design agent (each run spends ONE image generation):
bash scripts/w6_design_agent_probe.sh      # focused: prints stages, brief, render, review
W6_LIVE_AGENT=1 bash scripts/w6_debug.sh   # the same check inside the full sweep
```

The sweep never touches `server/frit.db` (`DB_PATH` points at /tmp) and cleans up its own file.
It also prints the Phase-6 performance line (boot / scan-20 / portfolio / feed-status / health).

Windows host note: `node --test test/` (directory form) errors here — always use the glob form
already in the `npm test` script. Live-provider behavior (billing, webhooks) can't be verified on
this host — staging required.

### 5b. VERIFICATION RECIPE (for whoever changes Android code) — NEW in Week 5

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:compileDebugKotlin    # MUST pass — the scanner is not a type checker (F-42)
.\gradlew.bat :app:testDebugUnitTest --rerun   # --rerun: plain runs report UP-TO-DATE and prove nothing
```

- Week 6 result: compile **BUILD SUCCESSFUL**, tests **32/32** (CallGuard 14, PrivacyGuard 9,
  SseParser 9). Read the counts from `app/build/test-results/testDebugUnitTest/*.xml`, not from
  the Gradle summary line.
- First run needs network (Gradle 9.3.1 download + the `androidx.security:security-crypto`
  dependency that is not in the local cache). Later runs are offline-OK.
- `scripts/kotlin_paren_check.js` is a QUICK gate only — it catches unbalanced braces, not
  unresolved references. Always follow it with the Gradle compile.


---

## 6. DEFERRED — GOOGLE PLAY (noted per user request — DO LATER)

- Play Store pre-launch / policy items in `PLAYSTORE_RELEASE.md` (honest doc already lists what's
  not production-safe): data-safety form fields, privacy policy URL, permissions declarations
  (overlay, accessibility usage in AutomationEngine), target API level compliance, signing/key
  management, store listing assets.
- Coordinate with F-15 (crash ingest) and F-10/F-11 (webhook/CORS) since Play review also probes
  network behavior.
- **Reminder: any Play-side token work must respect Constraint #1 — never move the token to XML.**
