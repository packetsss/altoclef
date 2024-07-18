package adris.altoclef.multiversion;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;

public class CraftingRecipeVer {


    @Pattern
    private static ItemStack getOutput(CraftingRecipe craftingRecipe) {
        //#if MC >= 11904
        //$$ return craftingRecipe.getOutput(null);
        //#else
        return craftingRecipe.getOutput();
        //#endif
    }

}
