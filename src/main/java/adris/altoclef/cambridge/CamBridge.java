package adris.altoclef.cambridge;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasks.CraftGenericManuallyTask;
import adris.altoclef.tasks.CraftGenericWithRecipeBooksTask;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.SafeNetherPortalTask;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasks.speedrun.beatgame.BeatMinecraftTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalSpeedrunTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInBlastFurnaceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.movement.GoToStrongholdPortalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.KillEndermanTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Runtime bridge that gathers bot telemetry and emits compact camera events.
 */
public final class CamBridge implements AutoCloseable {

    private static final long BIG_EVENT_COOLDOWN_MS = 6_000L;
    private static final long FULL_INSPECT_COOLDOWN_MS = 20_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final long MILESTONE_INACTIVITY_FLUSH_MS = 600L;
    private static final long MILESTONE_MAX_WINDOW_MS = 2_400L;
    private static final int RING_CAPACITY = 64;
    private static final long STALL_THRESHOLD_MS = 18_000L;
    private static final double PROGRESS_DISTANCE_SQ = 1.5 * 1.5;
    private static final long PANEL_COOLDOWN_MS = 10_000L;
    private static final long MINI_HUD_INTERVAL_MS = 2_500L;
    private static final double STATIONARY_DISTANCE_SQ = 0.25;
    private static final long STATUS_MIN_INTERVAL_MS = 750L;
    private static final long STATUS_FORCE_INTERVAL_MS = 5_000L;
    private static final long REROUTE_STATUS_WINDOW_MS = 6_000L;

    private final AltoClef mod;
    private final ObjectWriter writer;
    private final ExecutorService dispatcher;
    private final Deque<CamEvent> history = new ArrayDeque<>(RING_CAPACITY);
    private final List<CamEvent> pendingSend = new ArrayList<>();
    private final Queue<CamEvent> deferredBigEvents = new ConcurrentLinkedQueue<>();
    private final Map<HazardType, HazardState> hazardStates = new EnumMap<>(HazardType.class);
    private final MilestoneAccumulator milestoneAccumulator = new MilestoneAccumulator();
    private final AtomicLong nextEventId = new AtomicLong(1L);
    private final Map<ActivityKind, ActivityState> activityStates = new EnumMap<>(ActivityKind.class);
    private final StatusTracker statusTracker = new StatusTracker();

    private CamBridgeTransport transport;
    private boolean enabled;
    private long lastBigEventMs = Long.MIN_VALUE;
    private long lastFullInspectMs = Long.MIN_VALUE;
    private long lastHeartbeatMs = Long.MIN_VALUE;
    private long lastPanelStartMs = Long.MIN_VALUE;
    private long lastMiniHudMs = Long.MIN_VALUE;
    private CamPhase currentPhase;
    private Task currentTask;
    private ResourceSnapshot lastResources;
    private Dimension lastDimension;
    private boolean stallActive;
    private Vec3d lastProgressPos;
    private long lastProgressMs;
    private long lastTransportErrorLogMs;
    private Vec3d lastStationaryPos;
    private long stationarySinceMs = Long.MIN_VALUE;

