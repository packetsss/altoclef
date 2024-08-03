package adris.altoclef;

import adris.altoclef.util.helpers.CraftingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTables;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;

public class NewTaskCatalogue {


    public static void printGetItem(AltoClef mod, Item item) {
        mod.log("Found: "+mod.getCraftingRecipeTracker().getRecipeForItem(item).size()+" crafting recipe(s):");
        mod.log(mod.getCraftingRecipeTracker().getRecipeForItem(item));

        if (CraftingHelper.canCraftItemNow(mod, item)) {
            mod.log("Can craft right now, wahoo! ");
            mod.log(" > GO CRAFT");
            return;
        }

        mod.log("Now we should try to search through block drops I guess?");
    }



}
