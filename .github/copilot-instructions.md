# AltoClef Copilot Guide
## Architecture & Lifecycle
- `AltoClef.java` bootstraps on `TitleScreenEntryEvent` and calls `onInitializeLoad`; register chains, trackers, and settings hooks there before `TaskCatalogue.init()`.
- Core services (`TaskRunner`, `TrackerManager`, controllers, telemetry) are built during `ensureInitialized`; avoid instantiating singletons directly.
- Global events live in `adris.altoclef.eventbus`; handlers run on the client thread, so keep work cheap and guard against pre-init states.
- Settings hot-reload through `Settings.load`; mirror `AltoClef.onSettingsReload` when wiring new config so idle commands, throwaways, and avoidance lists stay synced.
- Resource metadata sits in `CataloguedResources.txt`, `ItemHelper`, and `TaskCatalogue`; update all three together when adding new goals.
## Task Chains & Behaviour
- `TaskRunner` schedules `TaskChain`s by `getPriority`; keep new chain priorities unique (e.g., `UserTaskChain` 50, food 55, defense 90).
- Use `SingleTaskChain.setTask` to swap tasks; never hold stale task references or you risk double `onStop` calls.
- Tasks extend `adris.altoclef.tasksystem.Task`; implement `isEqual`, reset transient state in `onStart`/`onStop`, and return subtasks from `onTick` instead of side effects.
- Mutate Baritone via `BotBehaviour.push()`/`pop()` wrappers only; stray Baritone edits bleed between chains.
- Invoke `UserTaskChain.signalNextTaskToBeIdleTask()` when scheduling passive follow-up so idle commands resume correctly.
## Inventory & World Interaction
- Route inventory clicks through `SlotHandler` and `StorageHelper`; direct `MinecraftClient` calls desync replay automation.
- Use catalogue helpers (`TaskCatalogue.mine`, `smelt`, `woodTasks`) to respect dimension forcing and auto-smelt rules.
- `TrackerManager` exposes `ItemStorageTracker`, `EntityTracker`, `BlockScanner`, etc.; access through `AltoClef` getters to stay in step with the tick loop.
- `ContainerSubTracker` is injected by `ItemStorageTracker`; reuse `AltoClef.getItemStorage()` for chest state instead of recreating trackers.
- `TaskPersistenceManager` replays queued `@` commands from `altoclef/logs/session`; keep new command flows resumable.
## Commands & Butler
- Chat commands route through `CommandExecutor` and `AltoClefCommands.init()`; `EventBus` swallows lines starting with `@` before vanilla chat.
- The Butler (`adris.altoclef.butler.*`) coordinates multi-user queues; emit player-visible strings through `MessageSender` to respect cooldowns.
- Idle automation honors `Settings.shouldRunIdleCommandWhenNotActive()` and `Settings.getIdleCommand()`; ensure custom tasks toggle idle state responsibly.
- Use `Debug.logMessage` for user-facing text and `Debug.logInternal` for noisy diagnostics; both land in `latest.log`, only the former echoes to chat.
- Prefer `TaskRunner.getChainDiagnostics()` and `CommandStatusOverlay` for live task state instead of ad-hoc logging.
## Telemetry & Integrations
- CamBridge (`adris.altoclef.cambridge`) emits STATUS_NOW and hazard payloads; gate heavy sends behind `Settings.isCamBridgeEnabled()` and reuse transports (`UdpCamBridgeTransport`, `FileCamBridgeTransport`, `CompositeCamBridgeTransport`).
- `DeathLogManager`, `StuckLogManager`, and friends write JSON under `altoclef/logs`; call their `recordEvent` helpers to keep session scoping intact.
- Telemetry session dirs arrive via `AltoClef.getTelemetrySessionDir()`; store run-specific files there for automatic cleanup.
- `integrations/python-client/cambridge_listener.py` documents the UDP consumer protocol; mirror its CLI flags if you add transports.
- For stuck debugging pair `stuckLogManager` outputs with `TaskRunner.statusReport`, which refreshes each tick.
## Build & Versioning
- Develop with `gradlew :1.21.1:runClient`; package via `gradlew :1.21.1:build` or `.\gradlew.bat build` on Windows.
- Pass `-Paltoclef.development` to bind a local `../baritone/dist/baritone-unoptimized-fabric` jar when hacking Baritone.
- ReplayMod preprocessors guard versioned code in `multiversion/versionedfields/*`; preserve `@Pattern` annotations and gating comments or the Gradle task fails.
- Resources under `src/main/resources` expand into `versions/1.21.1/build/resources`; rebuild after touching `fabric.mod.json` or mixins to refresh remapped jars.
- Add new Minecraft targets by updating `settings.gradle.kts` and `preprocess.createNode` in `root.gradle.kts`; keep yarn mapping ids (e.g., `12101`) aligned with the version number.