package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.blockpos.BlockPosHelper;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;

/**
 * Explores/Loads all chunks of a biome.
 */
public class SearchWithinBiomeTask extends SearchChunksExploreTask {

    private final Biome toSearch;

    public SearchWithinBiomeTask(Biome toSearch) {
        this.toSearch = toSearch;
    }

    @Override
    protected boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos) {
        return mod.getWorld().getBiome(BlockPosHelper.add(pos.getCenterBlockPos(),1,1,1)) == toSearch;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SearchWithinBiomeTask task) {
            return task.toSearch == toSearch;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Searching for+within biome: " + toSearch;
    }
}
