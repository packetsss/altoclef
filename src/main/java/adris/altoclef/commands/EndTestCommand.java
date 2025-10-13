package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.multiversion.entity.PlayerVer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sets up an End fight testing scenario by granting gear, teleporting to the End, and launching the speedrun task.
 */
public class EndTestCommand extends Command {

    private record LoadoutEntry(Item item, int count) {}

    private static final List<LoadoutEntry> LOADOUT = List.of(
            new LoadoutEntry(Items.DIAMOND_SWORD, 1),
            new LoadoutEntry(Items.DIAMOND_PICKAXE, 1),
            new LoadoutEntry(Items.DIAMOND_AXE, 1),
            new LoadoutEntry(Items.SHIELD, 1),
            new LoadoutEntry(Items.GOLDEN_HELMET, 1),
            new LoadoutEntry(Items.DIAMOND_CHESTPLATE, 1),
            new LoadoutEntry(Items.DIAMOND_LEGGINGS, 1),
            new LoadoutEntry(Items.DIAMOND_BOOTS, 1),
            new LoadoutEntry(Items.WHITE_BED, 12),
            new LoadoutEntry(Items.ENDER_PEARL, 16),
            new LoadoutEntry(Items.BLAZE_ROD, 12),
            new LoadoutEntry(Items.OBSIDIAN, 32),
            new LoadoutEntry(Items.COBBLESTONE, 64),
            new LoadoutEntry(Items.WATER_BUCKET, 1),
            new LoadoutEntry(Items.BOW, 1),
            new LoadoutEntry(Items.ARROW, 64),
            new LoadoutEntry(Items.COOKED_PORKCHOP, 64),
            new LoadoutEntry(Items.GOLDEN_APPLE, 8)
    );

    public EndTestCommand() {
        super("end_test", "Prepares the player for End fight testing and starts the gamer routine");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            Debug.logWarning("Cannot run @end_test: no client player present.");
            finish();
            return;
        }

    giveLoadout(player);
        topOffPlayer(player);
        attemptTeleportToEnd(player, mod);
        startGamer(mod);
        finish();
    }

    private void giveLoadout(ClientPlayerEntity player) {
        for (LoadoutEntry entry : LOADOUT) {
            giveItem(player, entry.item, entry.count);
        }
    }

    private void topOffPlayer(ClientPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().add(20, 20);
    }

    private void attemptTeleportToEnd(ClientPlayerEntity player, AltoClef mod) {
        boolean canTeleportAutomatically = MinecraftClient.getInstance().isInSingleplayer() || player.hasPermissionLevel(2);
        if (!canTeleportAutomatically) {
            Debug.logWarning("@end_test: Missing permission to run teleport command. Please move to the End manually.");
            return;
        }
        String targetName = player.getGameProfile().getName();
        String command = String.format(Locale.ROOT, "execute in minecraft:the_end run tp %s 0 90 0", targetName);
        PlayerVer.sendChatCommand(player, command);
    }

    private void startGamer(AltoClef mod) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        String prefix = mod.getModSettings().getCommandPrefix();
        PlayerVer.sendChatMessage(player, prefix + "gamer");
    }

    private void giveItem(ClientPlayerEntity player, Item item, int count) {
        int remaining = count;
        while (remaining > 0) {
            int toGive = Math.min(item.getMaxCount(), remaining);
            ItemStack stack = new ItemStack(item, toGive);
            giveStack(player, stack);
            remaining -= toGive;
        }
    }

    private void giveStack(ClientPlayerEntity player, ItemStack stack) {
        ItemStack copy = stack.copy();
        boolean accepted = player.giveItemStack(copy);
        if (!accepted && !copy.isEmpty()) {
            player.dropItem(copy, false);
        }
    }
}
