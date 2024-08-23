package adris.altoclef.newtaskcatalogue;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.ToolMaterialVer;
import adris.altoclef.newtaskcatalogue.dataparser.DataParser;
import adris.altoclef.newtaskcatalogue.dataparser.ItemDrop;
import adris.altoclef.newtaskcatalogue.gathertypes.*;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.CraftingHelper;
import adris.altoclef.util.helpers.StorageHelper;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.block.Block;
import net.minecraft.item.*;

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

        System.out.println();

        List<GatherTask> leafs = new ArrayList<>();

        getTreeLeafs(parent, leafs);
        System.out.println(leafs);


        // now that we choose the best task, search through others if they need the same item, if yes gather it
        // todo there should also be a check for tools (probably dont want to gather stack of stone with wooden pickaxe)

       FlattenedTasks flattenedTasks = new FlattenedTasks();


        System.out.println();
        getFlattenedItems(parent, flattenedTasks);
        System.out.println(flattenedTasks);


        List<PickaxeItem> toolItems = new ArrayList<>();
        for (Item i : flattenedTasks.itemToTask.keySet()) {
            if (!(i instanceof PickaxeItem toolItem)) continue;

            toolItems.add(toolItem);
        }
        toolItems.sort(Comparator.comparingInt(ToolMaterialVer::getMiningLevel));


        // todo update counts of needed resources after tools are pruned
        for (int i = 0; i < toolItems.size()-1; i++) {
            PickaxeItem pick = toolItems.get(i);

            System.out.println("SETTING: "+pick + " to " +flattenedTasks.singleItemTaskMap.get(pick) );
            flattenedTasks.set(pick, flattenedTasks.singleItemTaskMap.get(pick));


        }

        // check how many of the item we actually need and how much will be gathered later with a better tool
       /* HashMap<Item, Integer> map = new HashMap<>();

        // update for items already in inventory etc...
            for (GatherTask task : flattenedTasks.itemToTask.values()) {
                task.update();
            }

        System.out.println(flattenedTasks);
        System.out.println();
        System.out.println();
        System.out.println();
        for (GatherTask task : flattenedTasks.itemToTask.values()) {
            // this will be handled later
            if (task.getType() != GatherType.CRAFTING) continue;

            // this is stupid, sometimes we craft multiple items from one block
            for (ItemStack stack : task.getNeededItems()) {
                //ItemStack stack = task.getItemStack();
                Item i = stack.getItem();
                int count = Math.ceilDiv(stack.getCount(), ((CraftingGatherTask)task).recipe.getRecipe().outputCount());

                if (map.containsKey(i)) {
                    map.put(i, map.get(i) + count);
                } else {
                    map.put(i, count - mod.getItemStorage().getItemCount(i));
                }
            }
        }
        System.out.println("-----------");
        System.out.println(map);
        System.out.println("-----------");*/
        // todo do da pruning

        List<GatherTask> doableTasks = new ArrayList<>();
        boolean canCraft = false;

        for (GatherTask task : flattenedTasks.itemToTask.values()) {
            if (!task.canBeDone(mod)) continue;

            doableTasks.add(task);

            if (task.getType() == GatherType.CRAFTING) {
                canCraft = true;
            }
        }

        System.out.println();
        System.out.println();
        System.out.println(flattenedTasks);
        System.out.println();
        System.out.println();
        System.out.println(doableTasks);

        if (doableTasks.isEmpty()) {
            mod.logWarning("NO TASK IS POSSIBLE TO DO");
            return;
        }

        // prefer to craft items
        if (canCraft) {
            mod.log("Crafting is available, go craft!");
            doableTasks.removeIf((task) -> task.getType() != GatherType.CRAFTING);
            mod.log(doableTasks);
            return;
        }



        GatherTask best = doableTasks.getFirst();
        double bestWeight = Double.POSITIVE_INFINITY;

        for (GatherTask task : doableTasks) {
            double weight = task.getWeight(mod);
            if (weight < bestWeight) {
                bestWeight = weight;
                best = task;
            }
        }

        mod.log("Doing: "+best);
        //dang, this is pretty much it... I guess (not at all really lol)

        // list for pickaxe progression, might refactor this later
      /*  List<PickaxeItem> progressions = List.of((PickaxeItem) Items.WOODEN_PICKAXE,(PickaxeItem) Items.STONE_PICKAXE,
                (PickaxeItem) Items.IRON_PICKAXE,(PickaxeItem) Items.DIAMOND_PICKAXE,(PickaxeItem) Items.NETHERITE_PICKAXE);

        List<GatherTask> finalTodo = new ArrayList<>();
        for (PickaxeItem i : toolItems) {
            GatherTask task = flattenedTasks.singleItemTaskMap.get(i);

            finalTodo.add(task);

            for (ItemStack stack : task.getNeededItems()) {
                GatherTask test = flattenedTasks.itemToTask.get(stack.getItem());

                if (test.getType() == GatherType.MINING) {
                    MiningGatherTask miningTask = (MiningGatherTask) test;

                    miningTask.mineCount -= stack.getCount();
                } else {
                    System.out.println("[WARN] different task: "+test);
                }
            }
        }

        System.out.println("----");
        System.out.println(finalTodo);
        System.out.println();
        System.out.println(flattenedTasks.toString());*/


        // todo assign blocks to these tools, then you can continue in building the tree
        // for this a system in GatherTask that allows to dynamically changes its size and also copy should be added
        // then you can assign blocks to each pickaxe, subtract them from flattened resources and further continue in building the task tree

       /* for (Map.Entry<Item, Integer> entry : map.entrySet()) {
            if (entry.getKey() instanceof ToolItem tool) {
                int durability = tool.getMaterial().getDurability();

            }
        }*/


    }


    private static int getCombinedBlockCount(List<GatherTask> list) {
        int count = 0;

        for (GatherTask task : list) {
            if (!(task instanceof MiningGatherTask miningTask)) {
                throw new IllegalStateException("not a mining task!");
            }

            count += miningTask.mineCount;
        }

        return count;
    }

    private static void getFlattenedItems(GatherTask parent, FlattenedTasks flattened) {
        for (GatherTask child : parent.getChildren()) {

            flattened.addTask(child, parent);

            getFlattenedItems(child, flattened);
        }
    }

    private static class FlattenedTasks {
        HashMap<GatherTask, List<GatherTask>> map = new HashMap<>();

        HashMap<Item, GatherTask> itemToTask = new HashMap<>();

        HashMap<Item, GatherTask> singleItemTaskMap = new HashMap<>();

        public boolean containsItem(Item item) {

            return itemToTask.containsKey(item);
        }

        public List<GatherTask> getParents(Item item) {
            return map.get(itemToTask.get(item));
        }

        public void set(Item item, GatherTask task) {
            GatherTask prev = itemToTask.get(item);
            map.remove(prev);

            itemToTask.put(item, task);
            map.put(task, map.getOrDefault(task, new ArrayList<>()));
        }

        public void addTask(GatherTask task, GatherTask parent) {
            ItemStack stack = task.getItemStack();

            GatherTask currentReference = itemToTask.get(stack.getItem());

            GatherTask newTask;
            List<GatherTask> parentList;

            if (currentReference == null) {
                newTask = task;
                parentList = new ArrayList<>();

                singleItemTaskMap.put(stack.getItem(), task);
            } else {
                if (!currentReference.getClass().equals(task.getClass())) {
                    throw new IllegalStateException("not the same class!");
                }

                ItemStack newStack = new ItemStack(stack.getItem(), stack.getCount()+currentReference.getItemStack().getCount());
                if (task instanceof CraftingGatherTask craftingTask) {
                    CraftingGatherTask currentCraftingReference = (CraftingGatherTask) currentReference;

                    newTask = new CraftingGatherTask(newStack
                            ,new RecipeTarget(stack.getItem(),craftingTask.recipe.getTargetCount()+currentCraftingReference.recipe.getTargetCount(), craftingTask.recipe.getRecipe()),
                            craftingTask.multiplier+currentCraftingReference.multiplier);
                } else if (task instanceof MiningGatherTask miningTask) {
                    MiningGatherTask currentMiningReference = (MiningGatherTask) currentReference;

                    if (currentMiningReference.getToMine() != miningTask.getToMine()) {
                        throw new IllegalStateException("not the same");
                    }

                    newTask = new MiningGatherTask(miningTask.getToMine(),
                            miningTask.mineCount+currentMiningReference.mineCount,newStack);
                } else {
                    throw new IllegalStateException("unknown");
                }

                parentList = map.get(currentReference);
                map.remove(currentReference);
            }

            parentList.add(parent);
            map.put(newTask,parentList);
            itemToTask.put(stack.getItem(), newTask);

        }

        @Override
        public String toString() {
            String str = "";

            for (Map.Entry<GatherTask, List<GatherTask>> entry : map.entrySet()) {
                str += entry.toString() + "\n";
            }

            return str;
        }
    }

    private static void getTreeLeafs(GatherTask parent, List<GatherTask> list) {
        if (parent.getChildren() == null || parent.getChildren().isEmpty()) {
            list.add(parent);
        } else {
            for (GatherTask child : parent.getChildren()) {
                getTreeLeafs(child, list);
            }
        }


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

                added.add(new MiningGatherTask(block, (int) Math.ceil(needed.getCount() / chance), needed));
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
