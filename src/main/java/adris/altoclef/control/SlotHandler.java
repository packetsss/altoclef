package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.LinkedHashMap;
import java.util.Map;


public class SlotHandler {

    private final AltoClef mod;

    private final TimerGame slotActionTimer = new TimerGame(0);
    private boolean overrideTimerOnce = false;
    private static final int INVENTORY_SHUFFLE_ACTION_THRESHOLD = 80;
    private static final int INVENTORY_SHUFFLE_MIN_TICKS = 40;
    private static final int INVENTORY_SHUFFLE_RESET_TICKS = 60;
    private static final int INVENTORY_SHUFFLE_LOG_COOLDOWN_TICKS = 200;
    private static final double INVENTORY_SHUFFLE_MOVEMENT_THRESHOLD_SQ = 1.5 * 1.5;
    private int slotActionBurstCount = 0;
    private long slotActionBurstStartTick = -1;
    private long lastSlotActionTick = -1;
    private long lastInventoryShuffleLogTick = -1;
    private Vec3d slotActionBurstStartPos = null;

    public SlotHandler(AltoClef mod) {
        this.mod = mod;
    }

    private void forceAllowNextSlotAction() {
        overrideTimerOnce = true;
    }

    public boolean canDoSlotAction() {
        if (overrideTimerOnce) {
            overrideTimerOnce = false;
            return true;
        }
        slotActionTimer.setInterval(mod.getModSettings().getContainerItemMoveDelay());
        return slotActionTimer.elapsed();
    }

    public void registerSlotAction() {
        mod.getItemStorage().registerSlotAction();
        slotActionTimer.reset();
        monitorInventoryShuffle();
    }

