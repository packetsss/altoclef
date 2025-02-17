package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.Arrays;

public class CarveThenCollectTask extends ResourceTask {

    private final ItemTarget target;
    private final Block[] targetBlocks;
    private final ItemTarget toCarve;
    private final Block[] toCarveBlocks;
    private final ItemTarget carveWith;

    public CarveThenCollectTask(ItemTarget target, Block[] targetBlocks, ItemTarget toCarve, Block[] toCarveBlocks, ItemTarget carveWith) {
        super(target);
        this.target = target;
        this.targetBlocks = targetBlocks;
        this.toCarve = toCarve;
        this.toCarveBlocks = toCarveBlocks;
        this.carveWith = carveWith;
    }

    public CarveThenCollectTask(Item target, int targetCount, Block targetBlock, Item toCarve, Block toCarveBlock, Item carveWith) {
        this(new ItemTarget(target, targetCount), new Block[]{targetBlock}, new ItemTarget(toCarve, targetCount), new Block[]{toCarveBlock}, new ItemTarget(carveWith, 1));
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // If target block spotted, break it!
        // If toCarve block spotted, carve it!
        // neededCarve = (neededTarget - currentTarget)
        // If neededCarve > currentCarveItems:
        //      collect carve items!
        // ELSE:
        //      Place carved items down

        // If our target block is placed, break it!
        if (mod.getBlockScanner().anyFound(targetBlocks)) {
            setDebugState("Breaking carved/target block");
            return new DoToClosestBlockTask(DestroyBlockTask::new, targetBlocks);
        }
        // Collect our "carve with" item (can be shears, axe, whatever)
        if (!StorageHelper.itemTargetsMetInventory(mod, carveWith)) {
            setDebugState("Collect our carve tool");
            return TaskCatalogue.getItemTask(carveWith);
        }
        // If our carve block is spotted, carve it.
        if (mod.getBlockScanner().anyFound(toCarveBlocks)) {
            setDebugState("Carving block");
            return new DoToClosestBlockTask(blockPos -> new InteractWithBlockTask(carveWith, blockPos, false), toCarveBlocks);
        }
        // Collect carve blocks if we don't have enough, or place them down if we do.
        int neededCarveItems = target.getTargetCount() - mod.getItemStorage().getItemCount(target);
        int currentCarveItems = mod.getItemStorage().getItemCount(toCarve);
        if (neededCarveItems > currentCarveItems) {
            setDebugState("Collecting more blocks to carve");
            return TaskCatalogue.getItemTask(toCarve);
        } else {
            setDebugState("Placing blocks to carve down");
            return new PlaceBlockNearbyTask(toCarveBlocks);
        }
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CarveThenCollectTask task) {
            return (task.target.equals(target) && task.toCarve.equals(toCarve) && Arrays.equals(task.targetBlocks, targetBlocks) && Arrays.equals(task.toCarveBlocks, toCarveBlocks));
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Getting after carving: " + target;
    }
}
