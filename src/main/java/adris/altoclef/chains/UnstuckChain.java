package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetOutOfWaterTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class UnstuckChain extends SingleTaskChain {

    private final LinkedList<Vec3d> posHistory = new LinkedList<>();
    private boolean isProbablyStuck = false;
    private int eatingTicks = 0;
    private boolean interruptedEating = false;
    private TimerGame shimmyTaskTimer = new TimerGame(5);
    private boolean startedShimmying = false;
    private boolean waterTaskLogged = false;
    private final Map<String, Long> stuckLogCooldowns = new HashMap<>();

    public UnstuckChain(TaskRunner runner) {
        super(runner);
    }


    private void checkStuckInWater() {
        if (posHistory.size() < 100) return;

        ClientWorld world = AltoClef.getInstance().getWorld();
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        // is not in water
        if (!world.getBlockState(player.getSteppingPos()).getBlock().equals(Blocks.WATER)
                && !world.getBlockState(player.getSteppingPos().down()).getBlock().equals(Blocks.WATER))
            return;

        // everything should be fine
        if (player.isOnGround()) {
            posHistory.clear();
            return;
        }

        // do NOT do anything if underwater
        if (player.getAir() < player.getMaxAir()) {
            return;
        }

        Vec3d pos1 = posHistory.get(0);
        for (int i = 1; i < 100; i++) {
            Vec3d pos2 = posHistory.get(i);
            if (Math.abs(pos1.getX() - pos2.getX()) > 0.75 || Math.abs(pos1.getZ() - pos2.getZ()) > 0.75) {
                return;
            }
        }

        Vec3d pos2 = posHistory.get(99);
        double stuckSeconds = posHistory.size() / 20.0;
        double movementSq = pos1.squaredDistanceTo(pos2);
        double horizontalMovementSq = Math.pow(pos1.getX() - pos2.getX(), 2) + Math.pow(pos1.getZ() - pos2.getZ(), 2);
        double verticalMovement = Math.abs(pos1.getY() - pos2.getY());
        String dimensionName = world != null ? world.getRegistryKey().getValue().toString() : "<unknown>";
    logStuckEvent("WaterLimitedMovement", String.format(Locale.ROOT,
        "trigger=GetOutOfWaterTask duration=%.1fs movementSq=%.5f horizontalSq=%.5f vertical=%.3f start=%s end=%s dimension=%s",
                stuckSeconds,
                movementSq,
                horizontalMovementSq,
                verticalMovement,
                pos1.x + "," + pos1.y + "," + pos1.z,
                pos2.x + "," + pos2.y + "," + pos2.z,
                dimensionName));
        posHistory.clear();
    isProbablyStuck = true;
        setTask(new GetOutOfWaterTask());
    }

    private void checkStuckInPowderedSnow() {
        AltoClef mod = AltoClef.getInstance();

        PlayerEntity player = mod.getPlayer();
        ClientWorld world = mod.getWorld();

        if (PlayerVer.inPowderedSnow(player)) {
            isProbablyStuck = true;
            BlockPos destroyPos = null;

            Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(Blocks.POWDER_SNOW);
            if (nearest.isPresent()) {
                destroyPos = nearest.get();
            }

            BlockPos headPos = WorldHelper.toBlockPos(player.getEyePos()).down();
            if (world.getBlockState(headPos).getBlock() == Blocks.POWDER_SNOW) {
                destroyPos = headPos;
            } else if (world.getBlockState(player.getBlockPos()).getBlock() == Blocks.POWDER_SNOW) {
                destroyPos = player.getBlockPos();
            }

            if (destroyPos != null) {
                setTask(new DestroyBlockTask(destroyPos));
                String dimensionName = world != null ? world.getRegistryKey().getValue().toString() : "<unknown>";
        logStuckEvent("PowderSnow", String.format(Locale.ROOT,
            "trigger=DestroyBlockTask target=%s dimension=%s", destroyPos.toShortString(), dimensionName));
            }
        }
    }

    private void checkStuckOnEndPortalFrame(AltoClef mod) {
        BlockState state = mod.getWorld().getBlockState(mod.getPlayer().getSteppingPos());

        // if we are standing on an end portal frame that is NOT filled, get off otherwise we will get stuck
        if (state.getBlock() == Blocks.END_PORTAL_FRAME && !state.get(EndPortalFrameBlock.EYE)) {
            if (!mod.getFoodChain().isTryingToEat()) {
                isProbablyStuck = true;

                // for now let's just hope the other mechanisms will take care of cases where moving forward will get us in danger
                mod.getInputControls().tryPress(Input.MOVE_FORWARD);
                String dimensionName = mod.getWorld() != null ? mod.getWorld().getRegistryKey().getValue().toString() : "<unknown>";
        logStuckEvent("EndPortalFrame", String.format(Locale.ROOT,
            "action=MoveForward pos=%s dimension=%s", mod.getPlayer().getBlockPos().toShortString(), dimensionName));
            }
        }
    }

    private void checkEatingGlitch() {
        FoodChain foodChain = AltoClef.getInstance().getFoodChain();

        if (interruptedEating) {
            foodChain.shouldStop(false);
            interruptedEating = false;
        }

        if (foodChain.isTryingToEat()) {
            eatingTicks++;
        } else {
            eatingTicks = 0;
        }

        if (eatingTicks > 7*20) {
            ClientPlayerEntity player = AltoClef.getInstance().getPlayer();
            String dimensionName = AltoClef.getInstance().getWorld() != null ? AltoClef.getInstance().getWorld().getRegistryKey().getValue().toString() : "<unknown>";
            int hunger = player != null ? player.getHungerManager().getFoodLevel() : -1;
            float saturation = player != null ? player.getHungerManager().getSaturationLevel() : -1f;
            String posInfo = player != null ? player.getBlockPos().toShortString() : "<unknown>";
        logStuckEvent("EatingStall", String.format(Locale.ROOT,
            "action=ResetEat durationTicks=%d hunger=%d saturation=%.1f pos=%s dimension=%s",
                    eatingTicks,
                    hunger,
                    saturation,
                    posInfo,
                    dimensionName));
            foodChain.shouldStop(true);

            eatingTicks = 0;
            interruptedEating = true;
            isProbablyStuck = true;
        }
    }

    private void logStuckEvent(String key, String details) {
        ClientWorld world = AltoClef.getInstance().getWorld();
        long now = world != null ? world.getTime() : System.currentTimeMillis();
        long last = stuckLogCooldowns.getOrDefault(key, Long.MIN_VALUE);
        if (now - last >= 40) {
            Debug.logMessage(String.format(Locale.ROOT, "[Unstuck] %s: %s", key, details), false);
            stuckLogCooldowns.put(key, now);
        }
    }

    @Override
    public float getPriority() {
        if (mainTask instanceof GetOutOfWaterTask && mainTask.isActive()) {
            if (!waterTaskLogged) {
                Debug.logMessage("[Unstuck] Continuing GetOutOfWaterTask", false);
                waterTaskLogged = true;
            }
            return 55;
        }
        waterTaskLogged = false;

        isProbablyStuck = false;

        AltoClef mod = AltoClef.getInstance();

        if (!AltoClef.inGame() || MinecraftClient.getInstance().isPaused() || !mod.getUserTaskChain().isActive())
            return Float.NEGATIVE_INFINITY;

        if (StorageHelper.isBlastFurnaceOpen() || StorageHelper.isSmokerOpen() || StorageHelper.isChestOpen() || StorageHelper.isBigCraftingOpen()) {
            return Float.NEGATIVE_INFINITY;
        }

        PlayerEntity player = mod.getPlayer();
        posHistory.addFirst(player.getPos());
        if (posHistory.size() > 500) {
            posHistory.removeLast();
        }

        checkStuckInWater();
        checkStuckInPowderedSnow();
        checkEatingGlitch();
        checkStuckOnEndPortalFrame(mod);


        if (isProbablyStuck) {
            return 55;
        }

        if (startedShimmying && !shimmyTaskTimer.elapsed()) {
            setTask(new SafeRandomShimmyTask());
            return 55;
        }
        startedShimmying = false;

        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public String getName() {
        return "Unstuck Chain";
    }
}
