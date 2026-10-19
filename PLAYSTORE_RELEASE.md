# FRIT — Play Store Release (Production track)

> Status today: code is **internal/closed-testing ready**, NOT production-safe.
> Do NOT submit to Production review until the 5 policy declarations below are approved.
> Play rejects automation + SMS/Phone + Accessibility apps on first review without video demo + privacy policy.

## 0. What was fixed in code today (safe, non-breaking)

- `FritConfig.AUTH_TOKEN` no longer hardcoded — uses `BuildConfig.FRIT_AUTH_TOKEN` baked from `server/.env` at build time.
- `TokenStore.get()` now: prefs value → `BuildConfig` fallback. Communication still works out-of-box, rotate without rebuild by `TokenStore.set()`.
- Added `res/xml/backup_rules.xml` + `data_extraction_rules.xml`, wired in `AndroidManifest` — token prefs + `models/` excluded from Google backup.
- `file_paths.xml`: removed broad `<external-path>` (was full external storage), kept cache/files only. FileProvider shares in `ArtifactViews.kt:213`, `MainActivity.kt:538` unaffected.
- Accessibility description expanded for Play disclosure transparency.
- `app/build.gradle.kts` release now signs with `../keystore.properties` upload key when present.
- `keystore.properties.example` added as template.

**Still hardcoded nowhere:** grep the *value* of `AUTH_TOKEN` in `server/.env` must return 0 hits in `app/src/main/java`. Run it without pasting the token into a tracked file:

```powershell
# reads the token from .env at runtime, so the secret never lands in a commit
$tok = ((Get-Content server\.env | Select-String '^AUTH_TOKEN=') -split '=',2)[1].Trim()
Select-String -Path android\app\src\main\java -Pattern ([regex]::Escape($tok)) -Recurse
```

> ⚠️ **The token value used to be pasted literally into this document** (it appeared in
> the "grep <token>" instruction above). That leaked the live credential into every
> copy of the repo. It has been removed here and the check now reads the value from
> `server/.env` at runtime. **Rotate the production `AUTH_TOKEN` on the server before
> publishing this repository anywhere** — a credential that has been in a tracked
> file must be considered compromised, whether or not the repo was ever public.


## 1. One-time signing setup (5 min)

```powershell
cd android
# 1. Generate upload key (keep passwords + .keystore file safe, never commit)
keytool -genkeypair -v -keystore frit-upload.keystore -alias upload -keyalg RSA -keysize 2048 -validity 10000
# 2. Wire it
Copy-Item keystore.properties.example keystore.properties
# edit keystore.properties with real passwords
# 3. Ensure server/.env has production values (NOT localhost):
#    FRIT_BASE_URL=https://frit-android.onrender.com
#    AUTH_TOKEN=<fresh-rotated-token>
```

`keystore.properties` + `*.keystore` must stay gitignored. If you lose the upload key, Play Console → App signing lets you request a reset (takes days).

## 2. Build the AAB (NOT APK) for Play

```powershell
cd android
.\gradlew :app:bundleRelease
# output: app\build\outputs\bundle\release\app-release.aab
```

- versionCode `1`, versionName `1.0.0` in `app/build.gradle.kts:44-45` — correct for FIRST upload. Bump versionCode +1 on every Play upload after.
- Release ABI = `arm64-v8a` only → 32-bit devices excluded. Acceptable for 3GB-RAM + llama.cpp, but note in listing (“Requires 64-bit Android 8.0+”).
- Assets today: ~67 MB (Vosk). `assets/models/` empty → 1.28 GB Qwen GGUF downloads at runtime from HuggingFace. Good (avoids 150 MB Play limit), but you MUST disclose “downloads 1.3 GB on first use, Wi-Fi recommended” in listing + Data Safety + in-app prompt.
- Before upload: `.\gradlew :app:assembleRelease` install on a real arm64 Android 15 device, test: login/auth, voice overlay, screen capture consent, accessibility on/off, offline model download.

## 3. Play Console — Production blockers (expect rejection without these)

