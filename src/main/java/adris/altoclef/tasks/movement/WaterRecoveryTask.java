package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Escalated recovery sequence for stubborn water stalls.
 * <p>
 * Phase one performs a quick hazard purge by filling nearby water pockets with throwaway blocks.
 * Phase two drives a short series of manual "impulse" bursts toward a nearby dry landing spot.
 */
public class WaterRecoveryTask extends Task {

    private enum Phase {
        HAZARD_PURGE,
        IMPULSE_CRAWL
    }

    private static final int MAX_HAZARD_SWEEPS = 4;
    private static final int HAZARD_SCAN_INTERVAL_TICKS = 10;
    private static final int REQUIRED_DRY_TICKS = 20;
    private static final int IMPULSE_SEARCH_RADIUS = 6;
    private static final int IMPULSE_MAX_ATTEMPTS = 6;
    private static final int IMPULSE_BURST_TICKS = 15;
    private static final int IMPULSE_COOLDOWN_TICKS = 8;

    private final Deque<BlockPos> hazardQueue = new ArrayDeque<>();
    private final Set<BlockPos> processedHazards = new HashSet<>();

    private Task activeHazardTask;
    private Phase phase = Phase.HAZARD_PURGE;
    private int hazardSweepsPerformed = 0;
    private long nextHazardScanTick = -1;
    private boolean behaviourPushed = false;
    private int dryTicks = 0;

    private Vec3d impulseDirection;
    private int impulseTicksRemaining = 0;
    private int impulseCooldownTicks = 0;
    private int impulseAttempts = 0;

