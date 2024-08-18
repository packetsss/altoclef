package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import net.minecraft.item.ItemStack;

import java.util.List;

public class ParentGatherTask extends GatherTask{


    private final ItemStack[] toGather;
    public ParentGatherTask(ItemStack... stacks) {
        super(ItemStack.EMPTY, null);
        if (stacks.length == 0) {
            throw new IllegalStateException("must need at least one stack!");
        }

        toGather = stacks;
    }



    @Override
    public List<ItemStack> getNeededItems() {
        return List.of(toGather);
    }

    @Override
    public GatherType getType() {
        return GatherType.PARENT;
    }

    @Override
    protected double getSelfWeight(AltoClef mod) {
        return 0;
    }

    @Override
    protected boolean isSelfComplete(AltoClef mod) {
        return true;
    }

    @Override
    public String toString() {
        return "PARENT, getting: "+getItemStack();
    }
}
