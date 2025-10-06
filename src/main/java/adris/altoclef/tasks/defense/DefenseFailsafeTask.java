package adris.altoclef.tasks.defense;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.movement.GetOutOfWaterTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * Composite task used by the mob defense chain to run a deterministic escape sequence
 * whenever the regular defense loop stalls.
 */
public class DefenseFailsafeTask extends Task {

    public enum Reason {
        TIMEOUT,
        WATER,
        SPAWNER,
        STALE_COMBAT
    }

    private enum Stage {
        PREPARE,
        ASCEND,
        RESOLVE_REASON,
        FINALISE
    }

    private final Reason reason;
    private final BlockPos spawnerHint;
    private final int targetY;

    private Task current;
    private Stage stage = Stage.PREPARE;

    public DefenseFailsafeTask(Reason reason, BlockPos spawnerHint, int targetY) {
        this.reason = reason;
        this.spawnerHint = spawnerHint;
        this.targetY = targetY;
    }

    @Override
    protected void onStart() {
        current = null;
        stage = Stage.PREPARE;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (current != null) {
            if (current.isFinished()) {
                current = null;
                advanceStage();
            } else {
                setDebugState("Failsafe -> executing " + current);
                return current;
            }
        }

        switch (stage) {
            case PREPARE -> {
                if (mod.getPlayer().isTouchingWater()) {
                    current = new GetOutOfWaterTask();
                    setDebugState("Failsafe -> exiting water");
                    return current;
                }
                advanceStage();
            }
            case ASCEND -> {
                if (!mod.getPlayer().isTouchingWater() && mod.getPlayer().getBlockY() >= targetY - 1) {
                    advanceStage();
                    break;
                }
                current = new GetToYTask(targetY);
                setDebugState("Failsafe -> climbing to high ground");
                return current;
            }
            case RESOLVE_REASON -> {
                if (reason == Reason.SPAWNER && spawnerHint != null) {
                    current = new DestroyBlockTask(spawnerHint);
                    setDebugState("Failsafe -> breaking suspected spawner");
                    return current;
                }
                if ((reason == Reason.TIMEOUT || reason == Reason.STALE_COMBAT) && shouldAttemptSleep(mod)) {
                    current = new SleepThroughNightTask();
                    setDebugState("Failsafe -> sleeping to reset spawns");
                    return current;
                }
                advanceStage();
            }
            case FINALISE -> {
                setDebugState("Failsafe complete");
                return null;
            }
        }
        return null;
    }

    private boolean shouldAttemptSleep(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            return false;
        }
        if (!WorldHelper.canSleep()) {
            return false;
        }
        return mod.getItemStorage().hasItem(ItemHelper.BED) || mod.getItemStorage().hasItem(Items.WHITE_BED);
    }

    private void advanceStage() {
        stage = switch (stage) {
            case PREPARE -> Stage.ASCEND;
            case ASCEND -> Stage.RESOLVE_REASON;
            case RESOLVE_REASON -> Stage.FINALISE;
            case FINALISE -> Stage.FINALISE;
        };
    }

    @Override
    protected void onStop(Task interruptTask) {
        current = null;
    }

    @Override
    public boolean isFinished() {
        return stage == Stage.FINALISE && current == null;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefenseFailsafeTask task) {
            return task.reason == reason && equalsOrNull(task.spawnerHint, spawnerHint);
        }
        return false;
    }

    private boolean equalsOrNull(BlockPos a, BlockPos b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @Override
    protected String toDebugString() {
        return "Defense failsafe (" + reason + ")";
    }

    public Reason getReason() {
        return reason;
    }

    public String getStageName() {
        return stage.name();
    }
}
