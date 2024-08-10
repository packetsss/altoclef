package adris.altoclef.newtaskcatalogue;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.mixins.CreateWorldScreenInvoker;
import adris.altoclef.newtaskcatalogue.gathertypes.CraftingGatherTask;
import adris.altoclef.newtaskcatalogue.gathertypes.GatherTask;
import adris.altoclef.newtaskcatalogue.gathertypes.MiningGatherTask;
import adris.altoclef.newtaskcatalogue.gathertypes.ParentGatherTask;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.CraftingHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.SaveLoading;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.IOException;
import java.util.*;

public class NewTaskCatalogue {

    public static boolean bypass = false;
    private static LifecycledResourceManager resourceManager;
    private static HashMap<Item, HashSet<Block>> dropsToBlocks = new HashMap<>();

    public static void init() {
        //FIXME this might be s bit sketchy but seems to work for now
        resourceManager = getResourceManager();
        Debug.logInternal("LOADED RESOURCE MANAGER");
    }

    public static void printGetItem(AltoClef mod, Item item) {
        mod.log("Found: " + mod.getCraftingRecipeTracker().getRecipesForItem(item).size() + " crafting recipe(s):");
        mod.log(mod.getCraftingRecipeTracker().getRecipesForItem(item));

        if (CraftingHelper.canCraftItemNow(mod, item)) {
            mod.log("Can craft right now, wahoo! ");
            mod.log(" > GO CRAFT");
            return;
        }

        HashMap<Block, List<Item>> blockDrops = new HashMap<>();
        dropsToBlocks = new HashMap<>();

        for (Map.Entry<Identifier, Resource> entry : resourceManager.findResources("loot_tables/blocks", (a) -> true).entrySet()) {
            Identifier identifier = entry.getKey();
            Resource resource = entry.getValue();

            String path = identifier.getPath();

            path = path.substring(path.lastIndexOf("/") + 1);
            path = path.replace(".json", "");

            Block block = mod.getPlayer().getRegistryManager().get(Registries.BLOCK.getKey()).get(new Identifier(path));


            // todo parse resource
            Scanner s;
            try {
                s = new Scanner(resource.getInputStream());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            String str = "";
            while (s.hasNextLine()) {
                str += s.nextLine();
            }

            JsonObject obj = JsonParser.parseString(str).getAsJsonObject();

            if (!obj.get("type").getAsString().equals("minecraft:block")) {
                throw new IllegalStateException("Non-block loot table found! " + identifier);
            }
            if (obj.get("pools") == null) {
                System.err.println("'pools' is null");
                continue;
            }

            List<Item> itemList = new ArrayList<>();
            for (JsonElement pool : obj.get("pools").getAsJsonArray()) {

                for (JsonElement poolEntry : pool.getAsJsonObject().get("entries").getAsJsonArray()) {
                    parseEntry(mod, poolEntry.getAsJsonObject(), itemList);
                }
            }

            mod.log(block + " : " + itemList);
            blockDrops.put(block, itemList);

            for (Item i : itemList) {
                if (!dropsToBlocks.containsKey(i)) {
                    dropsToBlocks.put(i, new HashSet<>());
                }
                dropsToBlocks.get(i).add(block);
            }
        }

        mod.log("Now build gather tree...");
        ParentGatherTask parent = new ParentGatherTask(new ItemStack(item, 1));

        growGatherTree(mod, new ItemStack(item, 1), parent, new HashSet<>());

        System.out.println("\n" + print(parent, ""));

        mod.log("//TODO get entity drops");
    }

    private static void parseEntry(AltoClef mod, JsonObject obj, List<Item> list) {
        String type = obj.getAsJsonObject().get("type").getAsString();

        if (type.equals("minecraft:item")) {
            String name = obj.getAsJsonObject().get("name").getAsString();
            name = name.replace("minecraft:", "");

            list.add(mod.getPlayer().getRegistryManager().get(Registries.ITEM.getKey()).get(new Identifier(name)));
        } else if (type.equals("minecraft:alternatives")) {
            for (JsonElement el : obj.getAsJsonObject().get("children").getAsJsonArray()) {
                parseEntry(mod, el.getAsJsonObject(), list);
            }
        } else {
            System.err.println("Isnt known type! " + obj + " : " + type);
        }
    }

    private static void growGatherTree(AltoClef mod, ItemStack needed, GatherTask gatherTask, HashSet<Item> alreadyMet) {
        System.out.println("CALLED FUNCTION "+gatherTask.hashCode());

        alreadyMet.add(needed.getItem());

        List<GatherTask> added = new ArrayList<>();

        // add crafting recipes
        for (RecipeTarget target : mod.getCraftingRecipeTracker().getRecipeTargets(needed.getItem(), needed.getCount())) {
            GatherTask task =new CraftingGatherTask(needed, target);

            gatherTask.addChild(task);
            added.add(task);
        }

        if (!dropsToBlocks.containsKey(needed.getItem())) {
            // mod.logWarning("Item not in blocks! "+needed.getItem());
        } else {
            // add blocks to mine
            for (Block block : dropsToBlocks.get(needed.getItem())) {
                GatherTask task = new MiningGatherTask(block, needed, null);

                gatherTask.addChild(task);
                added.add(task);
            }
        }

        // todo add entities to kill


        HashSet<Item> calledFor = new HashSet<>();
        for (GatherTask child : added) {
            HashMap<Item, Integer> neededItemsMap = new HashMap<>();

            for (ItemStack stack : child.getNeededItems()) {
                neededItemsMap.put(stack.getItem(),neededItemsMap.getOrDefault(stack.getItem(),0)+stack.getCount());
            }

            System.out.println("CHILD LOOP START: "+child.hashCode());
            for (Map.Entry<Item,Integer> entry : neededItemsMap.entrySet()) {
                ItemStack stack = new ItemStack(entry.getKey(),entry.getValue());

                if (stack.getItem() == Items.AIR || stack.getCount() <= 0) continue;
                if (calledFor.contains(stack.getItem())) continue;
                if (alreadyMet.contains(stack.getItem())) continue;

                calledFor.add(stack.getItem());

                System.out.println("getting "+stack + " ; "+child.hashCode());

                // todo maybe make this non-recursive
                growGatherTree(mod, stack, child, new HashSet<>(alreadyMet));
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

    private static LifecycledResourceManager getResourceManager() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen prevScreen = client.currentScreen;
        bypass = true;
        CreateWorldScreen.create(client, null);
        bypass = false;
        CreateWorldScreen screen = (CreateWorldScreen) client.currentScreen;
        client.setScreen(prevScreen);
        client.currentScreen = prevScreen;

        return startNewWorld(((CreateWorldScreenInvoker) screen).invokeCreateSession().get(), ((CreateWorldScreenInvoker) screen).invokeCreateLevelInfo(false));
    }


    private static LifecycledResourceManager startNewWorld(LevelStorage.Session session, LevelInfo saveProperties) {
        ResourcePackManager resourcePackManager = VanillaDataPackProvider.createManager(session);
        return (LifecycledResourceManager) (new SaveLoading.DataPacks(resourcePackManager, saveProperties.getDataConfiguration(), false, false)).load().getSecond();

    }


}
