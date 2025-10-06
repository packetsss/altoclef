# CamBridge Event Guide for Camera Operators

This guide is written for streamers or production crew who only run the camera/overlay tooling. It explains how to subscribe to AltoClef's **CamBridge** event feed, interpret the messages, and surface them in your broadcast UI.

If you are the person piloting AltoClef itself, make sure CamBridge is enabled in `altoclef_settings.json` (see `usage.md`). Hand the relevant connection details (port or file path) to the camera operator before the run.

---

## 1. Quick checklist

| Step | What you need | Expected result |
|------|----------------|-----------------|
| 1 | Confirm CamBridge transport (UDP or file) with the AltoClef operator | You know whether to listen on a localhost UDP port or watch a file |
| 2 | Open your capture tool (camera mod, script, telemetry collector) | You can receive newline-delimited JSON events |
| 3 | Verify connectivity (see §2.3) | You see `HEARTBEAT` events arriving roughly every 10 seconds |
| 4 | Map event types to overlays or HUD widgets | Your camera UI reacts instantly with minimal spam |
| 5 | Keep an eye on the heartbeat | If it stops for >15 seconds, alert the run operator |

---

## 2. Getting the event feed

CamBridge emits compact JSON objects once per client tick after AltoClef starts up. All events line up with the real-time state that AltoClef already knows internally.

### 2.1 UDP transport (default)

1. **Port information.** By default CamBridge fires on `127.0.0.1:36666`. The AltoClef operator can change this in `altoclef_settings.json` (`camBridgeHost`, `camBridgePort`). You need the final values.
2. **Listening locally.** Run your camera mod or diagnostic tool on the same machine as AltoClef. The stream is newline-delimited UTF-8 JSON. Any UDP client that binds the port will see the traffic.
3. **Sanity check from PowerShell (Windows):**
   ```powershell
   $udp = New-Object System.Net.Sockets.UdpClient(36666)
   try {
       $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any,0)
       while ($true) {
           $bytes = $udp.Receive([ref]$remote)
           [Text.Encoding]::UTF8.GetString($bytes)
       }
   } finally {
       $udp.Dispose()
   }
   ```
   Replace `36666` if a different port was agreed upon. You should see `HEARTBEAT` JSON every ~10 seconds even when nothing else is happening.
4. **Firewall notes.** Because CamBridge binds to loopback only, nothing ever leaves the local PC. No extra firewall rules are needed.

### 2.2 File transport

If networking is prohibited, AltoClef can drop the latest events into a single JSON-lines file (`camBridgeTransport = "file"`). The event stream is rewritten once per client tick.

1. Obtain the absolute path from the AltoClef operator (default: `<minecraft folder>/cambridge-events.jsonl`).
2. Tail the file with your overlay tool or a watcher script; each line is a complete JSON object.
3. The file always contains the most recent batch flushed in the current tick. If you need historical events, keep your own buffer downstream.

### 2.3 How to know the feed is healthy

- You should receive a `HEARTBEAT` event every ~10 seconds.
- Phase and task transitions (`PHASE_ENTER`, `TASK_START`) are rate-limited to prevent spam. If you expect a message and do not see it, confirm with the AltoClef operator that the phase actually changed.
- CamBridge internally keeps a 64-event ring buffer; if your consumer crashes briefly, the next tick still shows only fresh events. There is no backfill beyond that.

---

## 3. Event structure

Every event is a single JSON object with the following top-level fields:

| Field | Type | Description |
|-------|------|-------------|
| `id` | number | Strictly increasing identifier unique to this AltoClef session |
| `ts` | number | Event timestamp in Unix milliseconds (local system clock) |
| `type` | string | Event taxonomy (see §4) |
| `priority` | string | Default importance hint (`P1`, `P2`, `P3`) |
| `phase` | string or null | Current run phase when the event was generated |
| `payload` | object | Event-specific fields, only the data that changed |
| `suggested_mode` | string, optional | Camera hint: `QUICK` for lightweight overlays, `FULL` for in-depth takes |
| `suggested_duration_sec` | number, optional | Suggested length for the overlay tied to the event |

Example `MILESTONE` packet:

```json
{
  "id": 1024,
  "ts": 1727998265123,
  "type": "MILESTONE",
  "priority": "P2",
  "phase": "NETHER",
  "payload": {
    "pearls": 12,
    "rods": 6,
    "crossed": ["pearls_target", "rods_min"]
  }
}
```

Notes:
- `payload` never repeats identical data two ticks in a row. For example, pearls +1 three times within two seconds becomes a single milestone with the final count.
- If an event asks for a full camera segment (`suggested_mode = "FULL"`), CamBridge enforces a 20-second cooldown before suggesting another full segment.
- There is also a 6-second cooldown between any two "big" events (`PHASE_*`, `TASK_*`, `HAZARD`, `REROUTE`, `STALL`, `DIM_CHANGE`). CamBridge queues deferred messages and sends them as soon as the cooldown expires.

---

## 4. Event taxonomy