This app triggers **5 separate restricted-permission reviews**:

| # | Trigger in this codebase | Play requirement |
|---|--------------------------|------------------|
| 1 | `QUERY_ALL_PACKAGES` + `getInstalledApplications()` in `DeviceStateCollector.kt:93`, `MT5Agent.kt:48`, `AutomationEngine.kt:526` | Remove, or submit “Core functionality” declaration + video showing why broad inventory is essential. Prefer per-package `<queries>` instead. |
| 2 | `SEND_SMS`, `CALL_PHONE`, `READ_CONTACTS`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS` (`AndroidManifest`, `MainActivity.kt:144-157`) | SMS/Call-Log group = default SMS/Phone/Dialer/Assistant only. Must file Permissions Declaration + video demo. Consider cutting `SEND_SMS` for v1 to halve review risk. |
| 3 | `FritAccessibilityService` (`typeAllMask` + gestures + window content) | Accessibility API policy: disability benefit only, prominent in-app disclosure + consent BEFORE enabling, video demo. Automation/MT5 monitoring alone = rejection. |
| 4 | Notification Listener (`FritNotificationListener`), `SYSTEM_ALERT_WINDOW`, MediaProjection (`ScreenCaptureService`), `RECORD_AUDIO` background, `SCHEDULE_EXACT_ALARM`, FGS `specialUse/microphone/mediaProjection` | Each needs Data Safety entry + runtime consent + foreground notification. `specialUse` subtypes already declared — keep them. |
| 5 | `VoiceInteractionService` (`BIND_VOICE_INTERACTION`, `FritAssistantService`) | Cannot be default Assistant via Play download; remove or gate behind “system build only” flag or Play will fail functional test. |

Also required:
- Privacy policy URL (hosted, e.g. `https://<your-site>/frit-privacy`) covering: contacts, SMS, calls, mic, camera, screen capture, accessibility data, HuggingFace model download, backend `frit-android.onrender.com` data retention.
- Data Safety form: location? No. Personal (contacts, phone), audio, photos/video (screen), app activity. Declare data collected, shared with backend + HuggingFace, encrypted in transit (TLS), retention + delete path.
- Content rating questionnaire, target audience, store listing: 512×512 icon, 1024×500 feature graphic, 2+ phone screenshots, short (80 char) + full description with model-download + permission disclosure.
- 16 KB page-size (Android 15+): own `llama_jni` aligned (`-z,max-page-size=16384`), OpenCV `5.0.0.1` aligned, but **Vosk `0.3.75` prebuilt likely NOT aligned** — test on Android 15 arm64, run `check_elf_alignment.sh` on APK `.so`s before upload.
- Remove `app/src/main/jni/llama.cpp/examples/llama.android/` from release packaging/repo (bloat + duplicate manifests confuse review). `settings.gradle.kts` only includes `:app` so not packaged today, but delete or gitignore before upload.

## 4. Recommended v1 scope cut to ship TODAY via Internal track

1. Upload AAB to **Internal testing** (not Production) — no full policy review, testers install in minutes.
2. In parallel prepare Production: privacy policy, Data Safety, permission videos, Vosk 16 KB test, token rotation.
3. If Production review fails (likely first time), you already have Internal build + feedback loop.

## 5. Pre-upload verification

```powershell
# secrets gone from source? (token read from .env at runtime, never pasted here)
$tok = ((Get-Content server\.env | Select-String '^AUTH_TOKEN=') -split '=',2)[1].Trim()
Select-String -Path android\app\src\main\java -Pattern ([regex]::Escape($tok)) -Recurse
# backup rules wired?
Select-String -Path android\app\src\main\AndroidManifest.xml -Pattern "backup_rules|dataExtractionRules"
# AAB size sanity (<150 MB base expected: ~67 MB assets + native libs)
Get-ChildItem android\app\build\outputs\bundle\release\*.aab | Select-Object Name, @{N="MB";E={[math]::Round($_.Length/1MB,1)}}
```
