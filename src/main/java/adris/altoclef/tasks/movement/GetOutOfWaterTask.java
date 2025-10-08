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
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;

public class GetOutOfWaterTask extends CustomBaritoneGoalTask{

    private boolean startedShimmying = false;
    private final TimerGame shimmyTaskTimer = new TimerGame(5);
    private Vec3d stallAnchorPos = null;
    private long stallAnchorTick = -1;
    private long lastStallLogTick = -1;
    private static final long STALL_DETECTION_TICKS = 12 * 20;
    private static final long STALL_LOG_COOLDOWN_TICKS = 35 * 20;
    private static final double STALL_DISTANCE_THRESHOLD_SQ = 1.25 * 1.25;

    @Override
    protected void onStart() {
        resetStallTelemetry();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        updateWaterStallTelemetry(mod);

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
        resetStallTelemetry();
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
        return !AltoClef.getInstance().getPlayer().isTouchingWater() && AltoClef.getInstance().getPlayer().isOnGround();
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
    }

    private static Map<String, Object> vectorMap(Vec3d vec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", Math.round(vec.x * 1000.0) / 1000.0);
        map.put("y", Math.round(vec.y * 1000.0) / 1000.0);
        map.put("z", Math.round(vec.z * 1000.0) / 1000.0);
        return map;
    }

    private static class EscapeFromWaterGoal implements Goal {

        private static boolean isWater(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean isWaterAdjacent(int x, int y, int z) {
            return isWater(x + 1, y, z) || isWater(x - 1, y, z) || isWater(x, y, z + 1) || isWater(x, y, z - 1)
                    || isWater(x + 1, y, z - 1) || isWater(x + 1, y, z + 1) || isWater(x - 1, y, z - 1)
                    || isWater(x - 1, y, z + 1);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return !isWater(x, y, z) && !isWaterAdjacent(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isWater(x, y, z)) {
                return 1;
            } else if (isWaterAdjacent(x, y, z)) {
                return 0.5f;
            }

            return 0;
        }
    }
}
