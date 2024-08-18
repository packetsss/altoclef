package adris.altoclef.newtaskcatalogue;

import adris.altoclef.AltoClef;
import adris.altoclef.newtaskcatalogue.dataparser.DataParser;
import adris.altoclef.newtaskcatalogue.dataparser.ItemDrop;
import adris.altoclef.newtaskcatalogue.gathertypes.CraftingGatherTask;
import adris.altoclef.newtaskcatalogue.gathertypes.GatherTask;
import adris.altoclef.newtaskcatalogue.gathertypes.MiningGatherTask;
import adris.altoclef.newtaskcatalogue.gathertypes.ParentGatherTask;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.CraftingHelper;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.*;

public class NewTaskCatalogue {

    private static HashMap<Item, List<Block>> dropsToBlocks = new HashMap<>();


    public static void printGetItem(AltoClef mod, Item item) {
        DataParser.parseBlockData(mod);
        dropsToBlocks = DataParser.getDropToBlocks();

        mod.log("Found: " + mod.getCraftingRecipeTracker().getRecipesForItem(item).size() + " crafting recipe(s):");
        mod.log(mod.getCraftingRecipeTracker().getRecipesForItem(item));

        if (CraftingHelper.canCraftItemNow(mod, item)) {
            mod.log("Can craft right now, wahoo! ");
            mod.log(" > GO CRAFT");
            return;
        }


        mod.log("Now build gather tree...");
        ParentGatherTask parent = new ParentGatherTask(new ItemStack(item, 1), new ItemStack(Items.CRAFTING_TABLE));

        growGatherTree(mod, new ItemStack(item, 1), parent, new HashSet<>());

        System.out.println("\n" + print(parent, ""));

        mod.log("//TODO get entity drops");

        parent.computeChildTaskCombo(mod);
        parent.setChildrenToComputed();

        System.out.println();
        System.out.println();
        System.out.println();

        System.out.println("\n" + print(parent, ""));
    }


    private static void growGatherTree(AltoClef mod, ItemStack needed, GatherTask gatherTask, HashSet<Item> alreadyMet) {
        System.out.println("CALLED FUNCTION "+gatherTask.hashCode());

        alreadyMet.add(needed.getItem());

        List<GatherTask> added = new ArrayList<>();

        // add crafting recipes
        for (RecipeTarget target : mod.getCraftingRecipeTracker().getRecipeTargets(needed.getItem(), needed.getCount())) {
            int neededCrafts = Math.ceilDiv(needed.getCount(), target.getRecipe().outputCount());

            added.add(new CraftingGatherTask(new ItemStack(needed.getItem(), neededCrafts*target.getRecipe().outputCount()), target, neededCrafts));
        }

        if (!dropsToBlocks.containsKey(needed.getItem())) {
            // mod.logWarning("Item not in blocks! "+needed.getItem());
        } else {
            // add blocks to mine
            for (Block block : dropsToBlocks.get(needed.getItem())) {

                float chance = 1;
                for (ItemDrop drop : DataParser.getBlockDropMap().get(block)) {
                    if (drop.item() != needed.getItem()) continue;

                    chance = Math.min(chance, drop.dropChance());
                }

                added.add(new MiningGatherTask(block, (int) Math.ceil(needed.getCount() / chance), needed, null));
            }
        }

        // todo add entities to kill


        HashSet<Item> calledFor = new HashSet<>();
        for (GatherTask child : added) {
            HashMap<Item, Integer> neededItemsMap = new HashMap<>();

            for (ItemStack stack : child.getNeededItems()) {
                neededItemsMap.put(stack.getItem(),neededItemsMap.getOrDefault(stack.getItem(),0)+stack.getCount());
            }
            boolean shouldAdd = neededItemsMap.isEmpty();

            System.out.println("CHILD LOOP START: "+child.hashCode());
            for (Map.Entry<Item,Integer> entry : neededItemsMap.entrySet()) {
                ItemStack stack = new ItemStack(entry.getKey(),entry.getValue());

                if (stack.getItem() == Items.AIR || stack.getCount() <= 0) continue;
                if (calledFor.contains(stack.getItem())) continue;
                if (alreadyMet.contains(stack.getItem())) continue;

                calledFor.add(stack.getItem());

                System.out.println("getting "+stack + " ; "+child.hashCode());

                // todo maybe make this non-recursive
                shouldAdd = true;
                growGatherTree(mod, stack, child, new HashSet<>(alreadyMet));
            }
            if (shouldAdd) {
                gatherTask.addChild(child);
            }
            System.out.println("CHILD LOOP END: "+child.hashCode());
        }
    }

    private static String print(GatherTask task, String prefix) {
        String str = "";
        str += prefix + "|-- " + task.toString() +"\n";
        for (GatherTask child : task.getChildren()) {

            str += print(child, prefix + "  ");
        }

        return str;
    }



}
