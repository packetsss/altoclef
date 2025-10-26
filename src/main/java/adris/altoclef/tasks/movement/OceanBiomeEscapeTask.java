package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LocateBiomeCommandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Drives the bot out of open ocean by relocating to the nearest configured land biome using locate commands
 * with a fallback biome search when commands are unavailable.
 */
public class OceanBiomeEscapeTask extends Task {

    private static final Set<RegistryKey<Biome>> BLOCKED_BIOMES = Set.of(
        BiomeKeys.WARM_OCEAN,
        BiomeKeys.OCEAN,
        BiomeKeys.DEEP_OCEAN,
        BiomeKeys.COLD_OCEAN,
        BiomeKeys.DEEP_COLD_OCEAN,
        BiomeKeys.LUKEWARM_OCEAN,
        BiomeKeys.DEEP_LUKEWARM_OCEAN,
        BiomeKeys.FROZEN_OCEAN,
        BiomeKeys.DEEP_FROZEN_OCEAN,
        BiomeKeys.ICE_SPIKES
    );

    private final List<Identifier> targetBiomes;
    private final List<LocatorState> locators = new ArrayList<>();

    private int locatorTickCursor = 0;
    private Task activeTask;
    private BlockPos currentTarget;
    private String currentTargetLabel;
    private SearchWithinBiomeTask fallbackSearchTask;
    private RegistryKey<Biome> fallbackRegistryKey;

    public OceanBiomeEscapeTask(List<Identifier> targetBiomes) {
        this.targetBiomes = new ArrayList<>(targetBiomes);
    }

    public void refreshTargets(List<Identifier> newTargets) {
        if (newTargets == null || newTargets.isEmpty()) {
            return;
        }
        List<Identifier> copy = new ArrayList<>(newTargets);
        targetBiomes.clear();
        targetBiomes.addAll(copy);

        Iterator<LocatorState> iterator = locators.iterator();
        while (iterator.hasNext()) {
            LocatorState state = iterator.next();
            if (!copy.contains(state.id)) {
                state.close();
                iterator.remove();
            }
        }
        Set<Identifier> existing = new HashSet<>();
        for (LocatorState state : locators) {
            existing.add(state.id);
        }
        for (Identifier id : copy) {
            if (!existing.contains(id)) {
                locators.add(createLocatorState(id));
            }
        }
        if (locators.isEmpty()) {
            locatorTickCursor = 0;
        } else if (locatorTickCursor >= locators.size()) {
            locatorTickCursor = 0;
        }
    }

    @Override
    protected void onStart() {
        locators.clear();
        locatorTickCursor = 0;
        activeTask = null;
        currentTarget = null;
        currentTargetLabel = null;
        fallbackSearchTask = null;
        fallbackRegistryKey = null;

        for (Identifier id : targetBiomes) {
            locators.add(createLocatorState(id));
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (!isInBlockedBiome(mod)) {
            setDebugState("Reached safe biome");
            return null;
        }
        if (locators.isEmpty()) {
            setDebugState("No escape targets configured");
            return null;
        }

        tickNextLocator();

        BlockPos bestTarget = selectBestTarget(mod);
        if (bestTarget != null) {
            ensureTravelTask(bestTarget);
            setDebugState(String.format(Locale.ROOT, "Heading to %s", currentTargetLabel != null ? currentTargetLabel : bestTarget.toShortString()));
            return activeTask;
        }

        if (!anyLocatorSupported()) {
            Task fallback = ensureFallbackSearchTask();
            if (fallback != null) {
                setDebugState("Fallback biome search");
                return fallback;
            }
        }

        ensureWanderTask();
        setDebugState("Awaiting locate result, wandering");
        return activeTask;
    }

    @Override
    protected void onStop(Task interruptTask) {
        for (LocatorState state : locators) {
            state.close();
        }
        locators.clear();
        activeTask = null;
        currentTarget = null;
        currentTargetLabel = null;
        fallbackSearchTask = null;
        fallbackRegistryKey = null;
    }

    @Override
    public boolean isFinished() {
        return !isInBlockedBiome(AltoClef.getInstance());
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof OceanBiomeEscapeTask task) {
            return new HashSet<>(task.targetBiomes).equals(new HashSet<>(targetBiomes));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Escape ocean biome";
    }

    private LocatorState createLocatorState(Identifier id) {
        LocateBiomeCommandHelper helper = new LocateBiomeCommandHelper(
            AltoClef.getInstance(),
            id.toString(),
            id.getPath(),
            Dimension.OVERWORLD,
            90,
            12
        );
    RegistryKey<Biome> key = RegistryKey.of(RegistryKeys.BIOME, id);
        String label = formatLabel(id);
        return new LocatorState(id, label, key, helper);
    }

    private void tickNextLocator() {
        if (locators.isEmpty()) {
            return;
        }
        if (locatorTickCursor >= locators.size()) {
            locatorTickCursor = 0;
        }
        LocatorState state = locators.get(locatorTickCursor);
        locatorTickCursor = (locatorTickCursor + 1) % Math.max(1, locators.size());
        state.tick();
    }

    private BlockPos selectBestTarget(AltoClef mod) {
        if (mod.getPlayer() == null) {
            return null;
        }
        Vec3d playerPos = mod.getPlayer().getPos();
        BlockPos best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        String bestLabel = null;
        for (LocatorState state : locators) {
            if (state.lastLocated == null) {
                continue;
            }
            double distanceSq = Vec3d.ofCenter(state.lastLocated).squaredDistanceTo(playerPos);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = state.lastLocated;
                bestLabel = state.label;
            }
        }
        currentTargetLabel = bestLabel;
        return best;
    }

