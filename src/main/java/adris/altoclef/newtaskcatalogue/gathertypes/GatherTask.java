package adris.altoclef.newtaskcatalogue.gathertypes;

import adris.altoclef.AltoClef;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.*;


/**
 * gather specific amount of item in a specific way (mining, crafting, killing) task is complete when the amount of the item in inventory is sufficient
 */
public abstract class GatherTask {

    private static int GLOBAL_ID = 0;
    private final List<GatherTask> children;
    private final ItemStack stack;
    private final int id;
    private final List<GatherTask> bestChildCombo = new ArrayList<>();
    private boolean computedBestChildCombo = false;

    public GatherTask(ItemStack stack, List<GatherTask> children) {
        if (children == null) {
            children = new ArrayList<>();
        }
        if (stack == null) {
            stack = ItemStack.EMPTY;
        }
        this.children = children;
        this.stack = stack;
        this.id = GLOBAL_ID;
        GLOBAL_ID++;
    }

    private void updateChildren(AltoClef mod) {
        children.removeIf(child -> child.isComplete(mod));
    }

    // todo refactor
    public void update(){
    }

    public void addChild(GatherTask child) {
        if (child == null) return;
        if (child.getType() == GatherType.PARENT) {
            throw new IllegalStateException("Cannot add parent as a child!");
        }
        boolean isNeeded = false;
        for (ItemStack stack : getNeededItems()) {
            if (child.getItemStack().getItem().equals(stack.getItem())) {
                isNeeded = true;
                break;
            }
        }
        if (!isNeeded) {
            throw new IllegalStateException("Invalid child! "+this.getNeededItems() + " : "+child.getItemStack());
        }

        for (GatherTask task : children) {
            if (task.toString().equals(child.toString())) {
                throw new IllegalStateException("how tho wtf");
            }
        }
        children.add(child);
    }

    public List<GatherTask> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public ItemStack getItemStack() {
        return new ItemStack(stack.getItem(),stack.getCount());
    }


    public abstract List<ItemStack> getNeededItems();

    public abstract GatherType getType();

    public boolean isComplete(AltoClef mod) {
        updateChildren(mod);
        if (children.isEmpty()) {
            return this.isSelfComplete(mod);
        }

        return false;
    }

    public void computeChildTaskCombo(AltoClef mod) {
        HashMap<Item, List<GatherTask>> itemTasksMap = new HashMap<>();

        for (GatherTask child : children) {
            Item item = child.getItemStack().getItem();
            if (!itemTasksMap.containsKey(item)) {
                itemTasksMap.put(item, new ArrayList<>());
            }
            itemTasksMap.get(item).add(child);
        }

        bestChildCombo.clear();

        for (Map.Entry<Item,List<GatherTask>> entry : itemTasksMap.entrySet()) {
            GatherTask bestCandidate = null;
            double lowestWeight = Double.POSITIVE_INFINITY;

            for (GatherTask candidate : entry.getValue()) {
                double weight = candidate.getWeight(mod);
                if (weight < lowestWeight) {
                    lowestWeight = weight;
                    bestCandidate = candidate;
                }
            }
            if (bestCandidate == null) continue;

            bestChildCombo.add(bestCandidate);
        }

        // calculate also for all children for convenience, nothing should happen if the function is called multiple times for whatever reason
        for (GatherTask child : bestChildCombo) {
            child.computeChildTaskCombo(mod);
        }

        computedBestChildCombo = true;
    }

    public void setChildrenToComputed() {
        this.children.clear();
        this.children.addAll(new ArrayList<>(getBestChildCombo()));

        for (GatherTask child : children) {
            child.setChildrenToComputed();
        }

    }

    public final double getWeight(AltoClef mod) {
        double min = Double.POSITIVE_INFINITY;

        for (GatherTask child : children) {
            min = Math.min(child.getWeight(mod), min);
        }
        if (children.isEmpty()) {
            min = 0;
        }

        return min + getSelfWeight(mod);
    }

    protected abstract double getSelfWeight(AltoClef mod);

    protected abstract boolean isSelfComplete(AltoClef mod);

    public boolean canBeDone(AltoClef mod) {
        for (ItemStack stack : getNeededItems()) {
            if (mod.getItemStorage().getItemCount(stack.getItem()) < stack.getCount()) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return id;
    }

    public List<GatherTask> getBestChildCombo() {
        if (!computedBestChildCombo) {
            throw new IllegalStateException("Has not been computed yet!");
        }

        return Collections.unmodifiableList(bestChildCombo);
    }

    public boolean hasComputedBestChildCombo() {
        return computedBestChildCombo;
    }
}
