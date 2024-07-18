package adris.altoclef.multiversion;

import net.minecraft.block.Block;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.registry.Registry;

public class BlockTagVer {


    public static boolean isWool(Block block) {
        //#if MC >= 11802
        //$$ return Registry.BLOCK.getKey(block).map(e -> Registry.BLOCK.entryOf(e).streamTags().anyMatch(t -> t == BlockTags.WOOL)).orElse(false);
        //#else
        return BlockTags.WOOL.contains(block);
        //#endif
    }

}
