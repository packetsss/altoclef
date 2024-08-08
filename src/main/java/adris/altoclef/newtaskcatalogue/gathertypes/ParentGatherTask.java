package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import net.minecraft.item.ItemStack;

import java.util.List;

public class ParentGatherTask extends GatherTask{


    public ParentGatherTask(ItemStack stack) {
        super(stack, null);
    }

    public ParentGatherTask(ItemStack stack, List<GatherTask> children) {
        super(stack, children);
    }

    @Override
    public List<ItemStack> getNeededItems() {
        return List.of();
    }

    @Override
    public GatherType getType() {
        return GatherType.PARENT;
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
