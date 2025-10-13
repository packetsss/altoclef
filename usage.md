# Usage Guide

Alto Clef has a variety of commands, settings and modes. This will give users an overview on how to use the bot.

Keep in mind this project is still in rapid development. A lot of features are placeholders and a work in progress.

## Commands

Commands are prefixed with `@`. Here's a list along with their functions:

| command                | description                                                                                                            | examples      |
|------------------------|------------------------------------------------------------------------------------------------------------------------|---------------|
| `help`                 | Lists all commands                                                                                                     |               |
| `gamer`                | Beats the game epic style                                                                                              | `@gamer`      |
| `reload_settings`      | Reloads the local settings file. Run this every time you want your settings to be updated.                             |               |
| `gamma {brightness=1}` | Sets the game's gamma. Useful for testing. 0 is "Moody" and 1 is "Bright", and you can go beyond to enable fullbright. | `@gamma 1000` |
| `scan`                 | Logs the nearest given block currently found be `BlockScanner`.                                                        | `@scan dirt`  |
| `status`               | Prints the status of the currently executing command. Mostly useful when running through `/msg`.                       |               |
| `stop`                 | Forcefully stops the currently running command. The shortcut `CTRL+K` also achieves this.                              |               |
| `test {testname}`      | Runs a "test" command. These vary, and will be described below.                                                        |               |


### Notable test commands

Test commands are temporary/only exist as an experiment, but some of these might be interesting.
For example, `@test terminate` runs the terminator.
Here's a list of some highlights.

**note** *this list doesn't include all the test tasks, but it is not my priority to update it...*

| test name   | what it does                                                                                                                                                                                                                                                                                         |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `terminate` | Runs the terminator task. When without diamond gear, the bot flees players and obtains diamond gear + food. When diamond gear is equipped, the bot hunts any nearby players down and searches for any chunks that were last seen to have players in them.                                            |
| `deadmeme`  | Expects a file to exist in your `.minecraft` directory called `test.txt`. Reads from this file, then displays the text contents of the file by placing signs in a line. Dubbed the "Bee Movie Task" for stupid reasons. Will automatically collect signs and building materials if the bot runs out. |
| `173`       | Attacks any player that doesn't have direct line of sight to the bot and stands still otherwise. Like a weeping angel.                                                                                                                                                                               | 
| `replace`   | Replace grass block within around 100 blocks with crafting tables. Frequently fails when replacing grass blocks next to water.                                                                                                                                                                       |
| `piglin`    | Collects 12 ender pearls via piglin bartering.                                                                                                                                                                                                                                                       |
| `stacked`   | Collects diamond armor, a sword and a crafting table.                                                                                                                                                                                                                                                |
| `netherite` | Same as `stacked` but for netherite gear.                                                                                                                                                                                                                                                            |
| `sign`      | Place a sign nearby that says "Hello there!"                                                                                                                                                                                                                                                         |
| `bed`       | Right clicks a nearby bed to set the bot's spawnpoint, placing one if it does not exist.                                                                                                                                                                                                             |

## Bot Settings/Configuration

After running the game with the mod once, a folder called `altoclef` should appear in your `.minecraft` directory. This
contains `altoclef` related settings and configurations.

Regarding the `altoclef_settings.json` file
Check [Settings.java](https://github.com/MiranCZ/altoclef/blob/main/src/main/java/adris/altoclef/Settings.java)

Other configs can be found in the `configs` subdirectory. Some of the `beat_minecraft.json` settings may not work, but I plan to reimplement proper settings in the future.

After modifying your local settings, run `@reload_settings` to apply your changes in-game.

### Random respawn

If you want the bot to start from a fresh location after every death, enable the random respawn settings in `altoclef_settings.json`:

- `"randomRespawnEnabled": true` – turn on random respawns (defaults to `false`).
- `"randomRespawnMinRadius"` / `"randomRespawnMaxRadius"` – control the radial distance in blocks (measured from world origin) that the new spawn point will be rolled from. Values in the low thousands work well for a fresh overworld start.

When enabled, Alto Clef will pick a random overworld coordinate inside that range each time you die, set the spawn point there, and relocate immediately after respawning. The feature requires an integrated (singleplayer/LAN-hosted) world so the client can move the player server-side.

### CamBridge telemetry bridge

AltoClef can expose its internal state to external camera tooling through a lightweight event bridge. To enable it, configure the following keys in `altoclef_settings.json` and reload settings with `@reload_settings`:

- `"camBridgeEnabled": true` – activates the bridge runtime.
- `"camBridgeTransport": "udp"` – emit JSON lines over loopback UDP (default). Set to `"file"` to write events to disk.
- `"camBridgeHost"` / `"camBridgePort"` – loopback endpoint used when the transport is `udp` (defaults to `127.0.0.1:36667`).
- `"camBridgeMirrorUdpPorts"` – optional list of extra loopback UDP ports that receive mirrored events (disabled by default; add ports if you need additional consumers).
- `"camBridgeFilePath"` – output path when the transport is `file` (defaults to `cambridge-events.jsonl` in the game directory).

Events are emitted once per client tick with built-in coalescing, hazard debouncing, and a 64-entry in-memory ring buffer so a subscribing camera mod can build overlays without spamming chat or requiring a server plugin. In the default "status-only" mode the stream is limited to queue information.

`STATUS_NOW` heartbeats include a `task_queue` block describing what the bot has on deck:

- `current` – the human-readable summary of the task AltoClef is executing right now (or "Mob Defense – Hunt nearby hostiles" when the combat failsafe takes over).
- `future` – up to the next three scheduled tasks pulled from the user chain.
- `mob_defense` – `true` when the mob defense chain is actively hunting or shielding, signalling that downstream listeners should treat the bot as fighting mobs instead of running the normal queue.
- `mob_defense_active` (top-level) mirrors the flag above for ease of filtering.

### Death telemetry log

For post-mortem debugging you can enable the detailed death logger (enabled by default). Set `"deathLogEnabled": true` in `altoclef_settings.json`, then reload settings. Every time the player dies, AltoClef captures a rich snapshot of the surrounding context—player stats, inventory, active tasks, nearby threats—and appends it as a JSON line under `altoclef/logs/death/`. These logs make it much easier to diagnose why the run failed without having to scroll back through chat output. Disable the feature with `"deathLogEnabled": false` if you prefer not to emit files.