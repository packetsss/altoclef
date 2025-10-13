package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GetOutOfWaterTask extends CustomBaritoneGoalTask{

    private static final long WATER_AVOIDANCE_DURATION_TICKS = 45 * 20;
    private static final int WATER_HORIZONTAL_RADIUS = 2;
    private static final int WATER_VERTICAL_RADIUS = 2;
    private static final int WATER_REGION_MERGE_RADIUS_SQ = 9;
    private static final List<WaterAvoidRegion> ACTIVE_WATER_AVOID_REGIONS = new ArrayList<>();
    private static boolean waterAvoidancePredicateRegistered = false;

    private static final java.util.function.Predicate<BlockPos> WATER_AVOIDANCE_PREDICATE = GetOutOfWaterTask::shouldAvoidWaterNode;

    private boolean startedShimmying = false;
    private final TimerGame shimmyTaskTimer = new TimerGame(5);
    private Vec3d stallAnchorPos = null;
    private long stallAnchorTick = -1;
    private long lastStallLogTick = -1;
    private static final long STALL_DETECTION_TICKS = 12 * 20;
    private static final long STALL_LOG_COOLDOWN_TICKS = 35 * 20;
    private static final double STALL_DISTANCE_THRESHOLD_SQ = 1.25 * 1.25;
    private int dryTicks = 0;
    private static final int REQUIRED_DRY_TICKS = 20;
    private BlockPos lastKnownWaterPos = null;
    private boolean avoidanceRegionRegisteredForCurrentStuck = false;

    @Override
    protected void onStart() {
        super.onStart();
        ensureWaterAvoidancePredicateInstalled();
        resetStallTelemetry();
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() != null && mod.getPlayer().isTouchingWater()) {
            lastKnownWaterPos = mod.getPlayer().getBlockPos();
        } else {
            lastKnownWaterPos = null;
        }
        avoidanceRegionRegisteredForCurrentStuck = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        updateWaterStallTelemetry(mod);
        updateDrynessCounter(mod);

        if (mod.getPlayer() != null) {
            if (mod.getPlayer().isTouchingWater()) {
                lastKnownWaterPos = mod.getPlayer().getBlockPos();
                avoidanceRegionRegisteredForCurrentStuck = false;
            } else if (!avoidanceRegionRegisteredForCurrentStuck && dryTicks > 0 && lastKnownWaterPos != null) {
                registerWaterAvoidanceRegion(mod, lastKnownWaterPos);
                avoidanceRegionRegisteredForCurrentStuck = true;
            }
        }

        // get on the surface first
        if (mod.getPlayer().getAir() < mod.getPlayer().getMaxAir() || mod.getPlayer().isSubmergedInWater()) {
            return super.onTick();
        }

        boolean footInWater = mod.getWorld().getBlockState(mod.getPlayer().getSteppingPos()).getBlock() == Blocks.WATER;

        boolean hasBlockBelow = false;
        for (int i = 0; i < 3; i++) {
            if (mod.getWorld().getBlockState(mod.getPlayer().getSteppingPos().down(i)).getBlock() != Blocks.WATER) {
                hasBlockBelow = true;
            }
        }
        boolean hasAirAbove = mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().up(2)).getBlock().equals(Blocks.AIR);

        if (footInWater && hasAirAbove && hasBlockBelow && StorageHelper.getNumberOfThrowawayBlocks(mod) > 0) {
            mod.getInputControls().tryPress(Input.JUMP);
            if (mod.getPlayer().isOnGround()) {

                if (!startedShimmying) {
                    startedShimmying = true;
                    shimmyTaskTimer.reset();
                    Debug.logMessage("[GetOutOfWater] Starting shimmy", false);
                }
                return new SafeRandomShimmyTask();
            }

            mod.getSlotHandler().forceEquipItem(mod.getClientBaritoneSettings().acceptableThrowawayItems.value.toArray(new Item[0]));
            LookHelper.lookAt(mod, mod.getPlayer().getSteppingPos().down());
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            Debug.logMessage("[GetOutOfWater] Placing block beneath player", false);
        } else if (footInWater && hasAirAbove && hasBlockBelow) {
            Debug.logMessage("[GetOutOfWater] No throwaway blocks available for pillar", false);
        }

        return super.onTick();
    }

    @Override
    protected void onStop(Task interruptTask) {
        super.onStop(interruptTask);
        resetStallTelemetry();
        lastKnownWaterPos = null;
        avoidanceRegionRegisteredForCurrentStuck = false;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new EscapeFromWaterGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return false;
    }

    @Override
    protected String toDebugString() {
        return "";
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null || mod.getWorld() == null) return true;

        if (!mod.getPlayer().isOnGround()) {
            return false;
        }

        if (mod.getPlayer().isTouchingWater()) {
            return false;
        }

        if (dryTicks >= REQUIRED_DRY_TICKS) {
            Debug.logInternal("[GetOutOfWater] Finishing after sustained dryness.");
            return true;
        }

        return false;
    }

    private void updateWaterStallTelemetry(AltoClef mod) {
        if (mod.getStuckLogManager() == null) {
            stallAnchorPos = null;
            stallAnchorTick = -1;
            return;
        }
        if (mod.getPlayer() == null) {
            stallAnchorPos = null;
            stallAnchorTick = -1;
            return;
        }
        if (!mod.getPlayer().isTouchingWater()) {
            stallAnchorPos = null;
            stallAnchorTick = -1;
            return;
        }

        long currentTick = WorldHelper.getTicks();
        Vec3d currentPos = mod.getPlayer().getPos();

        if (stallAnchorPos == null || currentPos.squaredDistanceTo(stallAnchorPos) > STALL_DISTANCE_THRESHOLD_SQ) {
            stallAnchorPos = currentPos;
            stallAnchorTick = currentTick;
            return;
        }

        long elapsed = currentTick - stallAnchorTick;
        if (elapsed >= STALL_DETECTION_TICKS && (lastStallLogTick == -1 || currentTick - lastStallLogTick >= STALL_LOG_COOLDOWN_TICKS)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("elapsed_ticks", elapsed);
            payload.put("elapsed_seconds", Math.round((elapsed / 20.0) * 100.0) / 100.0);
            payload.put("anchor_pos", vectorMap(stallAnchorPos));
            payload.put("current_pos", vectorMap(currentPos));
            payload.put("on_ground", mod.getPlayer().isOnGround());
            payload.put("throwaway_blocks", StorageHelper.getNumberOfThrowawayBlocks(mod));
            payload.put("started_shimmy", startedShimmying);
            mod.getStuckLogManager().recordEvent("GetOutOfWaterStall", payload);
            lastStallLogTick = currentTick;
            stallAnchorPos = currentPos;
            stallAnchorTick = currentTick;
        }
    }

    private void resetStallTelemetry() {
        stallAnchorPos = null;
        stallAnchorTick = -1;
        lastStallLogTick = -1;
        dryTicks = 0;
    }

    private static Map<String, Object> vectorMap(Vec3d vec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", Math.round(vec.x * 1000.0) / 1000.0);
        map.put("y", Math.round(vec.y * 1000.0) / 1000.0);
        map.put("z", Math.round(vec.z * 1000.0) / 1000.0);
        return map;
    }

    private void updateDrynessCounter(AltoClef mod) {
        if (mod.getPlayer() == null) {
            dryTicks = 0;
            return;
        }

        if (!mod.getPlayer().isTouchingWater() && mod.getPlayer().isOnGround()) {
            dryTicks++;
        } else {
            dryTicks = 0;
        }
    }

    private static void ensureWaterAvoidancePredicateInstalled() {
        if (waterAvoidancePredicateRegistered) {
            return;
        }
        AltoClef mod = AltoClef.getInstance();
        if (mod.getBehaviour() != null) {
            mod.getBehaviour().avoidWalkingThrough(WATER_AVOIDANCE_PREDICATE);
            waterAvoidancePredicateRegistered = true;
        }
    }

    private static boolean shouldAvoidWaterNode(BlockPos pos) {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getWorld() == null) {
            return false;
        }
        if (pos.getY() >= mod.getWorld().getSeaLevel()) {
            return false;
        }
        long currentTick = WorldHelper.getTicks();
        Iterator<WaterAvoidRegion> iterator = ACTIVE_WATER_AVOID_REGIONS.iterator();
        while (iterator.hasNext()) {
            WaterAvoidRegion region = iterator.next();
            if (region.isExpired(currentTick)) {
                iterator.remove();
                continue;
            }
            if (region.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private void registerWaterAvoidanceRegion(AltoClef mod, BlockPos anchor) {
        if (mod.getWorld() == null || anchor == null) {
            return;
        }
        ClientWorld world = mod.getWorld();
        if (anchor.getY() >= world.getSeaLevel()) {
            return;
        }
        Set<BlockPos> blocked = collectWaterMarginBlocks(world, anchor, WATER_HORIZONTAL_RADIUS, WATER_VERTICAL_RADIUS);
        if (blocked.isEmpty()) {
            return;
        }
        long expireTick = WorldHelper.getTicks() + WATER_AVOIDANCE_DURATION_TICKS;
        boolean merged = false;
        for (WaterAvoidRegion region : ACTIVE_WATER_AVOID_REGIONS) {
            if (region.isNearby(anchor, WATER_REGION_MERGE_RADIUS_SQ)) {
                region.merge(blocked, expireTick);
                merged = true;
                break;
            }
        }
        if (!merged) {
            ACTIVE_WATER_AVOID_REGIONS.add(new WaterAvoidRegion(anchor.toImmutable(), blocked, expireTick, world.getSeaLevel()));
        }
    }

    private static Set<BlockPos> collectWaterMarginBlocks(ClientWorld world, BlockPos anchor, int horizontalRadius, int verticalRadius) {
        Set<BlockPos> result = new HashSet<>();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    BlockPos candidate = anchor.add(dx, dy, dz);
                    if (candidate.getY() >= world.getSeaLevel()) {
                        continue;
                    }
                    if (isWaterOrTouchesWater(world, candidate)) {
                        result.add(candidate.toImmutable());
                    }
                }
            }
        }
        return result;
    }

    private static boolean isWaterOrTouchesWater(ClientWorld world, BlockPos pos) {
        if (world.getFluidState(pos).isIn(FluidTags.WATER)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (world.getFluidState(pos.offset(direction)).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static class WaterAvoidRegion {
        private final BlockPos anchor;
        private final Set<BlockPos> blockedPositions;
        private final int seaLevel;
        private long expireTick;

        private WaterAvoidRegion(BlockPos anchor, Set<BlockPos> blockedPositions, long expireTick, int seaLevel) {
            this.anchor = anchor;
            this.blockedPositions = new HashSet<>(blockedPositions);
            this.expireTick = expireTick;
            this.seaLevel = seaLevel;
        }

        private boolean isExpired(long currentTick) {
            return currentTick > expireTick;
        }

        private boolean contains(BlockPos pos) {
            return pos.getY() < seaLevel && blockedPositions.contains(pos);
        }

        private boolean isNearby(BlockPos other, int radiusSq) {
            return anchor.getSquaredDistance(other) <= radiusSq;
        }

        private void merge(Set<BlockPos> additionalPositions, long newExpireTick) {
            blockedPositions.addAll(additionalPositions);
            expireTick = Math.max(expireTick, newExpireTick);
        }
    }

    private static class EscapeFromWaterGoal implements Goal {

        private static boolean isWater(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return true;
            BlockPos pos = new BlockPos(x, y, z);
            return !MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(pos))
                    && !MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(pos.up()));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isWater(x, y, z)) {
                return 1;
            }

            return 0;
        }
    }
}
