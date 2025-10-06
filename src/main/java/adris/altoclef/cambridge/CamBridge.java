package adris.altoclef.cambridge;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasks.speedrun.beatgame.BeatMinecraftTask;
import adris.altoclef.tasksystem.Task;
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
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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

    private final AltoClef mod;
    private final ObjectWriter writer;
    private final ExecutorService dispatcher;
    private final Deque<CamEvent> history = new ArrayDeque<>(RING_CAPACITY);
    private final List<CamEvent> pendingSend = new ArrayList<>();
    private final Queue<CamEvent> deferredBigEvents = new ConcurrentLinkedQueue<>();
    private final Map<HazardType, HazardState> hazardStates = new EnumMap<>(HazardType.class);
    private final MilestoneAccumulator milestoneAccumulator = new MilestoneAccumulator();
    private final AtomicLong nextEventId = new AtomicLong(1L);

    private CamBridgeTransport transport;
    private boolean enabled;
    private long lastBigEventMs = Long.MIN_VALUE;
    private long lastFullInspectMs = Long.MIN_VALUE;
    private long lastHeartbeatMs = Long.MIN_VALUE;
    private CamPhase currentPhase;
    private Task currentTask;
    private ResourceSnapshot lastResources;
    private Dimension lastDimension;
    private boolean stallActive;
    private Vec3d lastProgressPos;
    private long lastProgressMs;
    private long lastTransportErrorLogMs;

    public CamBridge(AltoClef mod) {
        this.mod = mod;
        this.writer = new ObjectMapper().writer();
        this.dispatcher = Executors.newSingleThreadExecutor(new CamBridgeThreadFactory());
        for (HazardType type : HazardType.values()) {
            hazardStates.put(type, new HazardState());
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
                case "udp" -> newTransport = new UdpCamBridgeTransport(settings.getCamBridgeHost(), settings.getCamBridgePort());
                case "file" -> newTransport = new FileCamBridgeTransport(Path.of(settings.getCamBridgeFilePath()));
                default -> {
                    Debug.logWarning("Unknown CamBridge transport: " + transportMode + ". Falling back to UDP.");
                    newTransport = new UdpCamBridgeTransport(settings.getCamBridgeHost(), settings.getCamBridgePort());
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

    private void resetState() {
        currentTask = null;
        currentPhase = null;
        lastResources = null;
        lastDimension = null;
        stallActive = false;
        milestoneAccumulator.reset();
        hazardStates.values().forEach(HazardState::resetImmediate);
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
        Map<String, Integer> delta = snapshot.diff(lastResources);
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

    private void emitHeartbeatIfDue(ResourceSnapshot snapshot, long now) {
        if (now - lastHeartbeatMs < HEARTBEAT_INTERVAL_MS) {
            return;
        }
        lastHeartbeatMs = now;
        Map<String, Object> payload = new HashMap<>();
        payload.put("phase", currentPhase != null ? currentPhase.name() : "UNKNOWN");
        payload.put("brief", snapshot.brief());
        CamEvent hb = CamEvent.simple(CamEventType.HEARTBEAT, nextId(), now, currentPhase, payload);
        emitEvent(hb, now);
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

    private enum HazardType {
        COMBAT,
        FIRE,
        LAVA,
        FALLING,
        DROWNING,
        LOW_HP
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
        private final Map<String, Integer> values = new HashMap<>();
        private final Set<String> crossed = new HashSet<>();
        private boolean active;
        private long firstChangeMs;
        private long lastChangeMs;

        void record(Map<String, Integer> delta, Collection<String> thresholds, long now) {
            if (delta.isEmpty()) {
                return;
            }
            if (!active) {
                active = true;
                firstChangeMs = now;
            }
            lastChangeMs = now;
            values.putAll(delta);
            crossed.addAll(thresholds);
        }

        Optional<Map<String, Object>> flushIfReady(long now) {
            if (!active) {
                return Optional.empty();
            }
            if ((now - lastChangeMs) < MILESTONE_INACTIVITY_FLUSH_MS && (now - firstChangeMs) < MILESTONE_MAX_WINDOW_MS) {
                return Optional.empty();
            }
            Map<String, Object> payload = new HashMap<>();
            payload.putAll(values);
            if (!crossed.isEmpty()) {
                payload.put("crossed", new ArrayList<>(crossed));
            }
            reset();
            return Optional.of(payload);
        }

        void reset() {
            active = false;
            values.clear();
            crossed.clear();
            firstChangeMs = 0L;
            lastChangeMs = 0L;
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

        Map<String, Integer> diff(ResourceSnapshot previous) {
            Map<String, Integer> delta = new HashMap<>();
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
                delta.put("tools", 1);
            }
            return delta;
        }

        Collection<String> crossedThresholds(ResourceSnapshot previous) {
            List<String> hit = new ArrayList<>();
            BeatMinecraftConfig config = BeatMinecraftTask.getConfig();
            if (previous.pearls < config.targetEyes && pearls >= config.targetEyes) {
                hit.add("pearls_target");
            }
            if (previous.rods < 6 && rods >= 6) {
                hit.add("rods_min");
            }
            if (previous.eyes < config.minimumEyes && eyes >= config.minimumEyes) {
                hit.add("eyes_min");
            }
            if (previous.beds < config.requiredBeds && beds >= config.requiredBeds) {
                hit.add("beds_target");
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

        private static void compare(Map<String, Integer> delta, String key, int current, int previous) {
            if (current != previous) {
                delta.put(key, current);
            }
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
