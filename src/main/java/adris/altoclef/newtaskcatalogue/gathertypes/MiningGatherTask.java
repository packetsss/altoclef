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
    private final int mineCount;
    private final ItemStack stack;

    public MiningGatherTask(Block toMine,int mineCount, ItemStack stack, List<GatherTask> children) {
        super(stack,children);
        this.toMine = toMine;
        this.mineCount = mineCount;
        this.stack = stack;
    }

    // FIXME this can *technically* fail if the amount of blocks we want to mine is greater than the durability of the pickaxe
    // FIXME this should rather be parsed from the JSON than hardcoding a pickaxe (some blocks need shears, silk touch etc...)
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
    protected double getSelfWeight(AltoClef mod) {
        if (!mod.getBlockScanner().anyFound(toMine)) {
            return Double.POSITIVE_INFINITY;
        }

        return mod.getBlockScanner().distanceToClosest(toMine)*mineCount;
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
        return "mine "+mineCount +" "+toMine.getName().getString()+" for: "+stack +" needs: "+getNeededItems();
    }
}
