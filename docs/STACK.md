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
- **Draft Subagents**: OpenRouter `qwen/qwen3-30b-a3b-2507`; free `:free` quota sits behind paid in all fallback chains.
- **Vision Inference**: OpenRouter `z-ai/glm-5.3-flash` (native multimodal), `gemini-3.6-flash` legacy fallback only, then `mistral-large-latest`.
- **Speech-to-Text**: Whisper Large V3 via OpenRouter (`OR_STT_MODEL`).
- **Image**: `google/gemini-2.5-flash-image`; **Video**: `google/veo-3.1-lite` via OpenRouter.
- **On-Device Fallback**: Gemma 3n E2B for basic offline intent classification (Android only; server never calls it).

## Android Edge
- **Language / Framework**: Kotlin, Jetpack Compose, Material 3.
- **Network**: OkHttp3 gateway (`NetworkClient`).
- **Services**: Foreground Service, Accessibility Service, MediaProjection screen capture.
- **Security**: Security gate (`DEV_TOOLS_ENABLED = false`) restricting arbitrary shell command and file writing tools on release builds.
