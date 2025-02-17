package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RayTraceContext;

/**
 * Removes a liquid source block at a position.
 */
public class ClearLiquidTask extends Task {

    private final BlockPos liquidPos;

    public ClearLiquidTask(BlockPos liquidPos) {
        this.liquidPos = liquidPos;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getItemStorage().hasItem(Items.BUCKET)) {
            mod.getBehaviour().setRayTracingFluidHandling(RayTraceContext.FluidHandling.SOURCE_ONLY);
            return new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), liquidPos, false);
        }

        return new PlaceStructureBlockTask(liquidPos);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (mod.getChunkTracker().isChunkLoaded(liquidPos)) {
            return mod.getWorld().getBlockState(liquidPos).getFluidState().isEmpty();
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ClearLiquidTask task) {
            return task.liquidPos.equals(liquidPos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Clear liquid at " + liquidPos;
    }
}