    public CamBridge(AltoClef mod) {
        this.mod = mod;
        this.writer = new ObjectMapper().writer();
        this.dispatcher = Executors.newSingleThreadExecutor(new CamBridgeThreadFactory());
        for (HazardType type : HazardType.values()) {
            hazardStates.put(type, new HazardState());
        }
        for (ActivityKind kind : ActivityKind.values()) {
            activityStates.put(kind, new ActivityState());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            } catch (Exception ignored) {
            }
        }, "CamBridge-shutdown"));
    }

    public void onSettingsReload(Settings settings) {
        boolean shouldEnable = settings.isCamBridgeEnabled();
        if (!shouldEnable) {
            disable();
            return;
        }

        CamBridgeTransport newTransport = null;
        String transportMode = settings.getCamBridgeTransport().toLowerCase();
        try {
            switch (transportMode) {
                case "udp" -> newTransport = createUdpTransport(settings);
                case "file" -> newTransport = new FileCamBridgeTransport(Path.of(settings.getCamBridgeFilePath()));
                default -> {
                    Debug.logWarning("Unknown CamBridge transport: " + transportMode + ". Falling back to UDP.");
                    newTransport = createUdpTransport(settings);
                }
            }
        } catch (IOException ex) {
            logTransportError("Failed to initialize CamBridge transport", ex);
            disable();
            return;
        }

        swapTransport(newTransport);
        enabled = true;
    }

    public void onClientTick() {
        if (!enabled) {
            return;
        }
        tryDispatchDeferred();

        if (!AltoClef.inGame()) {
            resetState();
            flushIfNeeded();
            return;
        }

        long now = System.currentTimeMillis();
        ResourceSnapshot currentSnapshot = ResourceSnapshot.capture(mod);

        updateDimension(currentSnapshot, now);
        updatePhase(currentSnapshot, now);
        updateTaskLifecycle(now);
        updateMilestones(currentSnapshot, now);
        updateHazards(currentSnapshot, now);
        updateStallState(now);
        updateActivities(currentSnapshot, now);
    updateStatus(currentSnapshot, now);
        emitHeartbeatIfDue(currentSnapshot, now);

        lastResources = currentSnapshot;
        flushIfNeeded();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        synchronized (this) {
            enabled = false;
            if (transport != null) {
                try {
                    transport.close();
                } catch (IOException ignored) {
                }
                transport = null;
            }
        }
        dispatcher.shutdownNow();
    }

    private void disable() {
        synchronized (this) {
            enabled = false;
            if (transport != null) {
                try {
                    transport.close();
                } catch (IOException ignored) {
                }
                transport = null;
            }
        }
    }

    private void swapTransport(CamBridgeTransport newTransport) {
        synchronized (this) {
            if (transport != null) {
                try {
                    transport.close();
                } catch (IOException ignored) {
                }
            }
            transport = newTransport;
        }
    }

    private CamBridgeTransport createUdpTransport(Settings settings) throws IOException {
        String host = settings.getCamBridgeHost();
        int primaryPort = settings.getCamBridgePort();
        List<Integer> mirrors = settings.getCamBridgeMirrorUdpPorts();
        List<CamBridgeTransport> transports = new ArrayList<>();
        Set<Integer> seenPorts = new HashSet<>();

        try {
            transports.add(new UdpCamBridgeTransport(host, primaryPort));
            seenPorts.add(primaryPort);
            if (mirrors != null) {
                for (int mirrorPort : mirrors) {
                    if (mirrorPort <= 0 || seenPorts.contains(mirrorPort)) {
                        continue;
                    }
                    transports.add(new UdpCamBridgeTransport(host, mirrorPort));
                    seenPorts.add(mirrorPort);
                }
            }
        } catch (IOException ex) {
            for (CamBridgeTransport transport : transports) {
                try {
                    transport.close();
                } catch (IOException ignored) {
                }
            }
            throw ex;
        }

        if (transports.isEmpty()) {
            throw new IOException("No valid CamBridge UDP transports configured.");
        }
        if (transports.size() == 1) {
            return transports.get(0);
        }
        return new CompositeCamBridgeTransport(transports);
    }

    private void resetState() {
        currentTask = null;
        currentPhase = null;
        lastResources = null;
        lastDimension = null;
        stallActive = false;
        milestoneAccumulator.reset();
        hazardStates.values().forEach(HazardState::resetImmediate);
        activityStates.values().forEach(ActivityState::reset);
        lastPanelStartMs = Long.MIN_VALUE;
        lastMiniHudMs = Long.MIN_VALUE;
        lastStationaryPos = null;
        stationarySinceMs = Long.MIN_VALUE;
        statusTracker.reset();
    }

    private void updatePhase(ResourceSnapshot snapshot, long now) {
        CamPhase detected = detectPhase(snapshot, WorldHelper.getCurrentDimension());
        if (!Objects.equals(currentPhase, detected)) {
            CamPhase previous = currentPhase;
            currentPhase = detected;
            if (previous != null) {
                emitEvent(CamEvent.phase(CamEventType.PHASE_EXIT, nextId(), now, previous, previous, Map.of()), now);
            }
            if (detected != null) {
                Map<String, Object> payload = new HashMap<>();
                BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
                Map<String, Object> targets = new HashMap<>();
                targets.put("rods_target", 6);
                targets.put("pearls_target", config.targetEyes);
                targets.put("beds_target", config.requiredBeds);
                targets.put("gold_plan", config.barterPearlsInsteadOfEndermanHunt ? "barter" : "hunt");
                payload.put("shopping", targets);
                CamEvent enter = CamEvent.phase(CamEventType.PHASE_ENTER, nextId(), now, detected, detected, payload)
                        .withSuggestedMode("FULL", 8);
                emitEvent(enter, now);
            }
        }
    }

    private void updateTaskLifecycle(long now) {
        Task userTask = mod.getUserTaskChain() != null ? mod.getUserTaskChain().getCurrentTask() : null;
        if (!Objects.equals(currentTask, userTask)) {
            Task previous = currentTask;
            currentTask = userTask;
            if (previous != null) {
                boolean success = previous.isFinished() && !previous.stopped();
                statusTracker.onTaskFinished(success, now);
                Map<String, Object> payload = new HashMap<>();
                payload.put("task_key", previous.getClass().getSimpleName());
                payload.put("task_class", previous.getClass().getName());
                payload.put("success", success);
                if (!success) {
                    payload.put("reason", previous.stopped() ? "stopped" : "unknown");
                }
                CamEvent end = CamEvent.simple(CamEventType.TASK_END, nextId(), now, currentPhase, payload)
                        .withSuggestedMode(success ? null : "FULL", success ? null : 6);
                emitEvent(end, now);
            }
            if (userTask != null) {
                statusTracker.onTaskStarted(now);
                Map<String, Object> payload = new HashMap<>();
                payload.put("task_key", userTask.getClass().getSimpleName());
                payload.put("task_class", userTask.getClass().getName());
                payload.put("task_debug", userTask.toString());
                CamEvent start = CamEvent.simple(CamEventType.TASK_START, nextId(), now, currentPhase, payload)
                        .withSuggestedMode("QUICK", 5);
                emitEvent(start, now);
            }
        }
    }

    private void updateMilestones(ResourceSnapshot snapshot, long now) {
        if (lastResources == null) {
            return;
        }
        Map<String, ResourceSnapshot.StatChange> delta = snapshot.diff(lastResources);
        if (!delta.isEmpty()) {
            milestoneAccumulator.record(delta, snapshot.crossedThresholds(lastResources), now);
        }
        milestoneAccumulator.flushIfReady(now).ifPresent(payload -> {
            CamEvent m = CamEvent.simple(CamEventType.MILESTONE, nextId(), now, currentPhase, payload);
            emitEvent(m, now);
        });
    }

    private void updateHazards(ResourceSnapshot snapshot, long now) {
        hazardStates.forEach((type, state) -> {
            boolean signal = switch (type) {
                case COMBAT -> detectCombatHazard();
                case FIRE -> mod.getPlayer().isOnFire();
                case LAVA -> mod.getPlayer().isInLava();
                case FALLING -> mod.getMLGBucketChain().isFalling(mod) || mod.getPlayer().fallDistance > 3;
                case DROWNING -> mod.getPlayer().isSubmergedInWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir();
                case LOW_HP -> (mod.getPlayer().getHealth() + mod.getPlayer().getAbsorptionAmount()) <= 12.0f;
            };
            if (state.update(signal, now)) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("hazard", type.name());
                payload.put("state", state.isActive() ? "start" : "stop");
                if (type == HazardType.LOW_HP) {
                    payload.put("health", roundOneDecimal(mod.getPlayer().getHealth() + mod.getPlayer().getAbsorptionAmount()));
                }
                CamEvent hazard = CamEvent.simple(CamEventType.HAZARD, nextId(), now, currentPhase, payload);
                emitEvent(hazard, now);
                if (type == HazardType.LAVA && state.isActive()) {
                    emitEvent(CamEvent.simple(CamEventType.REROUTE, nextId(), now, currentPhase, Map.of("reason", "lava_blocked_path")), now);
                    statusTracker.registerReroute(now);
                }
            }
        });
    }

    private void updateDimension(ResourceSnapshot snapshot, long now) {
        Dimension dim = WorldHelper.getCurrentDimension();
        if (dim != lastDimension) {
            lastDimension = dim;
            Map<String, Object> payload = Map.of("dimension", dim.name());
            CamEvent evt = CamEvent.simple(CamEventType.DIM_CHANGE, nextId(), now, currentPhase, payload)
                    .withSuggestedMode("QUICK", 4);
            emitEvent(evt, now);
        }
    }

    private void updateStallState(long now) {
        if (mod.getPlayer() == null) {
            stallActive = false;
            return;
        }
        Vec3d pos = mod.getPlayer().getPos();
        if (lastProgressPos == null || pos.squaredDistanceTo(lastProgressPos) > PROGRESS_DISTANCE_SQ || currentTask == null) {
            lastProgressPos = pos;
            lastProgressMs = now;
            if (stallActive) {
                stallActive = false;
            }
            return;
        }
        long stuckMs = now - lastProgressMs;
        if (stuckMs >= STALL_THRESHOLD_MS && !stallActive) {
            stallActive = true;
            String near = inferStallContext();
            Map<String, Object> payload = new HashMap<>();
            payload.put("duration_sec", roundOneDecimal(stuckMs / 1000.0));
            payload.put("near", near);
            emitEvent(CamEvent.simple(CamEventType.STALL, nextId(), now, currentPhase, payload), now);
        } else if (stallActive) {
            lastProgressPos = pos;
        }
    }

    private void updateStatus(ResourceSnapshot snapshot, long now) {
        StatusDescriptor descriptor = determineStatus(currentTask, snapshot, currentPhase);
        StatusSupplement supplement = buildStatusSupplement(currentTask);
        statusTracker.tick(descriptor, currentPhase, stallActive, supplement, now);
        statusTracker.pollEmit(now).ifPresent(payload -> {
            CamEvent status = CamEvent.simple(CamEventType.STATUS_NOW, nextId(), now, currentPhase, payload)
                    .withSuggestedMode("QUICK", 2);
            emitEvent(status, now);
        });
    }

    private StatusDescriptor determineStatus(Task task, ResourceSnapshot snapshot, CamPhase phase) {
        if (isMobDefenseEngaged()) {
            return StatusDescriptor.mobDefense();
        }
        BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
        StatusDescriptor fromTask = mapTaskStatus(task, snapshot, config);
        if (fromTask != null) {
            return fromTask;
        }
        return defaultStatusForPhase(phase, snapshot, config);
    }

    private StatusSupplement buildStatusSupplement(Task userTask) {
        boolean mobDefenseActive = isMobDefenseEngaged();
        String currentSummary = mobDefenseActive ? "Mob Defense – Hunt nearby hostiles" : sanitizeTaskSummary(userTask);
        List<String> futureSummaries = List.of();

        TaskRunner runner = mod.getTaskRunner();
        if (runner != null) {
            for (TaskRunner.ChainDiagnostics diagnostics : runner.getChainDiagnostics()) {
                if ("User Tasks".equalsIgnoreCase(diagnostics.name())) {
                    List<String> sanitized = diagnostics.tasks().stream()
                            .map(TaskRunner.TaskDiagnostics::summary)
                            .map(this::sanitizeTaskSummary)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(ArrayList::new));
                    if (!sanitized.isEmpty()) {
                        if (!mobDefenseActive) {
                            currentSummary = sanitized.get(0);
                        }
                        if (sanitized.size() > 1) {
                            int end = Math.min(sanitized.size(), 4);
                            futureSummaries = List.copyOf(sanitized.subList(1, end));
                        }
                    }
                    break;
                }
            }
        }

        return new StatusSupplement(currentSummary, futureSummaries, mobDefenseActive);
    }

    private boolean isMobDefenseEngaged() {
        MobDefenseChain defense = mod.getMobDefenseChain();
        if (defense == null) {
            return false;
        }
        if (defense.isShielding()) {
            return true;
        }
        Task defenseTask = defense.getCurrentTask();
        return defenseTask != null && !defenseTask.isFinished() && !defenseTask.stopped();
    }

    private String sanitizeTaskSummary(Task task) {
        if (task == null) {
            return null;
        }
        String summary = sanitizeTaskSummary(task.toString());
        if (summary != null) {
            return summary;
        }
        return task.getClass().getSimpleName();
    }

    private String sanitizeTaskSummary(String raw) {
        if (raw == null) {
            return null;
        }
        String summary = raw.replace('\n', ' ').trim();
        return summary.isEmpty() ? null : summary;
    }

    private StatusDescriptor mapTaskStatus(Task task, ResourceSnapshot snapshot, BeatMinecraftConfig config) {
        if (task == null) {
            return null;
        }
        if (task instanceof CollectBlazeRodsTask) {
            return StatusDescriptor.of("collect_rods", "Fortress – Blaze Rods", new ProgressSnapshot("rods", snapshot.rods, 6));
        }
        if (task instanceof TradeWithPiglinsTask) {
            return StatusDescriptor.of("barter_pearls", "Nether – Bartering Pearls", new ProgressSnapshot("pearls", snapshot.pearls, config.targetEyes));
        }
        if (task instanceof KillEndermanTask) {
            return StatusDescriptor.of("hunt_endermen", "Overworld – Hunt Endermen", new ProgressSnapshot("pearls", snapshot.pearls, config.targetEyes));
        }
        if (task instanceof GoToStrongholdPortalTask) {
            return StatusDescriptor.of("locate_stronghold", "Stronghold – Locate Portal", null);
        }
        if (task instanceof ConstructNetherPortalBucketTask
                || task instanceof ConstructNetherPortalSpeedrunTask
                || task instanceof ConstructNetherPortalObsidianTask
                || task instanceof SafeNetherPortalTask) {
            return StatusDescriptor.of("build_portal", "Overworld – Build Portal", null);
        }
        String name = task.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("bed") && name.contains("cycle")) {
            return StatusDescriptor.of("dragon_bed_cycle", "End – Dragon Cycle", new ProgressSnapshot("beds", snapshot.beds, config.requiredBeds));
        }
        return null;
    }

    private StatusDescriptor defaultStatusForPhase(CamPhase phase, ResourceSnapshot snapshot, BeatMinecraftConfig config) {
        if (phase == null) {
            return StatusDescriptor.idle();
        }
        return switch (phase) {
            case OVERWORLD_PREP -> StatusDescriptor.of("build_portal", "Overworld – Build Portal", null);
            case FORTRESS -> StatusDescriptor.of("collect_rods", "Fortress – Blaze Rods", new ProgressSnapshot("rods", snapshot.rods, 6));
            case PEARLS -> {
                boolean barter = config.barterPearlsInsteadOfEndermanHunt;
                String key = barter ? "barter_pearls" : "hunt_endermen";
                String hint = barter ? "Nether – Bartering Pearls" : "Overworld – Hunt Endermen";
                yield StatusDescriptor.of(key, hint, new ProgressSnapshot("pearls", snapshot.pearls, config.targetEyes));
            }
            case NETHER -> {
                if (snapshot.eyes < config.targetEyes) {
                    yield StatusDescriptor.of("craft_eyes", "Nether – Craft Eyes", new ProgressSnapshot("eyes", snapshot.eyes, config.targetEyes));
                }
                yield StatusDescriptor.of("enter_end", "Nether – Exit Prep", null);
            }
            case STRONGHOLD -> {
                if (snapshot.eyes < config.targetEyes) {
                    yield StatusDescriptor.of("craft_eyes", "Stronghold – Craft Eyes", new ProgressSnapshot("eyes", snapshot.eyes, config.targetEyes));
                }
                yield StatusDescriptor.of("locate_stronghold", "Stronghold – Locate Portal", null);
            }
            case END -> StatusDescriptor.of("dragon_bed_cycle", "End – Dragon Cycle", new ProgressSnapshot("beds", snapshot.beds, config.requiredBeds));
        };
    }

    private void emitHeartbeatIfDue(ResourceSnapshot snapshot, long now) {
        if (now - lastHeartbeatMs < HEARTBEAT_INTERVAL_MS) {
            return;
        }
        lastHeartbeatMs = now;
        Map<String, Object> payload = new HashMap<>();
        payload.put("phase", currentPhase != null ? currentPhase.name() : "UNKNOWN");
        payload.put("brief", snapshot.brief());
        statusTracker.appendHeartbeat(payload);
        CamEvent hb = CamEvent.simple(CamEventType.HEARTBEAT, nextId(), now, currentPhase, payload);
        emitEvent(hb, now);
    }

    private void updateActivities(ResourceSnapshot snapshot, long now) {
        List<Task> tasks = getActiveTasks();
        boolean hazardActive = anyHazardActive();
        boolean stationary = evaluateStationary(now);
        updateCraftingActivity(tasks, hazardActive, now);
        updateSmeltingActivity(tasks, hazardActive, now);
        updateSpawnActivity(tasks, hazardActive, now);
        updateContextActivity(tasks, hazardActive, stationary, now);
        updateStrongholdActivity(tasks, hazardActive, now);
        emitMiniHud(snapshot, now);
    }

    private boolean anyHazardActive() {
        for (HazardState state : hazardStates.values()) {
            if (state.isActive()) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateStationary(long now) {
        if (mod.getPlayer() == null) {
            lastStationaryPos = null;
            stationarySinceMs = Long.MIN_VALUE;
            return false;
        }
        Vec3d pos = mod.getPlayer().getPos();
        if (lastStationaryPos == null) {
            lastStationaryPos = pos;
            stationarySinceMs = now;
            return false;
        }
        if (pos.squaredDistanceTo(lastStationaryPos) > STATIONARY_DISTANCE_SQ) {
            lastStationaryPos = pos;
            stationarySinceMs = now;
            return false;
        }
        lastStationaryPos = pos;
        if (stationarySinceMs == Long.MIN_VALUE) {
            stationarySinceMs = now;
        }
        return now - stationarySinceMs >= 1_200L;
    }

    private List<Task> getActiveTasks() {
        if (mod.getUserTaskChain() != null && mod.getUserTaskChain().isActive()) {
            return new ArrayList<>(mod.getUserTaskChain().getTasks());
        }
        return Collections.emptyList();
    }

    private Task findTask(List<Task> tasks, Predicate<Task> predicate) {
        for (Task task : tasks) {
            if (predicate.test(task)) {
                return task;
            }
        }
        return null;
    }

    private boolean isCraftTask(Task task) {
        if (task == null) {
            return false;
        }
        if (task instanceof CraftInInventoryTask
                || task instanceof CraftInTableTask
                || task instanceof CraftGenericManuallyTask
                || task instanceof CraftGenericWithRecipeBooksTask) {
            return true;
        }
        String name = task.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("craft") && !name.contains("squash");
    }

    private boolean isSmeltTask(Task task) {
        return task instanceof SmeltInFurnaceTask
                || task instanceof SmeltInSmokerTask
                || task instanceof SmeltInBlastFurnaceTask;
    }

    private boolean isPortalTask(Task task) {
        return task instanceof ConstructNetherPortalBucketTask
                || task instanceof ConstructNetherPortalSpeedrunTask
                || task instanceof ConstructNetherPortalObsidianTask
                || task instanceof SafeNetherPortalTask
                || task.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("portal");
    }

    private boolean isInventoryTask(Task task) {
        String simple = task.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String debug = task.toString().toLowerCase(Locale.ROOT);
        return simple.contains("inventory")
                || simple.contains("organize")
                || simple.contains("sorting")
                || debug.contains("inventory")
                || debug.contains("organize")
                || debug.contains("sorting");
    }

    private String deriveCraftContext(Task task) {
        String name = task.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("bed")) {
            return "beds";
        }
        if (name.contains("eye") || name.contains("ender")) {
            return "eyes";
        }
        if (name.contains("flint") || name.contains("steel") || name.contains("bucket") || name.contains("portal")) {
            return "portal_kit";
        }
        if (name.contains("shield") || name.contains("sword") || name.contains("pick") || name.contains("axe")) {
            return "tools";
        }
        return "generic";
    }

    private String deriveSmeltContext(Task task) {
        if (task instanceof SmeltInSmokerTask) {
            return "cooking";
        }
        return "smelting";
    }

    private void updateCraftingActivity(List<Task> tasks, boolean hazardActive, long now) {
        ActivityState state = activityStates.get(ActivityKind.CRAFTING);
        if (handleHazardGate(state, ActivityKind.CRAFTING, hazardActive, now)) {
            return;
        }
        Task craftTask = findTask(tasks, this::isCraftTask);
        if (craftTask != null) {
            String context = deriveCraftContext(craftTask);
            if (!state.active || !Objects.equals(state.context, context)) {
                state.active = true;
                state.context = context;
                state.anchor = mod.getPlayer() != null ? mod.getPlayer().getPos() : null;
                state.lastStateChangeMs = now;
                Map<String, Object> extra = new HashMap<>();
                extra.put("label", context);
                emitActivityStart(ActivityKind.CRAFTING, extra, now, true);
            }
        } else if (state.active) {
            String reason = "complete";
            if (state.anchor != null && mod.getPlayer() != null && mod.getPlayer().getPos().squaredDistanceTo(state.anchor) > 4.0) {
                reason = "moved";
            }
            emitActivityStop(ActivityKind.CRAFTING, reason, now);
            state.reset();
        }
    }

    private void updateSmeltingActivity(List<Task> tasks, boolean hazardActive, long now) {
        ActivityState state = activityStates.get(ActivityKind.SMELTING);
        if (handleHazardGate(state, ActivityKind.SMELTING, hazardActive, now)) {
            return;
        }
        Task smeltTask = findTask(tasks, this::isSmeltTask);
        if (smeltTask != null) {
            String context = deriveSmeltContext(smeltTask);
            if (!state.active || !Objects.equals(state.context, context)) {
                state.active = true;
                state.context = context;
                state.anchor = mod.getPlayer() != null ? mod.getPlayer().getPos() : null;
                state.lastStateChangeMs = now;
                Map<String, Object> extra = new HashMap<>();
                extra.put("label", context);
                emitActivityStart(ActivityKind.SMELTING, extra, now, true);
            }
        } else if (state.active) {
            String reason = "complete";
            if (state.anchor != null && mod.getPlayer() != null && mod.getPlayer().getPos().squaredDistanceTo(state.anchor) > 25.0) {
                reason = "abandoned";
            }
            emitActivityStop(ActivityKind.SMELTING, reason, now);
            state.reset();
        }
    }

    private void updateSpawnActivity(List<Task> tasks, boolean hazardActive, long now) {
        ActivityState state = activityStates.get(ActivityKind.SPAWN_WAIT);
        if (handleHazardGate(state, ActivityKind.SPAWN_WAIT, hazardActive, now)) {
            return;
        }
        ActivityDescriptor descriptor = detectSpawnActivity(tasks);
        if (descriptor != null) {
            if (!state.active || !Objects.equals(state.context, descriptor.label)) {
                state.active = true;
                state.context = descriptor.label;
                state.anchor = mod.getPlayer() != null ? mod.getPlayer().getPos() : null;
                state.lastStateChangeMs = now;
                Map<String, Object> extra = new HashMap<>();
                extra.put("label", descriptor.label);
                emitActivityStart(ActivityKind.SPAWN_WAIT, extra, now, true);
            }
        } else if (state.active) {
            emitActivityStop(ActivityKind.SPAWN_WAIT, "task_end", now);
            state.reset();
        }
    }

    private void updateContextActivity(List<Task> tasks, boolean hazardActive, boolean stationary, long now) {
        ActivityState state = activityStates.get(ActivityKind.CONTEXT);
        if (handleHazardGate(state, ActivityKind.CONTEXT, hazardActive, now)) {
            return;
        }
        ActivityDescriptor descriptor = detectContextActivity(tasks, stationary);
        if (descriptor != null) {
            if (!state.active || !Objects.equals(state.context, descriptor.label)) {
                state.active = true;
                state.context = descriptor.label;
                state.anchor = mod.getPlayer() != null ? mod.getPlayer().getPos() : null;
                state.lastStateChangeMs = now;
                Map<String, Object> extra = new HashMap<>();
                extra.put("label", descriptor.label);
                emitActivityStart(ActivityKind.CONTEXT, extra, now, true);
            }
        } else if (state.active) {
            String reason = "task_end";
            if (!stationary && !"PORTAL_KIT".equals(state.context)) {
                reason = "movement";
            }
            emitActivityStop(ActivityKind.CONTEXT, reason, now);
            state.reset();
        }
    }

    private void updateStrongholdActivity(List<Task> tasks, boolean hazardActive, long now) {
        ActivityState state = activityStates.get(ActivityKind.STRONGHOLD);
        if (handleHazardGate(state, ActivityKind.STRONGHOLD, hazardActive, now)) {
            return;
        }
        Task strongholdTask = findTask(tasks, t -> t instanceof GoToStrongholdPortalTask);
        if (strongholdTask != null) {
            if (!state.active) {
                state.active = true;
                state.context = "STRONGHOLD_TRIANGULATE";
                state.anchor = mod.getPlayer() != null ? mod.getPlayer().getPos() : null;
                state.lastStateChangeMs = now;
                Map<String, Object> extra = new HashMap<>();
                extra.put("label", state.context);
                emitActivityStart(ActivityKind.STRONGHOLD, extra, now, true);
            }
        } else if (state.active) {
            emitActivityStop(ActivityKind.STRONGHOLD, "task_end", now);
            state.reset();
        }
    }

    private boolean handleHazardGate(ActivityState state, ActivityKind kind, boolean hazardActive, long now) {
        if (hazardActive) {
            if (state.active) {
                emitActivityStop(kind, "hazard", now);
                state.active = false;
                state.hazardHold = true;
                state.context = null;
                state.anchor = null;
                state.lastStateChangeMs = now;
            }
            return true;
        }
        if (state.hazardHold) {
            state.hazardHold = false;
        }
        return false;
    }

    private ActivityDescriptor detectSpawnActivity(List<Task> tasks) {
        for (Task task : tasks) {
            if (task instanceof CollectBlazeRodsTask) {
                return new ActivityDescriptor("FORTRESS_BLAZE");
            }
        }
        for (Task task : tasks) {
            if (task instanceof KillEndermanTask) {
                return new ActivityDescriptor("HUNT_ENDERMEN");
            }
        }
        for (Task task : tasks) {
            if (task instanceof TradeWithPiglinsTask) {
                return new ActivityDescriptor("BARTER_PEARLS");
            }
        }
        return null;
    }

    private ActivityDescriptor detectContextActivity(List<Task> tasks, boolean stationary) {
        for (Task task : tasks) {
            if (isPortalTask(task)) {
                return new ActivityDescriptor("PORTAL_KIT");
            }
        }
        if (!stationary) {
            return null;
        }
        for (Task task : tasks) {
            if (isInventoryTask(task)) {
                return new ActivityDescriptor("INVENTORY_TIDY");
            }
        }
        return null;
    }

    private void emitActivityStart(ActivityKind kind, Map<String, Object> extra, long now, boolean fullPanel) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("activity", kind.name());
        payload.put("state", "start");
        if (extra != null) {
            payload.putAll(extra);
        }
        CamEvent event = CamEvent.simple(CamEventType.ACTIVITY_CONTEXT, nextId(), now, currentPhase, payload);
        if (fullPanel) {
            if (now - lastPanelStartMs < PANEL_COOLDOWN_MS) {
                event = event.withSuggestedMode("QUICK", 4);
            } else {
                lastPanelStartMs = now;
                event = event.withSuggestedMode("FULL", null);
            }
        }
        emitEvent(event, now);
    }

    private void emitActivityStop(ActivityKind kind, String reason, long now) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("activity", kind.name());
        payload.put("state", "stop");
        if (reason != null) {
            payload.put("reason", reason);
        }
        CamEvent event = CamEvent.simple(CamEventType.ACTIVITY_CONTEXT, nextId(), now, currentPhase, payload)
                .withSuggestedMode("QUICK", 2);
        emitEvent(event, now);
    }

    private void emitMiniHud(ResourceSnapshot snapshot, long now) {
        if (now - lastMiniHudMs < MINI_HUD_INTERVAL_MS) {
            return;
        }
        lastMiniHudMs = now;
        Map<String, Object> payload = new HashMap<>();
        payload.put("pearls", snapshot.pearls);
        payload.put("rods", snapshot.rods);
        payload.put("beds", snapshot.beds);
        payload.put("food", snapshot.food);
        payload.put("arrows", snapshot.arrows);
        payload.put("phase_slot", determinePhaseSlot());
        CamEvent mini = CamEvent.simple(CamEventType.MINI_HUD, nextId(), now, currentPhase, payload);
        emitEvent(mini, now);
    }

    private String determinePhaseSlot() {
        if (currentPhase == null) {
            return "IRON";
        }
        return switch (currentPhase) {
            case OVERWORLD_PREP -> "IRON";
            case NETHER, FORTRESS, PEARLS -> "GOLD";
            case STRONGHOLD -> "EYES";
            case END -> "ARROWS";
        };
    }

    private void emitEvent(CamEvent event, long now) {
        if (event == null) return;
        if (event.hasFullInspectSuggestion()) {
            if (now - lastFullInspectMs < FULL_INSPECT_COOLDOWN_MS) {
                event = event.withoutSuggestion();
            } else {
                lastFullInspectMs = now;
            }
        }
        if (event.type.isBig()) {
            if (now - lastBigEventMs < BIG_EVENT_COOLDOWN_MS) {
                deferredBigEvents.add(event);
                return;
            }
            lastBigEventMs = now;
        }
        recordEvent(event);
    }

    private void tryDispatchDeferred() {
        if (deferredBigEvents.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBigEventMs < BIG_EVENT_COOLDOWN_MS) {
            return;
        }
        CamEvent queued = deferredBigEvents.poll();
        if (queued == null) {
            return;
        }
        emitEvent(queued, now);
    }

    private void recordEvent(CamEvent event) {
        history.addLast(event);
        if (history.size() > RING_CAPACITY) {
            history.removeFirst();
        }
        pendingSend.add(event);
    }

    private void flushIfNeeded() {
        if (pendingSend.isEmpty()) {
            return;
        }
        CamBridgeTransport active;
        synchronized (this) {
            active = transport;
        }
        if (active == null || !active.isHealthy()) {
            pendingSend.clear();
            return;
        }
        Collection<String> serialized = pendingSend.stream().map(this::toJsonLine).toList();
        pendingSend.clear();
        dispatcher.submit(() -> {
            try {
                active.sendBatch(serialized);
            } catch (IOException ex) {
                logTransportError("CamBridge send failed", ex);
            }
        });
    }

    private void logTransportError(String message, Exception ex) {
        long now = System.currentTimeMillis();
        if (now - lastTransportErrorLogMs < 5_000L) {
            return;
        }
        lastTransportErrorLogMs = now;
        Debug.logWarning(message + ": " + ex.getMessage());
    }

    private String toJsonLine(CamEvent event) {
        try {
            return writer.writeValueAsString(event.toJson());
        } catch (JsonProcessingException e) {
            Debug.logWarning("CamBridge serialization failure: " + e.getMessage());
            return "{}";
        }
    }

    private CamPhase detectPhase(ResourceSnapshot snapshot, Dimension dimension) {
        BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
        if (dimension == Dimension.END) {
            return CamPhase.END;
        }
        if (dimension == Dimension.NETHER) {
            if (snapshot.rods < 6) {
                return CamPhase.FORTRESS;
            }
            if (snapshot.pearls < config.targetEyes) {
                return CamPhase.PEARLS;
            }
            return CamPhase.NETHER;
        }
        if (snapshot.rods >= 6 && snapshot.pearls >= config.targetEyes) {
            return CamPhase.STRONGHOLD;
        }
        return CamPhase.OVERWORLD_PREP;
    }

    private boolean detectCombatHazard() {
        MobDefenseChain defense = mod.getMobDefenseChain();
        if (defense != null && defense.isShielding()) {
            return true;
        }
        EntityTracker tracker = mod.getEntityTracker();
        if (tracker == null) {
            return false;
        }
        List<LivingEntity> hostiles = tracker.getHostiles();
        if (hostiles == null || hostiles.isEmpty()) {
            return false;
        }
        return hostiles.stream().anyMatch(entity -> entity != null && entity.isAlive() &&
                entity.squaredDistanceTo(mod.getPlayer()) < 64 && EntityHelper.isProbablyHostileToPlayer(mod, entity));
    }

    private String inferStallContext() {
        if (lastResources == null) {
            return null;
        }
        if (lastDimension == Dimension.NETHER) {
            BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
            if (lastResources.rods < 6) {
                return "fortress";
            }
            if (config.barterPearlsInsteadOfEndermanHunt && lastResources.gold > 0) {
                return "bastion";
            }
            if (lastResources.pearls < config.targetEyes) {
                return "pearls";
            }
        }
        if (lastDimension == Dimension.OVERWORLD && lastResources.rods >= 6) {
            return "stronghold";
        }
        return null;
    }

    private long nextId() {
        return nextEventId.getAndIncrement();
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private enum StatusState {
        RUNNING("running"),
        PAUSED("paused"),
        STALLED("stalled"),
        REROUTING("rerouting"),
        SUCCESS("success"),
        FAILED("failed");

        private final String wire;

        StatusState(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private record ProgressSnapshot(String key, int current, int target) {
        Map<String, Object> toJson() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("key", key);
            payload.put("current", current);
            if (target > 0) {
                payload.put("target", target);
                payload.put("display", current + " / " + target);
            } else {
                payload.put("display", Integer.toString(current));
            }
            return payload;
        }
    }

    private record StatusDescriptor(String taskKey, String hint, ProgressSnapshot progress) {
        static StatusDescriptor of(String taskKey, String hint, ProgressSnapshot progress) {
            return new StatusDescriptor(taskKey, hint, progress);
        }

        static StatusDescriptor idle() {
            return new StatusDescriptor("idle", "Idle", null);
        }

        static StatusDescriptor mobDefense() {
            return new StatusDescriptor("mob_defense", "Combat – Hunt Hostiles", null);
        }
    }

    private record StatusSupplement(String currentSummary, List<String> futureSummaries, boolean mobDefenseActive) {
        static StatusSupplement empty() {
            return new StatusSupplement(null, List.of(), false);
        }
    }

    private final class StatusTracker {
        private StatusDescriptor descriptor = StatusDescriptor.idle();
        private CamPhase phase;
        private ProgressSnapshot progress;
        private StatusState state = StatusState.PAUSED;
        private long startedAtMs = System.currentTimeMillis();
        private long lastEmitMs = Long.MIN_VALUE;
        private boolean dirty;
        private StatusState overrideState;
        private long overrideUntilMs;
        private long rerouteUntilMs;
        private StatusSupplement supplement = StatusSupplement.empty();

        void tick(StatusDescriptor next, CamPhase nextPhase, boolean stalled, StatusSupplement nextSupplement, long now) {
            if (next == null) {
                next = StatusDescriptor.idle();
            }
            if (!Objects.equals(descriptor, next)) {
                descriptor = next;
                startedAtMs = now;
                progress = next.progress();
                dirty = true;
                overrideState = null;
                overrideUntilMs = 0L;
            } else if (!Objects.equals(progress, next.progress())) {
                progress = next.progress();
                dirty = true;
            }
            StatusSupplement resolvedSupplement = nextSupplement == null ? StatusSupplement.empty() : nextSupplement;
            if (!Objects.equals(supplement, resolvedSupplement)) {
                supplement = resolvedSupplement;
                dirty = true;
            }

            if (!Objects.equals(phase, nextPhase)) {
                phase = nextPhase;
                dirty = true;
            }

            StatusState computed = computeState(stalled, now);
            if (computed != state) {
                state = computed;
                dirty = true;
            }

            if (now - lastEmitMs >= STATUS_FORCE_INTERVAL_MS) {
                dirty = true;
            }
        }

        private StatusState computeState(boolean stalled, long now) {
            if (overrideState != null && now >= overrideUntilMs) {
                overrideState = null;
            }
            if (overrideState != null && now < overrideUntilMs) {
                return overrideState;
            }
            if (stalled) {
                return StatusState.STALLED;
            }
            if (rerouteUntilMs > now) {
                return StatusState.REROUTING;
            }
            if (descriptor == null || "idle".equals(descriptor.taskKey())) {
                return StatusState.PAUSED;
            }
            return StatusState.RUNNING;
        }

        Optional<Map<String, Object>> pollEmit(long now) {
            if (!dirty || now - lastEmitMs < STATUS_MIN_INTERVAL_MS) {
                return Optional.empty();
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("phase", phase != null ? phase.name() : "UNKNOWN");
            payload.put("task_key", descriptor.taskKey());
            payload.put("hint", descriptor.hint());
            payload.put("state", state.wire());
            payload.put("started_at", Instant.ofEpochMilli(startedAtMs).toString());
            if (progress != null) {
                payload.put("progress", progress.toJson());
            }
            writeSupplement(payload);
            lastEmitMs = now;
            dirty = false;
            return Optional.of(payload);
        }

        void appendHeartbeat(Map<String, Object> payload) {
            payload.put("task_key", descriptor.taskKey());
            payload.put("state", state.wire());
            payload.put("hint", descriptor.hint());
            payload.put("started_at", Instant.ofEpochMilli(startedAtMs).toString());
            if (progress != null) {
                payload.put("progress", progress.toJson());
            }
            writeSupplement(payload);
        }

        void onTaskFinished(boolean success, long now) {
            overrideState = success ? StatusState.SUCCESS : StatusState.FAILED;
            overrideUntilMs = now + 3_000L;
            dirty = true;
        }

        void onTaskStarted(long now) {
            overrideState = null;
            overrideUntilMs = 0L;
            startedAtMs = now;
            dirty = true;
        }

        void registerReroute(long now) {
            rerouteUntilMs = Math.max(rerouteUntilMs, now + REROUTE_STATUS_WINDOW_MS);
            dirty = true;
        }

        void reset() {
            descriptor = StatusDescriptor.idle();
            phase = null;
            progress = null;
            state = StatusState.PAUSED;
            startedAtMs = System.currentTimeMillis();
            lastEmitMs = Long.MIN_VALUE;
            dirty = true;
            overrideState = null;
            overrideUntilMs = 0L;
            rerouteUntilMs = 0L;
            supplement = StatusSupplement.empty();
        }

        private void writeSupplement(Map<String, Object> payload) {
            if (supplement == null) {
                return;
            }
            if (supplement.currentSummary() == null && supplement.futureSummaries().isEmpty() && !supplement.mobDefenseActive()) {
                return;
            }
            Map<String, Object> queue = new LinkedHashMap<>();
            if (supplement.currentSummary() != null) {
                queue.put("current", supplement.currentSummary());
            }
            if (!supplement.futureSummaries().isEmpty()) {
                queue.put("future", supplement.futureSummaries());
            }
            queue.put("mob_defense", supplement.mobDefenseActive());
            payload.put("task_queue", queue);
            payload.put("mob_defense_active", supplement.mobDefenseActive());
        }
    }

    private enum ActivityKind {
        CRAFTING,
        SMELTING,
        SPAWN_WAIT,
        CONTEXT,
        STRONGHOLD
    }

    private enum HazardType {
        COMBAT,
        FIRE,
        LAVA,
        FALLING,
        DROWNING,
        LOW_HP
    }

    private static final class ActivityState {
        boolean active;
        boolean hazardHold;
        String context;
        Vec3d anchor;
        long lastStateChangeMs;

        void reset() {
            active = false;
            hazardHold = false;
            context = null;
            anchor = null;
            lastStateChangeMs = 0L;
        }
    }

    private static final class ActivityDescriptor {
        final String label;

        ActivityDescriptor(String label) {
            this.label = label;
        }
    }

    private static final class HazardState {
        private boolean active;
        private boolean pending;
        private boolean pendingValue;
        private long pendingSince;
        private long debounceMs;

        boolean update(boolean signal, long now) {
            if (signal == active) {
                pending = false;
                return false;
            }
            if (!pending || pendingValue != signal) {
                pending = true;
                pendingValue = signal;
                pendingSince = now;
                debounceMs = 800 + ThreadLocalRandom.current().nextInt(401);
                return false;
            }
            if (now - pendingSince >= debounceMs) {
                active = signal;
                pending = false;
                return true;
            }
            return false;
        }

        boolean isActive() {
            return active;
        }

        void resetImmediate() {
            active = false;
            pending = false;
        }
    }

    private final class MilestoneAccumulator {
        private final Map<String, Integer> latestValues = new HashMap<>();
        private final Map<String, Integer> deltaTotals = new HashMap<>();
        private final Set<String> crossed = new HashSet<>();
        private boolean active;
        private long firstChangeMs;
        private long lastChangeMs;

        void record(Map<String, ResourceSnapshot.StatChange> delta, Collection<String> thresholds, long now) {
            if (delta.isEmpty()) {
                return;
            }
            if (!active) {
                active = true;
                firstChangeMs = now;
            }
            lastChangeMs = now;
            delta.forEach((key, change) -> {
                latestValues.put(key, change.current());
                deltaTotals.merge(key, change.delta(), Integer::sum);
            });
            crossed.addAll(thresholds);
        }

        Optional<Map<String, Object>> flushIfReady(long now) {
            if (!active) {
                return Optional.empty();
            }
            if ((now - lastChangeMs) < MILESTONE_INACTIVITY_FLUSH_MS && (now - firstChangeMs) < MILESTONE_MAX_WINDOW_MS) {
                return Optional.empty();
            }
            if (latestValues.isEmpty()) {
                reset();
                return Optional.empty();
            }

            BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : latestValues.entrySet()) {
                String key = entry.getKey();
                int current = entry.getValue();
                int delta = deltaTotals.getOrDefault(key, 0);
                boolean reached = crossed.contains(key);
                if (delta == 0 && !reached) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("key", key);
                item.put("current", current);
                item.put("delta", delta);
                Integer target = resolveTarget(config, key);
                if (target != null) {
                    item.put("target", target);
                    item.put("target_met", reached || current >= target);
                } else if (reached) {
                    item.put("target_met", true);
                }
                items.add(item);
            }

            if (items.isEmpty()) {
                reset();
                return Optional.empty();
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("milestones", items);
            payload.put("duration_ms", Math.max(0L, lastChangeMs - firstChangeMs));
            reset();
            return Optional.of(payload);
        }

        void reset() {
            active = false;
            latestValues.clear();
            deltaTotals.clear();
            crossed.clear();
            firstChangeMs = 0L;
            lastChangeMs = 0L;
        }

        private Integer resolveTarget(BeatMinecraftConfig config, String key) {
            return switch (key) {
                case "pearls" -> config.targetEyes;
                case "rods" -> 6;
                case "eyes" -> config.targetEyes;
                case "beds" -> config.requiredBeds;
                default -> null;
            };
        }
    }

    private static final class ResourceSnapshot {
        final int pearls;
        final int rods;
        final int eyes;
        final int beds;
        final int arrows;
        final int food;
        final int iron;
        final int gold;
        final int obsidian;
        final int buckets;
        final int flintSteel;
        final Map<String, Object> tools;

        private ResourceSnapshot(int pearls, int rods, int eyes, int beds, int arrows, int food,
                                 int iron, int gold, int obsidian, int buckets, int flintSteel,
                                 Map<String, Object> tools) {
            this.pearls = pearls;
            this.rods = rods;
            this.eyes = eyes;
            this.beds = beds;
            this.arrows = arrows;
            this.food = food;
            this.iron = iron;
            this.gold = gold;
            this.obsidian = obsidian;
            this.buckets = buckets;
            this.flintSteel = flintSteel;
            this.tools = tools;
        }

        static ResourceSnapshot capture(AltoClef mod) {
            ItemStorageTracker storage = mod.getItemStorage();
            int pearls = storage.getItemCount(Items.ENDER_PEARL);
            int rods = storage.getItemCount(Items.BLAZE_ROD);
            int eyes = storage.getItemCount(Items.ENDER_EYE);
            int beds = storage.getItemCount(ItemHelper.BED);
            int arrows = storage.getItemCount(Items.ARROW);
            int food = (int) Math.round(StorageHelper.calculateInventoryFoodScore());
            int iron = storage.getItemCount(Items.IRON_INGOT, Items.RAW_IRON);
            int gold = storage.getItemCount(Items.GOLD_INGOT, Items.RAW_GOLD, Items.GOLD_NUGGET);
            int obsidian = storage.getItemCount(Items.OBSIDIAN);
            int buckets = storage.getItemCount(Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.POWDER_SNOW_BUCKET);
            int flintSteel = storage.getItemCount(Items.FLINT_AND_STEEL);
            Map<String, Object> tools = detectToolSnapshot(mod);
            return new ResourceSnapshot(pearls, rods, eyes, beds, arrows, food, iron, gold, obsidian, buckets, flintSteel, tools);
        }

        Map<String, StatChange> diff(ResourceSnapshot previous) {
            Map<String, StatChange> delta = new HashMap<>();
            compare(delta, "pearls", pearls, previous.pearls);
            compare(delta, "rods", rods, previous.rods);
            compare(delta, "eyes", eyes, previous.eyes);
            compare(delta, "beds", beds, previous.beds);
            compare(delta, "arrows", arrows, previous.arrows);
            compare(delta, "food", food, previous.food);
            compare(delta, "iron", iron, previous.iron);
            compare(delta, "gold", gold, previous.gold);
            compare(delta, "obsidian", obsidian, previous.obsidian);
            compare(delta, "buckets", buckets, previous.buckets);
            compare(delta, "flint_steel", flintSteel, previous.flintSteel);
            if (!Objects.equals(tools, previous.tools) && tools != null) {
                delta.put("tools", new StatChange(1, 1));
            }
            return delta;
        }

        Collection<String> crossedThresholds(ResourceSnapshot previous) {
            List<String> hit = new ArrayList<>();
            BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
            if (previous.pearls < config.targetEyes && pearls >= config.targetEyes) {
                hit.add("pearls");
            }
            if (previous.rods < 6 && rods >= 6) {
                hit.add("rods");
            }
            if (previous.eyes < config.minimumEyes && eyes >= config.minimumEyes) {
                hit.add("eyes");
            }
            if (previous.beds < config.requiredBeds && beds >= config.requiredBeds) {
                hit.add("beds");
            }
            return hit;
        }

        Map<String, Object> brief() {
            Map<String, Object> data = new HashMap<>();
            data.put("pearls", pearls);
            data.put("rods", rods);
            data.put("beds", beds);
            data.put("food", food);
            data.put("arrows", arrows);
            return data;
        }

        private static Map<String, Object> detectToolSnapshot(AltoClef mod) {
            PlayerInventory inventory = mod.getPlayer().getInventory();
            ItemStack bestStack = ItemStack.EMPTY;
            int bestTier = -1;
            String bestTierLabel = null;
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                Item item = stack.getItem();
                if (!(item instanceof PickaxeItem)) continue;
                int tier = tierScore(item);
                if (tier < 0) continue;
                if (tier > bestTier || (tier == bestTier && stack.getDamage() < bestStack.getDamage())) {
                    bestStack = stack;
                    bestTier = tier;
                    bestTierLabel = tierLabel(item);
                }
            }
            if (bestTier < 0 || bestStack.isEmpty() || bestTierLabel == null) {
                return Map.of();
            }
            int maxDamage = bestStack.getMaxDamage();
            int remaining = maxDamage - bestStack.getDamage();
            int pct = maxDamage > 0 ? (int) Math.round(remaining * 100.0 / maxDamage) : 100;
            Map<String, Object> tool = new HashMap<>();
            tool.put("pick_tier", bestTierLabel);
            tool.put("pick_dur_pct", pct);
            return tool;
        }

        private static void compare(Map<String, StatChange> delta, String key, int current, int previous) {
            int change = current - previous;
            if (change != 0) {
                delta.put(key, new StatChange(current, change));
            }
        }

        record StatChange(int current, int delta) {
        }

        private static int tierScore(Item item) {
            if (item == Items.NETHERITE_PICKAXE) return 5;
            if (item == Items.DIAMOND_PICKAXE) return 4;
            if (item == Items.IRON_PICKAXE) return 3;
            if (item == Items.GOLDEN_PICKAXE) return 2;
            if (item == Items.STONE_PICKAXE) return 1;
            if (item == Items.WOODEN_PICKAXE) return 0;
            return -1;
        }

        private static String tierLabel(Item item) {
            if (item == Items.NETHERITE_PICKAXE) return "NETHERITE";
            if (item == Items.DIAMOND_PICKAXE) return "DIAMOND";
            if (item == Items.IRON_PICKAXE) return "IRON";
            if (item == Items.GOLDEN_PICKAXE) return "GOLD";
            if (item == Items.STONE_PICKAXE) return "STONE";
            if (item == Items.WOODEN_PICKAXE) return "WOOD";
            return null;
        }
    }

    private static final class CamEvent {
        final CamEventType type;
        final long id;
        final long ts;
        final String priority;
        final CamPhase phase;
        final Map<String, Object> payload;
        final String suggestedMode;
        final Integer suggestedDurationSec;

        private CamEvent(CamEventType type, long id, long ts, String priority, CamPhase phase, Map<String, Object> payload,
                         String suggestedMode, Integer suggestedDurationSec) {
            this.type = type;
            this.id = id;
            this.ts = ts;
            this.priority = priority;
            this.phase = phase;
            this.payload = payload;
            this.suggestedMode = suggestedMode;
            this.suggestedDurationSec = suggestedDurationSec;
        }

        static CamEvent simple(CamEventType type, long id, long ts, CamPhase phase, Map<String, Object> payload) {
            return new CamEvent(type, id, ts, type.getDefaultPriority(), phase, payload, null, null);
        }

        static CamEvent phase(CamEventType type, long id, long ts, CamPhase phase, CamPhase eventPhase, Map<String, Object> payload) {
            return new CamEvent(type, id, ts, type.getDefaultPriority(), eventPhase, payload, null, null);
        }

        CamEvent withSuggestedMode(String mode, Integer duration) {
            if (mode == null) {
                return this;
            }
            return new CamEvent(type, id, ts, priority, phase, payload, mode, duration);
        }

        CamEvent withoutSuggestion() {
            if (suggestedMode == null) {
                return this;
            }
            return new CamEvent(type, id, ts, priority, phase, payload, null, null);
        }

        boolean hasFullInspectSuggestion() {
            return "FULL".equals(suggestedMode);
        }

        Map<String, Object> toJson() {
            Map<String, Object> root = new HashMap<>();
            root.put("id", id);
            root.put("ts", ts);
            root.put("type", type.name());
            if (priority != null) {
                root.put("priority", priority);
            }
            if (phase != null) {
                root.put("phase", phase.name());
            }
            root.put("payload", payload == null ? Map.of() : payload);
            if (suggestedMode != null) {
                root.put("suggested_mode", suggestedMode);
            }
            if (suggestedDurationSec != null) {
                root.put("suggested_duration_sec", suggestedDurationSec);
            }
            return root;
        }
    }

    private static final class CamBridgeThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "CamBridge-dispatcher");
            t.setDaemon(true);
            return t;
        }
    }
}
