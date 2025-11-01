package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.util.math.BlockPos;

import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;

/**
 * Temporarily routes the bot away from its current target toward mirrored coordinates.
 */
public class OppositeAxisDiversionTask extends CustomBaritoneGoalTask {

    private final BlockPos destination;
    private final double finishRadiusSq;
    private final Dimension dimension;

    public OppositeAxisDiversionTask(BlockPos destination, double finishRadius) {
        super();
        this.destination = destination;
        this.finishRadiusSq = finishRadius * finishRadius;
        this.dimension = Dimension.OVERWORLD;
    }

    public OppositeAxisDiversionTask(BlockPos destination) {
        this(destination, 4.0);
    }

    @Override
    protected Task onTick() {
        if (dimension != null && WorldHelper.getCurrentDimension() != dimension) {
            return new DefaultGoToDimensionTask(dimension);
        }
        return super.onTick();
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalBlock(destination);
    }

    @Override
    public boolean isFinished() {
        if (AltoClef.getInstance().getPlayer() == null) {
            return true;
        }
        if (dimension != null && WorldHelper.getCurrentDimension() != dimension) {
            return true;
        }
        BlockPos playerPos = AltoClef.getInstance().getPlayer().getBlockPos();
        double dx = playerPos.getX() - destination.getX();
        double dz = playerPos.getZ() - destination.getZ();
        if (dx * dx + dz * dz <= finishRadiusSq) {
            return true;
        }
        return super.isFinished();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof OppositeAxisDiversionTask task) {
            return task.destination.equals(destination);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Opposite-axis diversion toward " + destination.toShortString();
    }

    public BlockPos getDestination() {
        return destination;
    }
}