| Type | When it fires | Key payload fields | Recommended camera reaction |
|------|---------------|--------------------|------------------------------|
| `PHASE_ENTER`, `PHASE_EXIT` | Run switches between OVERWORLD_PREP → NETHER → FORTRESS → PEARLS → STRONGHOLD → END | `payload.shopping` (targets, gold plan) on enter | Fade-in overlay announcing the new phase; consider a quick stat card |
| `TASK_START`, `TASK_END` | AltoClef swaps primary user task | `task_key`, `task_class`, `task_debug`; `success`, `reason` on end | Display current automation focus; recap if a task fails |
| `MILESTONE` | Inventory counters change after coalescing | Counts for `pearls`, `rods`, `eyes`, `beds`, `arrows`, `food`, `iron`, `gold`, `obsidian`, `buckets`, `flint_steel`, `tools` | Update resource HUD badges, highlight `crossed` thresholds |
| `HAZARD` | Danger begins or ends (COMBAT, FIRE, LAVA, FALLING, DROWNING, LOW_HP) | `hazard`, `state`, `health` (for LOW_HP) | Flash warning panel; deactivate when `state` becomes `stop` |
| `REROUTE` | Immediate reroute reason detected (currently lava obstructions) | `reason` | Small lower-third mentioning why the path changed |
| `STALL` | Player position has not meaningfully changed for 18 s | `duration_sec`, `near` hint | Show "holding pattern" banner; consider calling out location |
| `DIM_CHANGE` | Dimension shifts OVERWORLD/NETHER/END | `dimension` | Quick status pop, maybe color-coded |
| `HEARTBEAT` | ~10-second cadence update | `phase`, `brief` counters | Drive persistent mini-HUD refresh |
| `DIAGNOSTIC` | Reserved for future verbose messages | Implementation-specific | Treat as low-priority log entry |

Hazard debouncing: CamBridge waits 0.8–1.2 seconds before emitting start/stop edges to avoid flicker from single-tick contacts with lava or fire.

---

## 5. Building overlays around the data

- **Phase board:** Use `PHASE_ENTER`, `PHASE_EXIT`, and `HEARTBEAT.phase` to keep a stepper UI accurate.
- **Resource bar:** Update counts from `MILESTONE` payloads. For tools, `payload.tools` contains `pick_tier` and `pick_dur_pct` whenever it changes.
- **Hazard ticker:** Maintain a set of active hazards. Display stacked alerts if multiple hazards are `start`ed and remove the row on `stop`.
- **Task focus panel:** The `task_debug` string mirrors AltoClef's `toString()` for the active task, useful for detailed commentary.
- **Stall/diagnostic logging:** Consider logging `STALL`, `REROUTE`, and `DIAGNOSTIC` events to a side console so producers can nudge the runner when something unexpected happens.

---

## 6. Troubleshooting tips

| Symptom | What to check | Fix |
|---------|---------------|-----|
| No packets at all | Is CamBridge enabled? Did you bind the correct UDP port or file path? | Ask the AltoClef operator to run `@reload_settings` and confirm `camBridgeEnabled = true`. Double-check port/file. |
| Heartbeat stops | AltoClef might be paused or the game lost focus | Notify the operator; if AltoClef is still running the heartbeat should resume immediately after the next client tick |
| Hazard spam | CamBridge already debounces; if you're still seeing flashing overlays, add your own minimum display duration on the camera side |
| JSON parse errors | Ensure your listener treats each UDP datagram or file line as a separate JSON object. There is no envelope wrapper. | Adjust your parser; newline-delimited JSON is expected |
| Need historical data | CamBridge does not replay old events | Persist events downstream if you need a history or time-shift buffer |

---

## 7. Glossary of payload fields

| Field | Meaning |
|-------|---------|
| `shopping.rods_target`, `shopping.pearls_target`, `shopping.beds_target` | Phase goals derived from AltoClef's current speedrun config |
| `shopping.gold_plan` | Either `barter` or `hunt`, telling you how pearls will be obtained |
| `tools.pick_tier` | One of `WOOD`, `STONE`, `IRON`, `GOLD`, `DIAMOND`, `NETHERITE` |
| `tools.pick_dur_pct` | Remaining durability percentage of the best pickaxe AltoClef owns |
| `crossed` array | Threshold flags: `pearls_target`, `rods_min`, `eyes_min`, `beds_target` |
| `hazard` | `COMBAT`, `FIRE`, `LAVA`, `FALLING`, `DROWNING`, `LOW_HP` |
| `reason` (REROUTE) | Currently `lava_blocked_path`; future releases may add `no_path`, `backtracking` |
| `near` (STALL) | High-level point of interest (`fortress`, `bastion`, `stronghold`, or `null`) |

---

## 8. Support

If your camera tooling needs an additional event or payload field, file an issue or reach out to the AltoClef maintainers with:
- The type of on-air graphic you want to trigger
- Which in-game moment should produce the data
- Whether a faster cadence or wider history buffer is required

CamBridge is deliberately small and stable so overlays stay predictable between releases.
