package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmithingTableSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class UpgradeInSmithingTableTask extends ResourceTask {

    private final ItemTarget tool;
    private final ItemTarget template;
    private final ItemTarget material;
    private final ItemTarget output;

    private final Task innerTask;

    public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget output) {
        super(output);
        this.tool = new ItemTarget(tool, output.getTargetCount());
        this.material = new ItemTarget(material, output.getTargetCount());
        template = new ItemTarget("netherite_upgrade_smithing_template", output.getTargetCount());
        this.output = output;
        innerTask = new UpgradeInSmithingTableInternalTask();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    private int getItemsInSlot(Slot slot, ItemTarget match) {
        ItemStack stack = StorageHelper.getItemStackInSlot(slot);
        if (!stack.isEmpty() && match.matches(stack.getItem())) {
            return stack.getCount();
        }
        return 0;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // if we don't have tools + materials, get them.

        boolean inSmithingTable = (mod.getPlayer().currentScreenHandler instanceof SmithingScreenHandler);

        int templatesInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.INPUT_SLOT_TEMPLATE, template) : 0;
        int materialsInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.INPUT_SLOT_MATERIALS, material) : 0;
        int toolsInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.INPUT_SLOT_TOOL, tool) : 0;
        int ouputInSlot = inSmithingTable ? getItemsInSlot(SmithingTableSlot.OUTPUT_SLOT, output) : 0;

        int desiredOutput = output.getTargetCount() - ouputInSlot;

        if (mod.getItemStorage().getItemCount(tool) + toolsInSlot < desiredOutput ||
                mod.getItemStorage().getItemCount(material) + materialsInSlot < desiredOutput ||
                mod.getItemStorage().getItemCount(template) + templatesInSlot < desiredOutput) {
            setDebugState("Getting materials + tools");
            return TaskCatalogue.getSquashedItemTask(tool, material, template);
        }

        // Edge case: We are wearing the armor we want to upgrade. If so, remove it.
        if (StorageHelper.isArmorEquipped(mod, tool.getMatches())) {
            // Exit out of any screen so we can move our armor
            if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
                ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
                if (!cursorStack.isEmpty()) {
                    Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                    if (moveTo.isPresent()) {
                        mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                        return null;
                    }
                    if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                        mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                        return null;
                    }
                    Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                    // Try throwing away cursor slot if it's garbage
                    if (garbage.isPresent()) {
                        mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                        return null;
                    }
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    StorageHelper.closeScreen();
                }
                setDebugState("Quickly removing equipped armor");
                return null;
            }
            // Take off our armor
            if (mod.getItemStorage().hasEmptyInventorySlot()) {
                return new EnsureFreeInventorySlotTask();
            }
            for (Slot armorSlot : PlayerSlot.ARMOR_SLOTS) {
                if (tool.matches(StorageHelper.getItemStackInSlot(armorSlot).getItem())) {
                    setDebugState("Quickly removing equipped armor");
                    mod.getSlotHandler().clickSlot(armorSlot, 0, SlotActionType.QUICK_MOVE);
                    return null;
                }
            }
        }

        setDebugState("Smithing...");
        return innerTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof UpgradeInSmithingTableTask task) {
            return task.tool.equals(tool) && task.output.equals(output) && task.material.equals(material);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Upgrading " + tool.toString() + " + " + material.toString() + " -> " + output.toString();
    }

    public ItemTarget getTools() {
        return tool;
    }

    public ItemTarget getMaterials() {
        return material;
    }

    private class UpgradeInSmithingTableInternalTask extends DoStuffInContainerTask {

        private final TimerGame invTimer;

        public UpgradeInSmithingTableInternalTask() {
            super(Blocks.SMITHING_TABLE, new ItemTarget("smithing_table"));
            invTimer = new TimerGame(0);
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            // inner part, don't care
            return true;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().currentScreenHandler instanceof SmithingScreenHandler);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            setDebugState("Smithing...");
            // We have our tools + materials. Now, do the thing.
            invTimer.setInterval(mod.getModSettings().getContainerItemMoveDelay());

            // Run once every
            if (!invTimer.elapsed()) {
                return null;
            }
            invTimer.reset();

            Slot templateSlot = SmithingTableSlot.INPUT_SLOT_TEMPLATE;
            Slot materialSlot = SmithingTableSlot.INPUT_SLOT_MATERIALS;
            Slot toolSlot = SmithingTableSlot.INPUT_SLOT_TOOL;
            Slot outputSlot = SmithingTableSlot.OUTPUT_SLOT;

            ItemStack currentTemplates = StorageHelper.getItemStackInSlot(templateSlot);
            ItemStack currentMaterials = StorageHelper.getItemStackInSlot(materialSlot);
            ItemStack currentTools = StorageHelper.getItemStackInSlot(toolSlot);
            ItemStack currentOutput = StorageHelper.getItemStackInSlot(outputSlot);
            // Grab from output
            if (!currentOutput.isEmpty()) {
                mod.getSlotHandler().clickSlot(outputSlot, 0, SlotActionType.QUICK_MOVE);
                return null;
            }
            // Put materials in slot
            if (currentMaterials.isEmpty() || !material.matches(currentMaterials.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(material, 1), materialSlot);
            }
            // Put tool in slot
            if (currentTools.isEmpty() || !tool.matches(currentTools.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(tool, 1), toolSlot);
            }

            if (currentTemplates.isEmpty() || !template.matches(currentTemplates.getItem())) {
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(template, 1), templateSlot);
            }

            setDebugState("PROBLEM: Nothing to do!");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            int price = 400;
            if (mod.getItemStorage().hasItem(ItemHelper.LOG) || mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4) {
                price -= 125;
            }
            if (mod.getItemStorage().getItemCount(Items.FLINT) >= 2) {
                price -= 125;
            }
            return price;
        }
    }

}
