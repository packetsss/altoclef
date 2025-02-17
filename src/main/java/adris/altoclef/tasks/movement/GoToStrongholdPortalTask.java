package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;


public class GoToStrongholdPortalTask extends Task {

    private LocateStrongholdCoordinatesTask locateCoordsTask;
    private final int targetEyes;
    private final int MINIMUM_EYES = 12;
    private BlockPos strongholdCoordinates;

    public GoToStrongholdPortalTask(int targetEyes) {
        this.targetEyes = targetEyes;
        strongholdCoordinates = null;
        locateCoordsTask = new LocateStrongholdCoordinatesTask(targetEyes);
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        /*
            If we don't know where stronghold is, find out where stronghold is.
            If we do know where stronghold is, fast travel there
            If there search it
         */
        if (strongholdCoordinates == null) {
            // in case any screen is open, prevents from getting stuck
            StorageHelper.closeScreen();

            strongholdCoordinates = locateCoordsTask.getStrongholdCoordinates().orElse(null);
            if (strongholdCoordinates == null) {
                if (mod.getItemStorage().getItemCount(Items.ENDER_EYE) < MINIMUM_EYES && mod.getEntityTracker().itemDropped(Items.ENDER_EYE)) {
                    setDebugState("Picking up dropped eye");
                    return new PickupDroppedItemTask(Items.ENDER_EYE, MINIMUM_EYES);
                }
                setDebugState("Triangulating stronghold...");
                return locateCoordsTask;
            }
        }

        if (mod.getPlayer().getPos().distanceTo(WorldHelper.toVec3d(strongholdCoordinates)) < 10 && !mod.getBlockScanner().anyFound(Blocks.END_PORTAL_FRAME)) {
            mod.log("Something went wrong whilst triangulating the stronghold... either the action got disrupted or the second eye went to a different stronghold");
            mod.log("We will try to triangulate again now...");
            strongholdCoordinates = null;
            locateCoordsTask = new LocateStrongholdCoordinatesTask(targetEyes);
            return null;
        }
        // Search stone brick chunks, but while we're wandering, go to the nether
        setDebugState("Searching for Stronghold...");
        /*return new SearchChunkForBlockTask(Blocks.STONE_BRICKS) {
            @Override
            protected Task onTick(AltoClef mod) {
                if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
                    return getWanderTask(mod);
                }
                return super.onTick(mod);
            }

            @Override
            protected Task getWanderTask(AltoClef mod) {
                return new FastTravelTask(strongholdCoordinates, 300, true);
            }
        };*/
        return new FastTravelTask(strongholdCoordinates, 300, true);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GoToStrongholdPortalTask;
    }

    @Override
    protected String toDebugString() {
        return "Locating Stronghold";
    }
}