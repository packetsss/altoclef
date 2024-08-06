package adris.altoclef;

import adris.altoclef.mixins.CreateWorldScreenInvoker;
import adris.altoclef.util.helpers.CraftingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.SaveLoading;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.IOException;
import java.util.Scanner;

public class NewTaskCatalogue {

    public static boolean bypass = false;
    private static LifecycledResourceManager resourceManager;

    public static void init() {
        //FIXME this might be s bit sketchy but seems to work for now
        resourceManager = getResourceManager();
        Debug.logInternal("LOADED RESOURCE MANAGER");
    }

    public static void printGetItem(AltoClef mod, Item item) {
        mod.log("Found: " + mod.getCraftingRecipeTracker().getRecipeForItem(item).size() + " crafting recipe(s):");
        mod.log(mod.getCraftingRecipeTracker().getRecipeForItem(item));

        if (CraftingHelper.canCraftItemNow(mod, item)) {
            mod.log("Can craft right now, wahoo! ");
            mod.log(" > GO CRAFT");
            return;
        }

        mod.log("Now we should try to search through block drops I guess?");

        for (Resource resource : resourceManager.getAllResources(new Identifier("minecraft", "loot_tables/blocks/gravel.json"))) {
            Scanner s = null;
            try {
                s = new Scanner(resource.getInputStream());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            while (s.hasNextLine()) {
                mod.log(s.nextLine());
            }
        }

        if (item instanceof BlockItem blockItem) {
            mod.log("Omg blok: " + blockItem.getBlock());
            mod.log(" > GO GET BLOCK (?)");
        }

        mod.log("//TODO get entity drops");

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