    private void ensureTravelTask(BlockPos target) {
        if (currentTarget != null && currentTarget.equals(target) && activeTask instanceof GetWithinRangeOfBlockTask) {
            return;
        }
        currentTarget = target;
        activeTask = new GetWithinRangeOfBlockTask(target, 24);
    }

    private void ensureWanderTask() {
        if (activeTask instanceof TimeoutWanderTask) {
            return;
        }
        activeTask = new TimeoutWanderTask();
        currentTarget = null;
        currentTargetLabel = null;
    }

    private Task ensureFallbackSearchTask() {
        RegistryKey<Biome> keyToUse = null;
        for (LocatorState state : locators) {
            if (state.registryKey != null) {
                keyToUse = state.registryKey;
                break;
            }
        }
        if (keyToUse == null) {
            return null;
        }
        if (fallbackSearchTask == null || fallbackRegistryKey == null || !fallbackRegistryKey.equals(keyToUse)) {
            fallbackSearchTask = new SearchWithinBiomeTask(keyToUse);
            fallbackRegistryKey = keyToUse;
        }
        activeTask = fallbackSearchTask;
        return activeTask;
    }

    private boolean anyLocatorSupported() {
        for (LocatorState state : locators) {
            if (!state.unsupported) {
                return true;
            }
        }
        return false;
    }

    private boolean isInBlockedBiome(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) {
            return false;
        }
        RegistryEntry<Biome> entry = mod.getWorld().getBiome(mod.getPlayer().getBlockPos());
        if (entry == null) {
            return false;
        }
        if (WorldHelper.isOcean(entry)) {
            return true;
        }
        for (RegistryKey<Biome> key : BLOCKED_BIOMES) {
            if (entry.matchesKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String formatLabel(Identifier id) {
        String path = id.getPath().replace('_', ' ');
        if (path.isEmpty()) {
            return id.toString();
        }
        return Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    private static class LocatorState {
        final Identifier id;
        final String label;
        final RegistryKey<Biome> registryKey;
        final LocateBiomeCommandHelper helper;
        BlockPos lastLocated;
        boolean unsupported;

        LocatorState(Identifier id, String label, RegistryKey<Biome> registryKey, LocateBiomeCommandHelper helper) {
            this.id = id;
            this.label = label;
            this.registryKey = registryKey;
            this.helper = helper;
        }

        void tick() {
            if (helper == null || unsupported) {
                return;
            }
            helper.tick();
            if (helper.isUnsupported()) {
                unsupported = true;
                return;
            }
            Optional<BlockPos> located = helper.getLocatedPosition();
            located.ifPresent(pos -> lastLocated = pos);
        }

        void close() {
            if (helper != null) {
                helper.close();
            }
        }
    }
}
