# Architecture & Technology Stack

## Core Loop Architecture
- **Unified Brain Endpoint**: `/agent/start`, `/agent/resume`, `/agent/status`, `/agent/heartbeat`.
- **Server Tool Execution**: `run_code` (Python/JS sandbox), `search_web` (DDG+Bing+Brave+Brave-API gateway with page fetch), `generate_image`, `generate_video`, `create_content`, `ground_scene`, `get_weather`, `get_market_data`, `analyze_market`, `wait_and_verify`, `assert_text_visible`. Executed directly inside `runAgentStep` on the backend.
- **Edge Action Delegation**: `read_screen`, `tap_button`, `tap_coordinates`, `type_text`, `scroll`, `press_home`, `press_back`, `open_app`, `send_whatsapp`, `send_sms`, `make_call`. Dispatched as `pending_actions` to Android client.
- **Verification Engine**: `MODELS.fast` evaluates pre/post device states after each action turn to determine step success.
- **Position Monitoring State Machine**: `PositionMonitor` (`server/src/strategy/position_monitor.js`) tracks active trade tickets (`PLACED`, `MONITORING`, `TP_HIT`, `SL_HIT`, `CLOSED`), auto-polls market prices every 60s, logs to SQLite `trade_memory`, and syncs with on-device `MT5Agent`.

## AI Infrastructure
- **Primary Agent LLM**: OpenRouter `z-ai/glm-5.3-flash` (`MODELS.agent/tools/coding`).
- **Fast Verification & Summary LLM**: OpenRouter `openai/gpt-oss-20b` (`MODELS.fast`).
- **Draft Subagents**: OpenRouter `deepseek/deepseek-v4-flash` (`MODELS.draft`); free `:free` quota sits behind paid in all fallback chains.
- **Vision Inference**: OpenRouter `z-ai/glm-5.3-flash` (native multimodal), `gemini-3.6-flash` legacy fallback only, then `mistral-large-latest`.
- **Speech-to-Text**: Whisper Large V3 via OpenRouter (`OR_STT_MODEL`).
- **Image**: `bytedance-seed/seedream-5-0-flash` via OpenRouter `/images`; **Video**: `heygen/heygen-video-1` via OpenRouter `/videos`.
- **Direct image providers** (optional, selected by model slug prefix in `creative/imageProviders.js`):
  `ideogram/ideogram-v3` (typography specialist, `IDEOGRAM_API_KEY`) and
  `recraft/recraft-v3` (real fonts via the native `texts[]` field, `RECRAFT_API_KEY`).
- **On-Device Fallback**: Gemma 3n E2B for basic offline intent classification (Android only; server never calls it).

## Creative Agent (Design Studio + AI Director)
Both studios run the same three stages — **research → brief → render + verify** — with a different
medium. The rules that make the output professional rather than "AI poster" live in
`server/src/creative/designDNA.js`.

- **Reference transport** (`creative/refs.js`): browser-like fetch → origin-side public proxy
  fallback → ≤1024px downscale → content-addressed disk cache. `GET /creative-suite/refs/:id`
  serves the bytes so the app can show what was studied.
- **Structure vs content** (`designDNA.js`): a reference is split into STRUCTURE (grid,
  alignment, whitespace, palette temperature, type contrast, motif density, texture — borrowable)
  and CONTENT (subject, motifs — never borrowable). Only the structure crosses into the brief.
- **Diversity + seed**: every run carries a seed that drives the query's style token, the
  reference ranking jitter, the reference order and the art direction. References are chosen for
  maximum *structural* spread, and the finished brief is then pushed away from them
  (`enforceDivergence`), so the same request never returns the same design twice.
- **Type layer** (`creative/typeLayer.js`): the renderer is asked for a background plate and the
  real type is set from the brief (font, size, weight, tracking, case), fitted to the column and
  contrast-checked. A typography specialist (Ideogram/Recraft) sets the type itself instead.
- **Director** (`creative/directorAgent.js`): look research → shot plan (with a shot-variety
  guard) → per-shot render → ffmpeg frame extraction (`POST /media/frames` in the sandbox) →
  vision review of the frame → re-render with the reviewer's complaints.

## Android Edge
- **Language / Framework**: Kotlin, Jetpack Compose, Material 3.
- **Network**: OkHttp3 gateway (`NetworkClient`).
- **Services**: Foreground Service, Accessibility Service, MediaProjection screen capture.
- **Security**: Security gate (`DEV_TOOLS_ENABLED = false`) restricting arbitrary shell command and file writing tools on release builds.
