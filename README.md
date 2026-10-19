# FRIT All-In-One Integrated Build

FRIT (Full-Range Intelligence Terminal) — General-purpose Android AI agent with server-side reasoning, fast step verification, position monitoring, and local execution fallback.

## Architecture & Unified Agent Loop

```
User / Scheduled Goal ──► SubAgentOrchestrator / IntegrationCoordinator (Android Edge)
                                   │
                                   ▼
                      POST /agent/start /resume (Server Brain)
                                   │
                ┌──────────────────┴──────────────────┐
                │                                     │
       Server Tools (Immediate)              Android Edge Actions
   (search_web, run_code, market,         (read_screen, tap_button, type,
    wait_and_verify, assert_text)          scroll, open_app, WhatsApp, etc.)
                │                                     │
                └──────────────────┬──────────────────┘
                                   │
                        Fast Step Verification & Ledger
                                   │
                                   ▼
                  Result Feedback / Subtask Completion
```

## Core Principles

1. **Task-Agnostic Agent Loop**: A single, consolidated `/agent/*` loop handles any goal — from WhatsApp/SMS messaging and app control to market analysis and MT5 trades.
2. **Server-Side Tool Execution**: Computational, search, market, and code execution tools execute directly on the server inside `runAgentStep`. Only Android UI interactions are dispatched to the phone.
3. **Step Verification & Ledger**: Every step outcome is verified against on-screen context, dynamically updating task sub-ledger state to prevent stuck loops.
4. **Additive Position Monitoring**: The `PositionMonitor` runs as a dedicated server-side state machine for MT5 trades, auto-resolving Take-Profit (TP) and Stop-Loss (SL) levels without altering the general-purpose loop.

## Key Features

- **General-Purpose Automation**: Call, SMS, WhatsApp, play music, open apps, set alarms, navigate.
- **Web & Code Execution**: Server-side `search_web` and sandboxed `run_code` execution.
- **Market & Position State Engine**: ACP conviction scoring, GSRI risk overlay, and MT5 deep-link position tracking.
- **Gemini-Style Voice Overlay**: Wake-word ("Hey FRIT") activated overlay with live thought step streaming.
- **Persistent Automation Scheduler**: Schedule any command string on any interval with background survival.

## Quick Start

### Backend Server (Node.js)
```bash
# Set environment variables in server/.env
cd server
npm install
npm run dev
```

### Android Application
1. Open `android/` directory in Android Studio or build via Gradle.
2. Ensure Accessibility Service permissions are enabled on the target device.