    private void monitorInventoryShuffle() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            slotActionBurstCount = 0;
            slotActionBurstStartTick = -1;
            slotActionBurstStartPos = null;
            lastSlotActionTick = -1;
            return;
        }

        long currentTick = WorldHelper.getTicks();

        if (lastSlotActionTick == -1 || currentTick - lastSlotActionTick > INVENTORY_SHUFFLE_RESET_TICKS) {
            slotActionBurstCount = 0;
            slotActionBurstStartTick = currentTick;
            slotActionBurstStartPos = player.getPos();
        }

        slotActionBurstCount++;
        if (slotActionBurstStartTick == -1) {
            slotActionBurstStartTick = currentTick;
        }
        if (slotActionBurstStartPos == null) {
            slotActionBurstStartPos = player.getPos();
        }

        long elapsedTicks = currentTick - slotActionBurstStartTick;
        double travelledSq = player.getPos().squaredDistanceTo(slotActionBurstStartPos);

        boolean meetsThreshold = slotActionBurstCount >= INVENTORY_SHUFFLE_ACTION_THRESHOLD
                && elapsedTicks >= INVENTORY_SHUFFLE_MIN_TICKS
                && travelledSq <= INVENTORY_SHUFFLE_MOVEMENT_THRESHOLD_SQ;

        boolean cooledDown = lastInventoryShuffleLogTick == -1
                || currentTick - lastInventoryShuffleLogTick >= INVENTORY_SHUFFLE_LOG_COOLDOWN_TICKS;

        if (meetsThreshold && cooledDown && mod.getStuckLogManager() != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("slot_actions", slotActionBurstCount);
            payload.put("elapsed_ticks", elapsedTicks);
            payload.put("distance", Math.sqrt(travelledSq));
            payload.put("cursor_stack", describeStack(StorageHelper.getItemStackInSlot(CursorSlot.SLOT)));
            payload.put("selected_slot", player.getInventory().selectedSlot);
            payload.put("main_hand", describeStack(player.getMainHandStack()));
            mod.getStuckLogManager().recordEvent("InventoryShuffleStall", payload);
            lastInventoryShuffleLogTick = currentTick;
            slotActionBurstCount = 0;
            slotActionBurstStartTick = currentTick;
            slotActionBurstStartPos = player.getPos();
        }

        lastSlotActionTick = currentTick;
    }

    private Map<String, Object> describeStack(ItemStack stack) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (stack == null || stack.isEmpty()) {
            data.put("empty", true);
            return data;
        }
        data.put("item", stack.getItem().getTranslationKey());
        data.put("count", stack.getCount());
        data.put("damage", stack.getDamage());
        data.put("max_damage", stack.getMaxDamage());
        return data;
    }


    public void clickSlot(Slot slot, int mouseButton, SlotActionType type) {
        if (!canDoSlotAction()) return;

        if (slot.getWindowSlot() == -1) {
            clickSlot(PlayerSlot.UNDEFINED, 0, SlotActionType.PICKUP);
            return;
        }
        // NOT THE CASE! We may have something in the cursor slot to place.
        //if (getItemStackInSlot(slot).isEmpty()) return getItemStackInSlot(slot);

        clickWindowSlot(slot.getWindowSlot(), mouseButton, type);
    }

    private void clickSlotForce(Slot slot, int mouseButton, SlotActionType type) {
        forceAllowNextSlotAction();
        clickSlot(slot, mouseButton, type);
    }

    private void clickWindowSlot(int windowSlot, int mouseButton, SlotActionType type) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        registerSlotAction();
        int syncId = player.currentScreenHandler.syncId;

        try {
            mod.getController().clickSlot(syncId, windowSlot, mouseButton, type, player);
        } catch (Exception e) {
            Debug.logWarning("Slot Click Error (ignored)");
            e.printStackTrace();
        }
    }

    public void forceEquipItemToOffhand(Item toEquip) {
        if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() == toEquip) {
            return;
        }
        List<Slot> currentItemSlot = mod.getItemStorage().getSlotsWithItemPlayerInventory(false,
                toEquip);
        for (Slot CurrentItemSlot : currentItemSlot) {
            if (!Slot.isCursor(CurrentItemSlot)) {
                mod.getSlotHandler().clickSlot(CurrentItemSlot, 0, SlotActionType.PICKUP);
            } else {
                mod.getSlotHandler().clickSlot(PlayerSlot.OFFHAND_SLOT, 0, SlotActionType.PICKUP);
            }
        }
    }

    public boolean forceEquipItem(Item toEquip) {

        // Already equipped
        if (StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() == toEquip) return true;

        // Always equip to the second slot. First + last is occupied by baritone.
        mod.getPlayer().getInventory().selectedSlot = 1;

        // If our item is in our cursor, simply move it to the hotbar.
        boolean inCursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT).getItem() == toEquip;

        List<Slot> itemSlots = mod.getItemStorage().getSlotsWithItemScreen(toEquip);
        if (!itemSlots.isEmpty()) {
            for (Slot ItemSlots : itemSlots) {
                int hotbar = 1;
                //_mod.getPlayer().getInventory().swapSlotWithHotbar();
                clickSlotForce(Objects.requireNonNull(ItemSlots), inCursor ? 0 : hotbar, inCursor ? SlotActionType.PICKUP : SlotActionType.SWAP);
                //registerSlotAction();
            }
            return true;
        }
        return false;
    }

    public boolean forceDeequipHitTool() {
        return forceDeequip(stack -> stack.getItem() instanceof ToolItem);
    }

    public void forceDeequipRightClickableItem() {
        forceDeequip(stack -> {
                    Item item = stack.getItem();
                    return item instanceof BucketItem // water,lava,milk,fishes
                            || item instanceof EnderEyeItem
                            || item == Items.BOW
                            || item == Items.CROSSBOW
                            || item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE
                            || item == Items.ENDER_PEARL
                            || item instanceof FireworkRocketItem
                            || item instanceof SpawnEggItem
                            || item == Items.END_CRYSTAL
                            || item == Items.EXPERIENCE_BOTTLE
                            || item instanceof PotionItem // also includes splash/lingering
                            || item == Items.TRIDENT
                            || item == Items.WRITABLE_BOOK
                            || item == Items.WRITTEN_BOOK
                            || item instanceof FishingRodItem
                            || item instanceof OnAStickItem
                            || item == Items.COMPASS
                            || item instanceof EmptyMapItem
                            || item instanceof Equipment
                            || item == Items.LEAD
                            || item == Items.SHIELD;
                }
        );
    }

    /**
     * Tries to de-equip any item that we don't want equipped.
     *
     * @param isBad: Whether an item is bad/shouldn't be equipped
     * @return Whether we successfully de-equipped, or if we didn't have the item equipped at all.
     */
    public boolean forceDeequip(Predicate<ItemStack> isBad) {
        ItemStack equip = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        ItemStack cursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT);
        if (isBad.test(cursor)) {
            // Throw away cursor slot OR move
            Optional<Slot> fittableSlots = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(equip, false);
            if (fittableSlots.isEmpty()) {
                // Try to swap items with the first non-bad slot.
                for (Slot slot : Slot.getCurrentScreenSlots()) {
                    if (!isBad.test(StorageHelper.getItemStackInSlot(slot))) {
                        clickSlotForce(slot, 0, SlotActionType.PICKUP);
                        return false;
                    }
                }
                if (ItemHelper.canThrowAwayStack(mod, cursor)) {
                    clickSlotForce(PlayerSlot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return true;
                }
                // Can't throw :(
                return false;
            } else {
                // Put in the empty/available slot.
                clickSlotForce(fittableSlots.get(), 0, SlotActionType.PICKUP);
                return true;
            }
        } else if (isBad.test(equip)) {
            // Pick up the item
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, SlotActionType.PICKUP);
            return false;
        } else if (equip.isEmpty() && !cursor.isEmpty()) {
            // cursor is good and equip is empty, so finish filling it in.
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, SlotActionType.PICKUP);
            return true;
        }
        // We're already de-equipped
        return true;
    }

    public void forceEquipSlot(Slot slot) {
        Slot target = PlayerSlot.getEquipSlot();
        clickSlotForce(slot, target.getInventorySlot(), SlotActionType.SWAP);
    }

    public boolean forceEquipItem(Item[] matches, boolean unInterruptable) {
        return forceEquipItem(new ItemTarget(matches, 1), unInterruptable);
    }

    public boolean forceEquipItem(ItemTarget toEquip, boolean unInterruptable) {
        if (toEquip == null) return false;

        //If the bot try to eat
        if (mod.getFoodChain().needsToEat() && !unInterruptable) { //unless we really need to force equip the item
            return false; //don't equip the item for now
        }

        Slot target = PlayerSlot.getEquipSlot();
        // Already equipped
        if (toEquip.matches(StorageHelper.getItemStackInSlot(target).getItem())) return true;

        for (Item item : toEquip.getMatches()) {
            if (mod.getItemStorage().hasItem(item)) {
                if (forceEquipItem(item)) return true;
            }
        }
        return false;
    }

    // By default, don't force equip if the bot is eating.
    public boolean forceEquipItem(Item... toEquip) {
        return forceEquipItem(toEquip, false);
    }

    public void refreshInventory() {
        if (MinecraftClient.getInstance().player == null)
            return;
        for (int i = 0; i < MinecraftClient.getInstance().player.getInventory().main.size(); ++i) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            clickSlotForce(slot, 0, SlotActionType.PICKUP);
            clickSlotForce(slot, 0, SlotActionType.PICKUP);
        }
    }
}
