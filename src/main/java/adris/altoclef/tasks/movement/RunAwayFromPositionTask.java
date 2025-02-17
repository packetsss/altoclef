package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

public class RunAwayFromPositionTask extends CustomBaritoneGoalTask {

    private final BlockPos[] dangerBlocks;
    private final double distance;
    private final Integer maintainY;

    public RunAwayFromPositionTask(double distance, BlockPos... toRunAwayFrom) {
        this(distance, null, toRunAwayFrom);
    }

    public RunAwayFromPositionTask(double distance, Integer maintainY, BlockPos... toRunAwayFrom) {
        this.distance = distance;
        this.dangerBlocks = toRunAwayFrom;
        this.maintainY = maintainY;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalRunAway(distance, maintainY, dangerBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromPositionTask task) {
            return Arrays.equals(task.dangerBlocks, dangerBlocks);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Running away from " + Arrays.toString(dangerBlocks);
    }
}
