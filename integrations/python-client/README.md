# AltoClef CamBridge Python Client

This directory contains a reference UDP listener that consumes CamBridge telemetry emitted by AltoClef and prints it to standard output. It listens on **`127.0.0.1:36667`**, which is configured as a mirrored CamBridge port so the primary camera tooling can stay on `36666`.

## Prerequisites

- Python 3.9 or newer.
- AltoClef 1.21.1 (or later) with CamBridge telemetry enabled.
- The AltoClef and Python processes must run on the same machine (CamBridge only binds loopback).

## Configure AltoClef

1. Locate your `altoclef_settings.json` (created after running AltoClef once). The file lives next to `latest.log` inside your Minecraft instance directory.
2. Open the file in a text editor and ensure the following keys are set:

   ```json
   "camBridgeEnabled": true,
   "camBridgeTransport": "udp",
   "camBridgeHost": "127.0.0.1",
   "camBridgePort": 36666,
   "camBridgeMirrorUdpPorts": [36667]
   ```

3. Save the file and run `@reload_settings` in-game (or restart AltoClef) to apply the changes.

## Run the listener

From this directory, start the sample listener in PowerShell:

```powershell
python .\cambridge_listener.py
```

You should see task-centric status updates such as:

```
12:34:56 ▶ Overworld – Build Portal – [RUNNING @ OVERWORLD_PREP]
12:35:10 ✅ Finished ConstructNetherPortalObsidianTask
```

### Optional flags

- `--raw` prints the raw JSON line without formatting (restores the legacy behaviour).
- `--host` / `--port` let you match a custom CamBridge endpoint if the AltoClef operator changes it.

### Event fields of interest

`STATUS_NOW` events now ship a `task_queue` payload so downstream tooling can distinguish combat fallbacks from the normal goal stack:

- `task_queue.current` – current human-readable task summary. When the mob defense chain pre-empts the user task chain it reads `Mob Defense – Hunt nearby hostiles`.
- `task_queue.future` – up to the next three queued tasks, useful for overlaying “coming up next” panels.
- `task_queue.mob_defense` (and the mirrored root-level `mob_defense_active`) – boolean flag signalling that AltoClef is actively fighting mobs. Your listener can swap to combat UI or narration when this turns `true`.

## Integrating into your bot

1. Copy `cambridge_listener.py` into your project (or import its helpers).
2. Replace the `print_event` function with your bot's enqueue / dispatch logic.
3. Keep the UDP socket open for the lifetime of your process. CamBridge emits events roughly once per client tick; no handshake is required.
4. Watch for `HEARTBEAT` events (every ~10 seconds). If they stop, reconnect or alert the AltoClef operator.

## Troubleshooting

| Symptom | What to check | Fix |
|---------|---------------|-----|
| `PermissionError` binding the port | Another process already claimed UDP 36667 | Pick a new free port, add it to both `camBridgeMirrorUdpPorts` and the listener (`--port`). |
| No packets received | CamBridge disabled or wrong port | Confirm the JSON keys above, then run `@reload_settings` in AltoClef. |
| Garbled output | Non-UTF8 data or partial packets | Use `--raw` to inspect the payloads. CamBridge sends newline-delimited UTF-8 JSON. |

For deeper event semantics, see `docs/cambridge-camera-guide.md`.
