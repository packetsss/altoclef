package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RunAwayFromHostilesTask extends CustomBaritoneGoalTask {

    private final double distanceToRun;
    private final boolean includeSkeletons;

    public RunAwayFromHostilesTask(double distance, boolean includeSkeletons) {
        distanceToRun = distance;
        this.includeSkeletons = includeSkeletons;
    }

    public RunAwayFromHostilesTask(double distance) {
        this(distance, false);
    }


    @Override
    protected Goal newGoal(AltoClef mod) {
        // We want to run away NOW
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return new GoalRunAwayFromHostiles(mod, distanceToRun);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromHostilesTask task) {
            return Math.abs(task.distanceToRun - distanceToRun) < 1;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "NIGERUNDAYOO, SUMOOKEYY! distance="+ distanceToRun +", skeletons="+ includeSkeletons;
    }

    private class GoalRunAwayFromHostiles extends GoalRunAwayFromEntities {

    private static final double WATER_COST_MULTIPLIER = 4.0;
    private static final double HEAVY_WATER_COST_MULTIPLIER = 8.0;
        private static final double LOS_BREAK_BONUS_MULTIPLIER = 4.0;
        private static final double LOS_CHECK_RANGE = 32.0;

        private final AltoClef modReference;

        public GoalRunAwayFromHostiles(AltoClef mod, double distance) {
            super(mod, distance, false, 0.8);
            this.modReference = mod;
        }

        @Override
        protected List<Entity> getEntities(AltoClef mod) {
            Stream<LivingEntity> stream = mod.getEntityTracker().getHostiles().stream();
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                if (!includeSkeletons) {
                    stream = stream.filter(hostile -> !(hostile instanceof SkeletonEntity));
                }
                return stream.collect(Collectors.toList());
            }
        }

        @Override
        protected double getCostOfEntity(Entity entity, int x, int y, int z) {
            double cost = super.getCostOfEntity(entity, x, y, z);

            BlockPos pos = new BlockPos(x, y, z);
            World world = modReference.getWorld();
            if (world == null) {
                return cost;
            }

            BlockPos surface = pos.down();
            boolean tileWater = isWater(world, pos) || isWater(world, surface);
            if (tileWater) {
                cost = Math.max(cost, 1.0) * WATER_COST_MULTIPLIER;
            }

            int waterNeighbors = countWaterNeighbors(world, surface);
            if (waterNeighbors >= 2) {
                cost = Math.max(cost, 1.0) * HEAVY_WATER_COST_MULTIPLIER;
            }

            boolean breaksLos = !LookHelper.cleanLineOfSight(entity, WorldHelper.toVec3d(pos), LOS_CHECK_RANGE);
            if (breaksLos) {
                cost *= LOS_BREAK_BONUS_MULTIPLIER;
            }

            return cost;
        }

        private boolean isWater(World world, BlockPos pos) {
            return world.getFluidState(pos).getFluid() == Fluids.WATER || world.getBlockState(pos).getBlock() == Blocks.WATER;
        }

        private int countWaterNeighbors(World world, BlockPos pos) {
            int count = 0;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos offset = pos.offset(dir);
                if (isWater(world, offset)) {
                    count++;
                }
            }
            return count;
        }
    }
}
