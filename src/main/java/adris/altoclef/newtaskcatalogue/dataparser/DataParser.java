package adris.altoclef.newtaskcatalogue.dataparser;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.mixins.CreateWorldScreenInvoker;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.item.Item;
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

public class DataParser {

    private static LifecycledResourceManager resourceManager;
    public static boolean bypass = false;

    public static void init() {
        //FIXME this might be s bit sketchy but seems to work for now
        resourceManager = createResourceManager();
        Debug.logInternal("LOADED RESOURCE MANAGER");
    }

    private static LifecycledResourceManager createResourceManager() {
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


    private static HashMap<Item, List<Block>> dropToBlocks;
    private static HashMap<Block, List<ItemDrop>> blockDropMap;

    public static HashMap<Item, List<Block>> getDropToBlocks() {
        return dropToBlocks;
    }

    public static HashMap<Block, List<ItemDrop>> getBlockDropMap() {
        return blockDropMap;
    }

    public static void parseBlockData(AltoClef mod) {
        dropToBlocks = new HashMap<>();
        blockDropMap = new HashMap<>();

        for (Map.Entry<Identifier, Resource> entry : resourceManager.findResources("loot_tables/blocks", (a) -> true).entrySet()) {
            Identifier identifier = entry.getKey();
            Resource resource = entry.getValue();

            String path = identifier.getPath();

            path = path.substring(path.lastIndexOf("/") + 1);
            path = path.replace(".json", "");

            Block block = mod.getPlayer().getRegistryManager().get(Registries.BLOCK.getKey()).get(new Identifier(path));

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

            List<ItemDrop> itemList = new ArrayList<>();
            for (JsonElement pool : obj.get("pools").getAsJsonArray()) {

                for (JsonElement poolEntry : pool.getAsJsonObject().get("entries").getAsJsonArray()) {
                    parseEntry(mod, poolEntry.getAsJsonObject(), itemList);
                }
            }

            mod.log(block + " : " + itemList);

            blockDropMap.put(block, itemList);

            for (ItemDrop drop : itemList) {
                Item i = drop.item();

                if (!dropToBlocks.containsKey(i)) {
                    dropToBlocks.put(i, new ArrayList<>());
                }
                dropToBlocks.get(i).add(block);
            }
        }
    }

    private static void parseEntry(AltoClef mod, JsonObject obj, List<ItemDrop> list) {
        String type = obj.getAsJsonObject().get("type").getAsString();

        if (type.equals("minecraft:item")) {
            String name = obj.getAsJsonObject().get("name").getAsString();
            name = name.replace("minecraft:", "");

            Item droppedItem = mod.getPlayer().getRegistryManager().get(Registries.ITEM.getKey()).get(new Identifier(name));
            float dropChance = parseDropChance(obj.getAsJsonObject());

            list.add(new ItemDrop(droppedItem,dropChance));
        } else if (type.equals("minecraft:alternatives")) {
            for (JsonElement el : obj.getAsJsonObject().get("children").getAsJsonArray()) {
                parseEntry(mod, el.getAsJsonObject(), list);
            }
        } else {
            System.err.println("Isnt known type! " + obj + " : " + type);
        }
    }


    // TODO parse also other conditions
    private static float parseDropChance(JsonObject obj) {
        float dropChance = 1;
        if (obj.has("conditions")) {
            for (JsonElement element : obj.get("conditions").getAsJsonArray()) {
                if (element.getAsJsonObject().has("chances")) {
                    if (dropChance != 1) {
                        throw new IllegalStateException("multiple drop chances!! "+obj);
                    }

                    dropChance = element.getAsJsonObject().get("chances").getAsJsonArray().get(0).getAsFloat();
                }
            }

            JsonObject conditionsObj = obj.getAsJsonArray("conditions").get(0).getAsJsonObject();
            if (conditionsObj.has("chances")) {
                dropChance = conditionsObj.get("chances").getAsJsonArray().get(0).getAsFloat();
            }
        }

        return dropChance;
    }

    public static LifecycledResourceManager getResourceManager() {
        return resourceManager;
    }


    private static LifecycledResourceManager startNewWorld(LevelStorage.Session session, LevelInfo saveProperties) {
        ResourcePackManager resourcePackManager = VanillaDataPackProvider.createManager(session);
        return (LifecycledResourceManager) (new SaveLoading.DataPacks(resourcePackManager, saveProperties.getDataConfiguration(), false, false)).load().getSecond();
    }



}
