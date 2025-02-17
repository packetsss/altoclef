package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.util.math.BlockPos;

public class GetToBlockTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    private final BlockPos position;
    private final boolean preferStairs;
    private final Dimension dimension;
    private int finishedTicks = 0;
    private final TimerGame wanderTimer = new TimerGame(2);

    public GetToBlockTask(BlockPos position, boolean preferStairs) {
        this(position, preferStairs, null);
    }

    public GetToBlockTask(BlockPos position, Dimension dimension) {
        this(position, false, dimension);
    }

    public GetToBlockTask(BlockPos position, boolean preferStairs, Dimension dimension) {
        this.dimension = dimension;
        this.position = position;
        this.preferStairs = preferStairs;
    }

    public GetToBlockTask(BlockPos position) {
        this(position, false);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (dimension != null && WorldHelper.getCurrentDimension() != dimension) {
            return new DefaultGoToDimensionTask(dimension);
        }

        if (isFinished(mod)) {
            finishedTicks++;
        } else {
            finishedTicks = 0;
        }
        if (finishedTicks > 10*20) {
            wanderTimer.reset();
            mod.logWarning("GetToBlock was finished for 10 seconds yet is still being called, wandering");
            finishedTicks = 0;
            return new TimeoutWanderTask();
        }
        if (!wanderTimer.elapsed()) {
            return new TimeoutWanderTask();
        }

        return super.onTick(mod);
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        if (preferStairs) {
            mod.getBehaviour().push();
            mod.getBehaviour().setPreferredStairs(true);
        }
    }


    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        if (preferStairs) {
            mod.getBehaviour().pop();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToBlockTask task) {
            return task.position.equals(position) && task.preferStairs == preferStairs && task.dimension == dimension;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) && (dimension == null || dimension == WorldHelper.getCurrentDimension());
    }

    @Override
    protected String toDebugString() {
        return "Getting to block " + position + (dimension != null ? " in dimension " + dimension : "");
    }


    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalBlock(position);
    }

    @Override
    protected void onWander(AltoClef mod) {
        super.onWander(mod);
        mod.getBlockScanner().requestBlockUnreachable(position);
    }
}
