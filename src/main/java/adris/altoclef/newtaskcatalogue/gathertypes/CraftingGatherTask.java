package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.speedrun.beatgame.prioritytask.tasks.CraftItemPriorityTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

public class CraftingGatherTask extends GatherTask{

    private final RecipeTarget recipe;

    public CraftingGatherTask(ItemStack stack, RecipeTarget recipe) {
       this(stack,recipe, null);
    }

    public CraftingGatherTask(ItemStack stack, RecipeTarget recipe, List<GatherTask> children) {
        super(stack, children);
        this.recipe = recipe;
    }


    @Override
    public List<ItemStack> getNeededItems() {
        List<ItemStack> stacks = new ArrayList<>();

        for (ItemTarget target : recipe.getRecipe().getSlots()) {
            for (Item match : target.getMatches()) {
                if (match == Items.AIR) continue;

                stacks.add(new ItemStack(match));
            }
        }

        return stacks;
    }

    @Override
    public GatherType getType() {
        return GatherType.CRAFTING;
    }

    @Override
    protected boolean isSelfComplete(AltoClef mod) {
        return mod.getItemStorage().getItemCount(recipe.getOutputItem()) >= recipe.getTargetCount();
    }

    @Override
    public String toString() {
        return "craft: "+getItemStack()+" with recipe: "+getNeededItems();
    }
}
