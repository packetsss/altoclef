package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalYLevel;

public class GetToYTask extends CustomBaritoneGoalTask {

    private final int yLevel;
    private final Dimension dimension;

    public GetToYTask(int ylevel, Dimension dimension) {
        this.yLevel = ylevel;
        this.dimension = dimension;
    }

    public GetToYTask(int ylevel) {
        this(ylevel, null);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (dimension != null && WorldHelper.getCurrentDimension() != dimension) {
            return new DefaultGoToDimensionTask(dimension);
        }
        return super.onTick(mod);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalYLevel(yLevel);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToYTask task) {
            return task.yLevel == yLevel;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to y=" + yLevel + (dimension != null ? ("in dimension" + dimension) : "");
    }
}
