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
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.tag.FluidTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class UnstuckChain extends SingleTaskChain {

    private final LinkedList<Vec3d> posHistory = new LinkedList<>();
    private boolean isProbablyStuck = false;
    private int eatingTicks = 0;
    private boolean interruptedEating = false;
    private TimerGame shimmyTaskTimer = new TimerGame(5);
    private boolean startedShimmying = false;
    private boolean waterTaskLogged = false;
    private final Map<String, Long> stuckLogCooldowns = new HashMap<>();

    private static final int IDLE_SAMPLE_SIZE = 220;
    private static final double IDLE_DISPLACEMENT_THRESHOLD = 0.6;
    private static final double IDLE_TOTAL_PATH_THRESHOLD = 3.5;
    private static final long IDLE_LOG_COOLDOWN_TICKS = 400;

    private static final int OSC_SAMPLE_SIZE = 180;
    private static final double OSC_MAX_DISTANCE = 3.5;
    private static final int OSC_SWITCH_THRESHOLD = 45;
    private static final long OSC_LOG_COOLDOWN_TICKS = 400;
    private static final double OSC_DOMINANT_FRACTION = 0.85;

    public UnstuckChain(TaskRunner runner) {
        super(runner);
    }


    private void triggerShimmy() {
        startedShimmying = true;
        shimmyTaskTimer.reset();
        setTask(new SafeRandomShimmyTask());
        isProbablyStuck = true;
    }


    private void checkStuckInWater() {
        if (posHistory.size() < 100) return;

        ClientWorld world = AltoClef.getInstance().getWorld();
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        BlockPos feet = player.getSteppingPos();
        BlockState feetState = world.getBlockState(feet);
        FluidState feetFluid = feetState.getFluidState();
        boolean waterAtFeet = feetState.getBlock().equals(Blocks.WATER) || feetFluid.isIn(FluidTags.WATER);
        boolean waterBelow = world.getFluidState(feet.down()).isIn(FluidTags.WATER) || world.getBlockState(feet.down()).getBlock().equals(Blocks.WATER);
        boolean waterAtHead = world.getFluidState(feet.up()).isIn(FluidTags.WATER) || world.getBlockState(feet.up()).getFluidState().isIn(FluidTags.WATER);
        boolean touchingWater = player.isTouchingWater() || waterAtFeet || waterBelow;

        if (!touchingWater) {
            return;
        }

        if (player.getAir() < player.getMaxAir()) {
            return;
        }

        Vec3d velocity = player.getVelocity();
        double horizontalSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;

        if (player.isOnGround() && !waterAtFeet && horizontalSpeedSq > 0.01) {
            posHistory.clear();
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trigger", "GetOutOfWaterTask");
        payload.put("duration_sec", round(stuckSeconds, 2));
        payload.put("movement_sq", round(movementSq, 5));
        payload.put("horizontal_sq", round(horizontalMovementSq, 5));
        payload.put("vertical_delta", round(verticalMovement, 3));
        payload.put("start_pos", vectorMap(pos1));
        payload.put("end_pos", vectorMap(pos2));
        payload.put("dimension", dimensionName);
        payload.put("on_ground", player.isOnGround());
        payload.put("touching_water", player.isTouchingWater());
        payload.put("head_in_water", waterAtHead);
        payload.put("horizontal_speed", round(Math.sqrt(horizontalSpeedSq), 4));
        payload.put("velocity", vectorMap(velocity));
        payload.put("ground_block", describeBlock(world.getBlockState(feet)));
        Direction facing = player.getHorizontalFacing();
        BlockPos frontPos = feet.offset(facing);
        payload.put("front_block", describeBlock(world.getBlockState(frontPos)));
        payload.put("front_block_pos", blockPosMap(frontPos));
        List<Map<String, Object>> nearbyWater = collectNearbyWater(world, feet);
        if (!nearbyWater.isEmpty()) {
            payload.put("nearby_water", nearbyWater);
        }
        logStuckEvent("WaterLimitedMovement", 200, payload);
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
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("trigger", "DestroyBlockTask");
                payload.put("target", destroyPos.toShortString());
                payload.put("dimension", dimensionName);
                logStuckEvent("PowderSnow", 200, payload);
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
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("action", "MoveForward");
                payload.put("pos", mod.getPlayer().getBlockPos().toShortString());
                payload.put("dimension", dimensionName);
                logStuckEvent("EndPortalFrame", 100, payload);
            }
        }
    }

    private void checkIdleStall(AltoClef mod) {
        if (posHistory.size() < IDLE_SAMPLE_SIZE) {
            return;
        }
        Vec3d newest = posHistory.get(0);
        Vec3d oldest = posHistory.get(IDLE_SAMPLE_SIZE - 1);
        double netDisplacement = newest.distanceTo(oldest);
        double totalPath = 0.0;
        double maxStepSpeed = 0.0;
        for (int i = 0; i < IDLE_SAMPLE_SIZE - 1; i++) {
            Vec3d current = posHistory.get(i);
            Vec3d next = posHistory.get(i + 1);
            double step = current.distanceTo(next);
            totalPath += step;
            maxStepSpeed = Math.max(maxStepSpeed, step * 20.0);
        }
        if (netDisplacement < IDLE_DISPLACEMENT_THRESHOLD && totalPath < IDLE_TOTAL_PATH_THRESHOLD) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("window_seconds", round(IDLE_SAMPLE_SIZE / 20.0, 2));
            payload.put("total_path", round(totalPath, 3));
            payload.put("net_displacement", round(netDisplacement, 3));
            payload.put("max_step_speed", round(maxStepSpeed, 3));
            payload.put("current_task", mod.getUserTaskChain() != null && mod.getUserTaskChain().getCurrentTask() != null
                    ? mod.getUserTaskChain().getCurrentTask().toString()
                    : "<none>");
            payload.put("runner_status", mod.getTaskRunner().statusReport.trim());
            payload.put("pos", vectorMap(newest));
            payload.put("dimension", mod.getWorld() != null ? mod.getWorld().getRegistryKey().getValue().toString() : "<unknown>");
            payload.put("sample_count", IDLE_SAMPLE_SIZE);
            if (logStuckEvent("IdleStall", IDLE_LOG_COOLDOWN_TICKS, payload)) {
                triggerShimmy();
            }
        }
    }

    private void checkOscillation(AltoClef mod) {
        if (posHistory.size() < OSC_SAMPLE_SIZE) {
            return;
        }

        Map<HorizontalPos, ColumnStats> columns = new LinkedHashMap<>();
        HorizontalPos previous = null;
        int switches = 0;
        double horizontalPath = 0.0;
        for (int i = 0; i < OSC_SAMPLE_SIZE; i++) {
            Vec3d sample = posHistory.get(i);
            BlockPos blockPos = BlockPos.ofFloored(sample);
            HorizontalPos key = new HorizontalPos(blockPos.getX(), blockPos.getZ());
            ColumnStats stats = columns.computeIfAbsent(key, ColumnStats::new);
            stats.recordSample(sample.y);
            if (previous != null && !previous.equals(key)) {
                switches++;
            }
            if (i < OSC_SAMPLE_SIZE - 1) {
                horizontalPath += horizontalDistance(sample, posHistory.get(i + 1));
            }
            previous = key;
        }

        if (columns.size() < 2 || switches < OSC_SWITCH_THRESHOLD) {
            return;
        }

        List<ColumnStats> sorted = new ArrayList<>(columns.values());
    sorted.sort(Comparator.comparingInt(ColumnStats::count).reversed());

    ColumnStats first = sorted.get(0);
    ColumnStats second = sorted.get(1);
    int dominantSamples = first.count + second.count;
        if (dominantSamples < OSC_SAMPLE_SIZE * OSC_DOMINANT_FRACTION) {
            return;
        }

        double distance = Math.sqrt(squaredHorizontalDistance(first.position, second.position));
        if (distance > OSC_MAX_DISTANCE) {
            return;
        }

        List<Map<String, Object>> positionDetails = new ArrayList<>();
        for (int i = 0; i < Math.min(sorted.size(), 5); i++) {
            ColumnStats stats = sorted.get(i);
            Map<String, Object> posInfo = new LinkedHashMap<>();
            posInfo.put("pos", horizontalPosMap(stats.position));
            posInfo.put("samples", stats.count);
            posInfo.put("dominant", i < 2);
            posInfo.put("avg_y", round(stats.averageY(), 3));
            posInfo.put("min_y", round(stats.minY, 3));
            posInfo.put("max_y", round(stats.maxY, 3));
            positionDetails.add(posInfo);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("window_seconds", round(OSC_SAMPLE_SIZE / 20.0, 2));
        payload.put("switches", switches);
        payload.put("unique_positions", columns.size());
        payload.put("dominant_fraction", round(dominantSamples / (double) OSC_SAMPLE_SIZE, 3));
        payload.put("positions", positionDetails);
        payload.put("distance_between", round(distance, 3));
        payload.put("total_horizontal_path", round(horizontalPath, 3));
        payload.put("current_task", mod.getUserTaskChain() != null && mod.getUserTaskChain().getCurrentTask() != null
                ? mod.getUserTaskChain().getCurrentTask().toString()
                : "<none>");
        payload.put("runner_status", mod.getTaskRunner().statusReport.trim());
        payload.put("dimension", mod.getWorld() != null ? mod.getWorld().getRegistryKey().getValue().toString() : "<unknown>");
        if (logStuckEvent("Oscillation", OSC_LOG_COOLDOWN_TICKS, payload)) {
            triggerShimmy();
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
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "ResetEat");
            payload.put("duration_ticks", eatingTicks);
            payload.put("hunger", hunger);
            payload.put("saturation", round(saturation, 2));
            payload.put("pos", posInfo);
            payload.put("dimension", dimensionName);
            logStuckEvent("EatingStall", 200, payload);
            foodChain.shouldStop(true);

            eatingTicks = 0;
            interruptedEating = true;
            isProbablyStuck = true;
        }
    }

    private boolean logStuckEvent(String key, long cooldownTicks, Map<String, Object> details) {
        AltoClef mod = AltoClef.getInstance();
        ClientWorld world = mod.getWorld();
        long now = world != null ? world.getTime() : WorldHelper.getTicks();
        long last = stuckLogCooldowns.getOrDefault(key, Long.MIN_VALUE);
        if (now - last < cooldownTicks) {
            return false;
        }
        stuckLogCooldowns.put(key, now);
        String detailLine = details.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        Debug.logMessage(String.format(Locale.ROOT, "[Unstuck] %s: %s", key, detailLine), false);
        if (mod.getStuckLogManager() != null) {
            mod.getStuckLogManager().recordEvent(key, details);
        }
        return true;
    }

    private boolean logStuckEvent(String key, Map<String, Object> details) {
        return logStuckEvent(key, 40, details);
    }

    private static Map<String, Object> vectorMap(Vec3d vec) {
        if (vec == null) {
            return Map.of("x", 0.0, "y", 0.0, "z", 0.0);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", round(vec.x, 3));
        map.put("y", round(vec.y, 3));
        map.put("z", round(vec.z, 3));
        return map;
    }

    private static Map<String, Object> blockPosMap(BlockPos pos) {
        if (pos == null) {
            return Map.of("x", 0, "y", 0, "z", 0);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", pos.getX());
        map.put("y", pos.getY());
        map.put("z", pos.getZ());
        return map;
    }

    private static double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double squaredHorizontalDistance(HorizontalPos a, HorizontalPos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static Map<String, Object> horizontalPosMap(HorizontalPos pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", pos.x());
        map.put("z", pos.z());
        return map;
    }

    private static List<Map<String, Object>> collectNearbyWater(ClientWorld world, BlockPos center) {
        List<Map<String, Object>> samples = new ArrayList<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    FluidState fluid = world.getFluidState(mutable);
                    if (fluid.isIn(FluidTags.WATER)) {
                        samples.add(blockPosMap(mutable.toImmutable()));
                        if (samples.size() >= 8) {
                            return samples;
                        }
                    }
                }
            }
        }
        return samples;
    }

    private static String describeBlock(BlockState state) {
        if (state == null) {
            return "<null>";
        }
        return Registries.BLOCK.getId(state.getBlock()).toString();
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
        checkIdleStall(mod);
        checkOscillation(mod);


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

    private static record HorizontalPos(int x, int z) {
    }

    private static final class ColumnStats {
        private final HorizontalPos position;
        private int count;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double sumY = 0.0;

        private ColumnStats(HorizontalPos position) {
            this.position = position;
        }

        private void recordSample(double y) {
            count++;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            sumY += y;
        }

        private double averageY() {
            return count == 0 ? 0.0 : sumY / count;
        }

        private int count() {
            return count;
        }
    }
}
