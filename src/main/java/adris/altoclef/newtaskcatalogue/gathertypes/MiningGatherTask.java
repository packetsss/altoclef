package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;

public class MiningGatherTask extends GatherTask {


    private final Block toMine;
    private final ItemStack stack;

    public MiningGatherTask(Block toMine, ItemStack stack, List<GatherTask> children) {
        super(stack,children);
        this.toMine = toMine;
        this.stack = stack;
    }

    @Override
    public List<ItemStack> getNeededItems() {
        MiningRequirement requirement = MiningRequirement.getMinimumRequirementForBlock(toMine);
        if (requirement == MiningRequirement.HAND) return List.of();

        return List.of( new ItemStack(requirement.getMinimumPickaxe(),1));
    }

    @Override
    public GatherType getType() {
        return GatherType.MINING;
    }

    @Override
    protected boolean isSelfComplete(AltoClef mod) {
        return mod.getItemStorage().getItemCount(stack.getItem()) >= stack.getCount();
    }

    public Block getToMine() {
        return toMine;
    }

    @Override
    public String toString() {
        return "mine "+toMine+" for: "+stack +" needs: "+getNeededItems();
    }
}
