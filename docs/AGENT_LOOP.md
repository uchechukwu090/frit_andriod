# Unified Agent Loop & Position Monitor Specification

## 1. Single Agent Loop Overview

FRIT uses a single, task-agnostic `/agent/*` loop for driving all client and server goals. Legacy endpoints (`/chat`, `/plan`, `/automate`, `/automation-results`) have been consolidated into this single state engine.

### Workflow Sequence

1. **Session Initiation** (`POST /agent/start`):
   - Accepts `{ goal, device_state, memory }`.
   - Generates plan & subtask ledger.
   - Executes any server-bound tools immediately within `runAgentStep`.
   - Returns assistant text and any pending edge actions (`pending_actions`).

2. **Edge Action Execution**:
   - The Android edge (`IntegrationCoordinator.chat()`) executes UI actions (`tap_button`, `type_text`, `scroll`, `send_whatsapp`, etc.).
   - Gathers updated screen context (`read_screen`).

3. **Session Resume** (`POST /agent/resume`):
   - Client sends `{ sessionId, tool_results, device_state }`.
   - Server runs verification verdict (`MODELS.fast`) comparing pre- and post-action screen state.
   - Ledger updates subtask status (`DONE` / `FAILED`).
   - Loop continues until goal completion (`done: true`).

## 2. API Endpoints Reference

### Brain & Loop Endpoints
- `POST /agent/start`: Initialize agent task session.
- `POST /agent/resume`: Resume session with edge tool results.
- `GET /agent/status?sessionId=...`: Query session state & conviction metrics.
- `POST /agent/heartbeat`: Background heartbeat check.

### Search & Sandbox Endpoints
- `POST /tools/search`: Web search proxy (`{ query }`).
- `POST /sandbox/run`: Python/JS code sandbox execution (`{ language, code, stdin }`).

### Additive Position Monitor Endpoints
- `POST /positions/register`: Register new open trade position (`{ ticket, symbol, type, openPrice, sl, tp, volume }`).
- `GET /positions`: List active monitored positions.
- `GET /positions/status`: Summary metrics of active positions & equity risk.
- `POST /positions/resolve`: Resolve closed position (`{ ticket, outcome, exit_price, profit }`).

## 3. Position Monitor State Machine

The `PositionMonitor` (`server/src/strategy/position_monitor.js`) operates independently of the agent loop:
- **State Machine**: `PLACED` ➔ `MONITORING` ➔ `TP_HIT` / `SL_HIT` / `CLOSED`.
- **Auto-Polling**: Server polls price data every 60s to check TP/SL conditions.
- **On-Device Sync**: The Android client `MT5Agent` position watcher detects ticket changes in MetaTrader 5 and posts resolutions to `/positions/resolve`.
- **Memory Persistence**: Resolved trades are automatically recorded to SQLite `trade_memory` and the paper logger.
