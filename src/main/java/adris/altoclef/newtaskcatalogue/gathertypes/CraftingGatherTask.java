package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

public class CraftingGatherTask extends GatherTask {

    public RecipeTarget recipe;
    public int multiplier;

    public CraftingGatherTask(ItemStack stack, RecipeTarget recipe, int multiplier) {
        this(stack, recipe, null, multiplier);

    }

    public CraftingGatherTask(ItemStack stack, RecipeTarget recipe, List<GatherTask> children, int multiplier) {
        super(stack, children);
        this.recipe = recipe;
        this.multiplier = multiplier;
    }


    @Override
    public List<ItemStack> getNeededItems() {
        List<ItemStack> stacks = new ArrayList<>();

        for (ItemTarget target : recipe.getRecipe().getSlots()) {
            for (Item match : target.getMatches()) {
                if (match == Items.AIR) continue;

                stacks.add(new ItemStack(match, multiplier));
            }
        }

        return stacks;
    }

    @Override
    public void update() {

        multiplier = Math.ceilDiv(getItemStack().getCount(),multiplier);

    }

    @Override
    public GatherType getType() {
        return GatherType.CRAFTING;
    }

    @Override
    protected double getSelfWeight(AltoClef mod) {
        return 0;
    }

    @Override
    protected boolean isSelfComplete(AltoClef mod) {
        return mod.getItemStorage().getItemCount(recipe.getOutputItem()) >= recipe.getTargetCount();
    }

    @Override
    public String toString() {
        return "craft: " + getItemStack() + " with recipe: " + getNeededItems();
    }
}