    @Override
    protected void onStart() {
        hazardQueue.clear();
        processedHazards.clear();
        activeHazardTask = null;
        hazardSweepsPerformed = 0;
        nextHazardScanTick = -1;
        phase = Phase.HAZARD_PURGE;
        dryTicks = 0;
        impulseDirection = null;
        impulseTicksRemaining = 0;
        impulseCooldownTicks = 0;
        impulseAttempts = 0;

        AltoClef mod = AltoClef.getInstance();
        if (mod.getBehaviour() != null) {
            mod.getBehaviour().push();
            behaviourPushed = true;
            mod.getBehaviour().setBlockPlacePenalty(0);
            mod.getBehaviour().setBlockBreakAdditionalPenalty(0);
            mod.getBehaviour().setAllowWalkThroughFlowingWater(false);
        }
        mod.getClientBaritone().getExploreProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        updateDryTicks(mod);

        if (phase == Phase.HAZARD_PURGE) {
            Task hazardTask = runHazardPurge(mod);
            if (hazardTask != null) {
                setDebugState("Purging nearby water hazards");
                return hazardTask;
            }
            phase = Phase.IMPULSE_CRAWL;
            impulseDirection = null;
            impulseTicksRemaining = 0;
        }

        setDebugState("Impulse crawl toward dry ground");
        runImpulseBurst(mod);
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.JUMP);
        if (behaviourPushed && mod.getBehaviour() != null) {
            mod.getBehaviour().pop();
        }
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getPlayer() == null) {
            return true;
        }
        if (!mod.getPlayer().isTouchingWater() && dryTicks >= REQUIRED_DRY_TICKS) {
            return true;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof WaterRecoveryTask;
    }

    @Override
    protected String toDebugString() {
        return phase == Phase.HAZARD_PURGE ? "Water hazard purge" : "Impulse crawl";
    }

    private void updateDryTicks(AltoClef mod) {
        if (mod.getPlayer() != null && mod.getPlayer().isOnGround() && !mod.getPlayer().isTouchingWater()) {
            dryTicks++;
        } else {
            dryTicks = 0;
        }
    }

    private Task runHazardPurge(AltoClef mod) {
        if (activeHazardTask != null) {
            if (!activeHazardTask.isFinished()) {
                return activeHazardTask;
            }
            activeHazardTask = null;
        }

        long currentTick = WorldHelper.getTicks();
        if (hazardQueue.isEmpty() && (nextHazardScanTick == -1 || currentTick >= nextHazardScanTick)) {
            populateHazardQueue(mod);
            nextHazardScanTick = currentTick + HAZARD_SCAN_INTERVAL_TICKS;
        }

        while (!hazardQueue.isEmpty()) {
            BlockPos target = hazardQueue.removeFirst();
            if (!processedHazards.add(target)) {
                continue;
            }
            if (!isHazardStillValid(mod, target)) {
                continue;
            }
            activeHazardTask = new PlaceBlockTask(target, new net.minecraft.block.Block[]{Blocks.COBBLESTONE}, true, true);
            return activeHazardTask;
        }

        if (hazardSweepsPerformed < MAX_HAZARD_SWEEPS) {
            hazardSweepsPerformed++;
            hazardQueue.clear();
            processedHazards.clear();
            nextHazardScanTick = -1;
            return null;
        }

        return null;
    }

    private void populateHazardQueue(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) {
            return;
        }
        ClientWorld world = mod.getWorld();
        BlockPos feet = mod.getPlayer().getSteppingPos();
        BlockPos playerBlock = mod.getPlayer().getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = feet.offset(direction);
            if (!candidate.equals(playerBlock) && isWaterHazard(world, candidate)) {
                hazardQueue.add(candidate);
            }
            BlockPos below = candidate.down();
            if (!below.equals(playerBlock) && isWaterHazard(world, below)) {
                hazardQueue.add(below);
            }
        }
        BlockPos directlyBelow = feet.down();
        if (!directlyBelow.equals(playerBlock) && isWaterHazard(world, directlyBelow)) {
            hazardQueue.add(directlyBelow);
        }
        BlockPos forward = feet.offset(mod.getPlayer().getMovementDirection());
        if (!forward.equals(playerBlock) && isWaterHazard(world, forward)) {
            hazardQueue.add(forward);
        }
    }

    private boolean isWaterHazard(ClientWorld world, BlockPos pos) {
        if (pos == null || world == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        FluidState fluid = world.getFluidState(pos);
        if (state.getBlock() == Blocks.BUBBLE_COLUMN) {
            return true;
        }
        return fluid.isIn(FluidTags.WATER);
    }

    private boolean isHazardStillValid(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null) {
            return false;
        }
        FluidState fluid = mod.getWorld().getFluidState(pos);
        return fluid.isIn(FluidTags.WATER) || mod.getWorld().getBlockState(pos).getBlock() == Blocks.BUBBLE_COLUMN;
    }

    private void runImpulseBurst(AltoClef mod) {
        if (mod.getPlayer() == null) {
            return;
        }
        if (impulseDirection == null && impulseCooldownTicks <= 0) {
            Optional<Vec3d> heading = findImpulseHeading(mod);
            if (heading.isPresent()) {
                impulseDirection = heading.get();
                impulseTicksRemaining = IMPULSE_BURST_TICKS;
                impulseCooldownTicks = IMPULSE_COOLDOWN_TICKS;
                impulseAttempts++;
                Vec3d lookTarget = mod.getPlayer().getPos().add(impulseDirection.multiply(2.0));
                LookHelper.lookAt(mod, lookTarget, false);
            } else {
                impulseAttempts = IMPULSE_MAX_ATTEMPTS;
            }
        }

        if (impulseDirection != null && impulseTicksRemaining > 0) {
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            mod.getInputControls().hold(Input.SPRINT);
            mod.getInputControls().hold(Input.JUMP);
            impulseTicksRemaining--;
            if (impulseTicksRemaining == 0) {
                impulseDirection = null;
            }
        } else {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.SPRINT);
            mod.getInputControls().release(Input.JUMP);
            if (impulseCooldownTicks > 0) {
                impulseCooldownTicks--;
            }
        }

        if (impulseAttempts >= IMPULSE_MAX_ATTEMPTS) {
            impulseDirection = null;
            impulseTicksRemaining = 0;
            impulseCooldownTicks = 0;
        }
    }

    private Optional<Vec3d> findImpulseHeading(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            return Optional.empty();
        }
        ClientWorld world = mod.getWorld();
        BlockPos origin = mod.getPlayer().getBlockPos();
        Vec3d playerPos = mod.getPlayer().getPos();
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3d bestVector = null;

        for (int dx = -IMPULSE_SEARCH_RADIUS; dx <= IMPULSE_SEARCH_RADIUS; dx++) {
            for (int dz = -IMPULSE_SEARCH_RADIUS; dz <= IMPULSE_SEARCH_RADIUS; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!isCandidateDryLanding(world, candidate)) {
                        continue;
                    }
                    Vec3d center = WorldHelper.toVec3d(candidate);
                    Vec3d vector = center.subtract(playerPos);
                    double horizontal = Math.hypot(vector.x, vector.z);
                    if (horizontal < 1.5 || horizontal > IMPULSE_SEARCH_RADIUS + 1) {
                        continue;
                    }
                    double verticalBonus = Math.max(0.0, vector.y) * 0.35;
                    double score = -horizontal + verticalBonus;
                    if (score > bestScore) {
                        bestScore = score;
                        bestVector = vector.normalize();
                    }
                }
            }
        }

        return Optional.ofNullable(bestVector);
    }

    private boolean isCandidateDryLanding(ClientWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!WorldHelper.canReach(pos)) {
            return false;
        }
        BlockPos head = pos.up();
        BlockState floorState = world.getBlockState(pos);
        BlockState headState = world.getBlockState(head);
        if (!floorState.isSolidBlock(world, pos)) {
            return false;
        }
        if (!headState.isAir() && headState.getCollisionShape(world, head).isEmpty()) {
            // Non-air blocks without collision (e.g. plants) are fine as long as not waterlogged.
        } else if (!headState.isAir()) {
            return false;
        }
        FluidState headFluid = world.getFluidState(head);
        return !headFluid.isIn(FluidTags.WATER);
    }
}
