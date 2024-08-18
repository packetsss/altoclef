package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.List;

public class KillingGatherTask extends GatherTask{

    public KillingGatherTask(Class<? extends LivingEntity> clazz, Item desiredItem, List<GatherTask> children) {
        super(null,children);
    }


    @Override
    public List<ItemStack> getNeededItems() {
        return List.of();
    }

    @Override
    public GatherType getType() {
        return null;
    }

    @Override
    protected double getSelfWeight(AltoClef mod) {
        return 0;
    }

    @Override
    protected boolean isSelfComplete(AltoClef mod) {
        return false;
    }
}
