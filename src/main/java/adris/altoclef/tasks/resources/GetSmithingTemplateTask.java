package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.multiversion.versionedfields.Items;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LocateStructureCommandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class GetSmithingTemplateTask extends ResourceTask {

    private final Task _searcher = new SearchChunkForBlockTask(Blocks.BLACKSTONE);
    private final int _count;
    private BlockPos _chestloc = null;
    private LocateStructureCommandHelper bastionLocator;

    public GetSmithingTemplateTask(int count) {
        super(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, count);
        _count = count;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        bastionLocator = new LocateStructureCommandHelper(mod,
            "minecraft:bastion_remnant",
            "bastion",
            Dimension.NETHER,
            45,
            10);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        if (bastionLocator != null) {
            bastionLocator.tick();
        }
        Optional<BlockPos> locatedBastion = bastionLocator != null ? bastionLocator.getLocatedPosition() : Optional.empty();
        //if (_bastionloc != null && !mod.getChunkTracker().isChunkLoaded(_bastionloc)) {
        //    Debug.logMessage("Bastion at " + _bastionloc + " too far away. Re-searching.");
        //    _bastionloc = null;
        // }
        if (_chestloc == null) {
            for (BlockPos pos : mod.getBlockScanner().getKnownLocations(Blocks.CHEST)) {
                if (WorldHelper.isInteractableBlock(pos)) {
                    _chestloc = pos;
                    break;
                }
            }
            if (_chestloc == null) {
                if (locatedBastion.isPresent()) {
                    BlockPos target = locatedBastion.get();
                    setDebugState("Heading to located bastion at " + target.toShortString());
                    return new GetToXZTask(target.getX(), target.getZ(), Dimension.NETHER);
                }
                if (bastionLocator != null && !bastionLocator.isUnsupported()) {
                    setDebugState("Locating bastion (waiting on /locate)...");
                    return new TimeoutWanderTask();
                }
            }
        }
        if (_chestloc != null) {
            //if (!_chestloc.isWithinDistance(mod.getPlayer().getPos(), 150)) {
            setDebugState("Destroying Chest"); // TODO: Make It check the chest instead of destroying it
            if (WorldHelper.isInteractableBlock(_chestloc)) {
                return new DestroyBlockTask(_chestloc);
            } else {
                _chestloc = null;
                for (BlockPos pos : mod.getBlockScanner().getKnownLocations(Blocks.CHEST)) {
                    if (WorldHelper.isInteractableBlock(pos)) {
                        _chestloc = pos;
                        break;
                    }
                }
            }
            //}
        }
        setDebugState("Searching for/Traveling around bastion");
        return _searcher;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

        if (bastionLocator != null) {
            bastionLocator.close();
            bastionLocator = null;
        }
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof GetSmithingTemplateTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " smithing templates";
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
