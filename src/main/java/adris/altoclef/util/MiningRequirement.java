package adris.altoclef.util;

import adris.altoclef.Debug;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents a pickaxe requirement you need to mine a specific block.
 * <p>
 * Other special items like shears are not considered.
 */
// FIXME this doesnt work for cobwebs because they are broken with shears...
public enum MiningRequirement implements Comparable<MiningRequirement> {
    HAND(Items.AIR),
    WOOD(Items.WOODEN_PICKAXE),
    STONE(Items.STONE_PICKAXE),
    IRON(Items.IRON_PICKAXE),
    DIAMOND(Items.DIAMOND_PICKAXE);

    private final Item pickaxe;

    MiningRequirement(Item pickaxe) {
        this.pickaxe = pickaxe;
    }

    public static MiningRequirement getMinimumRequirementForBlock(Block block) {
        if (!block.getDefaultState().isToolRequired()) return MiningRequirement.HAND;

        for (MiningRequirement req : MiningRequirement.values()) {
            if (req == MiningRequirement.HAND) continue;

            Item pick = req.getPickaxe();
            if (pick.getDefaultStack().isSuitableFor(block.getDefaultState())) {
                return req;
            }
        }

        Debug.logWarning("Failed to find ANY effective tool against: " + block + ". I assume netherite is not required anywhere, so something else probably went wrong.");
        return MiningRequirement.DIAMOND;
    }

    public static List<MiningRequirement> getRequirementsForBlock(Block block) {
        List<MiningRequirement> result = new ArrayList<>();

        if (!block.getDefaultState().isToolRequired()) {
            result.add(MiningRequirement.HAND);
        }
        for (MiningRequirement req : MiningRequirement.values()) {
            if (req == MiningRequirement.HAND) continue;
            Item pick = req.getPickaxe();
            if (pick.getDefaultStack().isSuitableFor(block.getDefaultState())) {
                result.add(req);
            }
        }
        if (result.isEmpty()) {
            Debug.logWarning("Failed to find ANY effective tool against: " + block + ".");
        }

        return result;
    }

    /**
     * @return The next mining requirement in order (eq. {@code STONE} -> {@code IRON})
     */
    public Optional<MiningRequirement> next() {
        int index = this.ordinal();
        if (index + 1 < values().length) {
            return Optional.of(values()[index + 1]);
        }

        return Optional.empty();
    }

    public Item getPickaxe() {
        return pickaxe;
    }

}
