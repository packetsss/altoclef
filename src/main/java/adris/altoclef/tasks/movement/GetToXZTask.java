package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.util.math.BlockPos;

public class GetToXZTask extends CustomBaritoneGoalTask {

    private final int x, z;
    private final Dimension dimension;
    private int wanderAttempts;

    public GetToXZTask(int x, int z) {
        this(x, z, null);
    }

    public GetToXZTask(int x, int z, Dimension dimension) {
        this.x = x;
        this.z = z;
        this.dimension = dimension;
    }

    @Override
    protected void onStart() {
        configureProgressChecker();
        wanderAttempts = 0;
        super.onStart();
    }

    private void configureProgressChecker() {
        AltoClef mod = AltoClef.getInstance();
        double timeoutSeconds = 12.0;
        double minDistance = 0.25;
        double mineTimeout = 3.0;
        double mineProgress = 0.002;
        int attempts = 3;

        if (mod != null && mod.getPlayer() != null) {
            double dx = mod.getPlayer().getX() - x;
            double dz = mod.getPlayer().getZ() - z;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

            if (horizontalDistance > 16.0) {
                timeoutSeconds = clamp(horizontalDistance / 2.5, 15.0, 45.0);
                minDistance = clamp(horizontalDistance / 160.0, 0.2, 1.25);
                mineTimeout = clamp(timeoutSeconds / 3.5, 4.0, 12.0);
                mineProgress = 0.0025;
                attempts = horizontalDistance > 256.0 ? 6 : horizontalDistance > 160.0 ? 5 : 4;
            }
        }

        checker = new MovementProgressChecker(timeoutSeconds, minDistance, mineTimeout, mineProgress, attempts);
    }

    @Override
    protected Task onTick() {
        if (dimension != null && WorldHelper.getCurrentDimension() != dimension) {
            return new DefaultGoToDimensionTask(dimension);
        }
        return super.onTick();
    }

    @Override
    protected void onWander(AltoClef mod) {
        wanderAttempts++;
        if (wanderAttempts >= 4) {
            // Force Baritone to rebuild a fresh path after repeated wander loops.
            cachedGoal = null;
            checker.reset();
            wanderAttempts = 0;
        }
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalXZ(x, z);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToXZTask task) {
            return task.x == x && task.z == z && task.dimension == dimension;
        }
        return false;
    }

    @Override
    public boolean isFinished() {
        BlockPos cur = AltoClef.getInstance().getPlayer().getBlockPos();
        return (cur.getX() == x && cur.getZ() == z && (dimension == null || dimension == WorldHelper.getCurrentDimension()));
    }

    @Override
    protected String toDebugString() {
        return "Getting to (" + x + "," + z + ")" + (dimension != null ? " in dimension " + dimension : "");
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
