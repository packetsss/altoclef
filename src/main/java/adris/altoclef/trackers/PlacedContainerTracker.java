package adris.altoclef.trackers;

import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks container blocks placed by the bot so we can preferentially pick them back up later.
 */
public class PlacedContainerTracker extends Tracker {

    private final Map<Block, LinkedHashMap<BlockPos, Dimension>> placements = new LinkedHashMap<>();

    public PlacedContainerTracker(TrackerManager manager) {
        super(manager);
    }

    /**
     * Record that the bot placed a container block at the given position.
     */
    public void registerPlacement(Block block, BlockPos position) {
        if (block == null || position == null) {
            return;
        }
        BlockPos immutable = position.toImmutable();
        LinkedHashMap<BlockPos, Dimension> entries = placements.computeIfAbsent(block, ignored -> new LinkedHashMap<>());
        Dimension dimension = WorldHelper.getCurrentDimension();
        Dimension previous = entries.get(immutable);
        if (!Objects.equals(previous, dimension)) {
            entries.put(immutable, dimension);
        }
    }

    /**
     * Forget a placement once the container has been recovered or otherwise invalidated.
     */
    public void forgetPlacement(Block block, BlockPos position) {
        if (block == null || position == null) {
            return;
        }
        LinkedHashMap<BlockPos, Dimension> entries = placements.get(block);
        if (entries == null) {
            return;
        }
        entries.remove(position);
        if (entries.isEmpty()) {
            placements.remove(block);
        }
    }

    /**
     * Retrieve all positions the bot has placed for the provided container block.
     */
    public Collection<BlockPos> getPlacements(Block block) {
        ensureUpdated();
        LinkedHashMap<BlockPos, Dimension> entries = placements.get(block);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        Dimension current = WorldHelper.getCurrentDimension();
        ArrayList<BlockPos> result = new ArrayList<>(entries.size());
        for (Map.Entry<BlockPos, Dimension> entry : entries.entrySet()) {
            Dimension stored = entry.getValue();
            if (stored == null || stored == current) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Returns true if we are tracking the provided block position as one the bot placed itself.
     */
    public boolean isTracked(Block block, BlockPos position) {
        LinkedHashMap<BlockPos, Dimension> entries = placements.get(block);
        return entries != null && entries.containsKey(position);
    }

    @Override
    protected void updateState() {
        if (mod == null || mod.getWorld() == null) {
            placements.clear();
            return;
        }
        Iterator<Map.Entry<Block, LinkedHashMap<BlockPos, Dimension>>> outer = placements.entrySet().iterator();
        while (outer.hasNext()) {
            Map.Entry<Block, LinkedHashMap<BlockPos, Dimension>> entry = outer.next();
            Block block = entry.getKey();
            LinkedHashMap<BlockPos, Dimension> locations = entry.getValue();
            Iterator<Map.Entry<BlockPos, Dimension>> inner = locations.entrySet().iterator();
            while (inner.hasNext()) {
                Map.Entry<BlockPos, Dimension> placement = inner.next();
                BlockPos pos = placement.getKey();
                Dimension dimension = placement.getValue();
                if (dimension == null || dimension == WorldHelper.getCurrentDimension()) {
                    if (!mod.getChunkTracker().isChunkLoaded(pos)) {
                        continue;
                    }
                    if (mod.getWorld().getBlockState(pos).getBlock() != block) {
                        inner.remove();
                    }
                }
            }
            if (locations.isEmpty()) {
                outer.remove();
            }
        }
    }

    @Override
    protected void reset() {
        placements.clear();
    }
}
