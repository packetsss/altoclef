package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Use to walk through and search interconnected structures or biomes.
 * <p>
 * Example use cases:
 * - Search a dark forest for a woodland mansion and avoid going to different biomes
 * - Search a nether fortress for blaze spawners
 * - Search a stronghold for the portal
 */
abstract class ChunkSearchTask extends Task {

    private final BlockPos startPoint;
    private final Object searchMutex = new Object();
    // We're either searched or will be searched later.
    private final Set<ChunkPos> consideredAlready = new HashSet<>();
    // We definitely were searched before.
    private final Set<ChunkPos> searchedAlready = new HashSet<>();
    private final ArrayList<ChunkPos> searchLater = new ArrayList<>();
    private final ArrayList<ChunkPos> justLoaded = new ArrayList<>();
    private boolean first = true;
    private boolean finished = false;

    private Subscription<ChunkLoadEvent> onChunkLoad;

    public ChunkSearchTask(BlockPos startPoint) {
        this.startPoint = startPoint;
    }

    public ChunkSearchTask(ChunkPos chunkPos) {
        this(adris.altoclef.multiversion.blockpos.BlockPosHelper.add(chunkPos.getCenterBlockPos(),1,1,1));
    }

    public Set<ChunkPos> getSearchedChunks() {
        return searchedAlready;
    }

    public boolean finished() {
        return finished;
    }

    @Override
    protected void onStart(AltoClef mod) {

        //Debug.logMessage("(deleteme) start. Finished: " + finished);
        if (first) {
            finished = false;
            first = false;
            ChunkPos startPos = mod.getWorld().getChunk(startPoint).getPos();
            synchronized (searchMutex) {
                searchChunkOrQueueSearch(mod, startPos);
            }
        }

        onChunkLoad = EventBus.subscribe(ChunkLoadEvent.class, evt -> {
            WorldChunk chunk = evt.chunk;
            if (chunk == null) return;
            synchronized (searchMutex) {
                if (!searchedAlready.contains(chunk.getPos())) {
                    justLoaded.add(chunk.getPos());
                }
            }
        });
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // WTF This is a horrible idea.
        // Backup in case if chunk search fails?
        //onChunkLoad((WorldChunk) mod.getWorld().getChunk(mod.getPlayer().getBlockPos()));

        synchronized (searchMutex) {
            // Search all items from justLoaded that we ought to search.
            if (!justLoaded.isEmpty()) {
                for (ChunkPos justLoaded : justLoaded) {
                    if (searchLater.contains(justLoaded)) {
                        // Search this one. If we succeed, we no longer need to search.
                        if (trySearchChunk(mod, justLoaded)) {
                            searchLater.remove(justLoaded);
                        }
                    }
                }
            }
            justLoaded.clear();
        }

        // Now that we have an updated map, go to the nearest
        ChunkPos closest = getBestChunk(mod, searchLater);

        if (closest == null) {
            finished = true;
            Debug.logWarning("Failed to find any chunks to go to. If we finish, that means we scanned all possible chunks.");
            //Debug.logMessage("wtf??????: " + finished);
            return null;
        }

        return new GetToChunkTask(closest);
    }

    // Virtual
    protected ChunkPos getBestChunk(AltoClef mod, List<ChunkPos> chunks) {
        double lowestScore = Double.POSITIVE_INFINITY;
        ChunkPos bestChunk = null;
        if (!chunks.isEmpty()) {
            for (ChunkPos toSearch : chunks) {
                double cx = (toSearch.getStartX() + toSearch.getEndX() + 1) / 2.0, cz = (toSearch.getStartZ() + toSearch.getEndZ() + 1) / 2.0;
                double px = mod.getPlayer().getX(), pz = mod.getPlayer().getZ();
                double distanceSq = (cx - px) * (cx - px) + (cz - pz) * (cz - pz);
                double distanceToCenterSq = new Vec3d(startPoint.getX() - cx, 0, startPoint.getZ() - cz).lengthSquared();
                double score = distanceSq + distanceToCenterSq * 0.8;
                if (score < lowestScore) {
                    lowestScore = score;
                    bestChunk = toSearch;
                }
            }
        }
        return bestChunk;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        EventBus.unsubscribe(onChunkLoad);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return searchLater.size() == 0;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ChunkSearchTask task) {
            if (!task.startPoint.equals(startPoint)) return false;
            return isChunkSearchEqual(task);
        }
        return false;
    }

    private void searchChunkOrQueueSearch(AltoClef mod, ChunkPos pos) {
        // Don't search/consider this chunk again.
        if (consideredAlready.contains(pos)) {
            return;
        }
        consideredAlready.add(pos);

        if (!trySearchChunk(mod, pos)) {
            // We'll check it later if we haven't searched it.
            if (!searchedAlready.contains(pos)) {
                searchLater.add(pos);
            }
        }
    }

    /**
     * Try to search the chunk.
     *
     * @param pos chunk to search
     * @return true if we're DONE searching this chunk
     * false if we need to SEARCH IT IN PERSON
     */
    private boolean trySearchChunk(AltoClef mod, ChunkPos pos) {
        // Do NOT search later.
        if (searchedAlready.contains(pos)) {
            return true;
        }
        if (mod.getChunkTracker().isChunkLoaded(pos)) {
            searchedAlready.add(pos);
            if (isChunkPartOfSearchSpace(mod, pos)) {
                // This chunk may lead to more, so either search or enqueue its neighbors.
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x + 1, pos.z));
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x - 1, pos.z));
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x, pos.z + 1));
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x, pos.z - 1));
            }
            return true;
        }
        return false;
    }

    protected abstract boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos);

    protected abstract boolean isChunkSearchEqual(ChunkSearchTask other);
}
