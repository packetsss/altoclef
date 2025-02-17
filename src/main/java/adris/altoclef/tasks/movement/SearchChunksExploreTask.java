package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Searches/explores a continuous "blob" of chunks, attempting to load in ALL nearby chunks that are part of this "blob"
 * <p>
 * You must define a function that determines whether a chunk is to be included within this "blob".
 * <p>
 * For instance, if you wish to explore an entire desert, this function will return whether a chunk is a desert chunk.
 */
public abstract class SearchChunksExploreTask extends Task {

    private final Object searcherMutex = new Object();
    private final Set<ChunkPos> alreadyExplored = new HashSet<>();
    private ChunkSearchTask searcher;
    private AltoClef mod;
    private Subscription<ChunkLoadEvent> chunkLoadedSubscription;

    // Virtual
    protected ChunkPos getBestChunkOverride(AltoClef mod, List<ChunkPos> chunks) {
        return null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;

        // Listen for chunk loading
        chunkLoadedSubscription = EventBus.subscribe(ChunkLoadEvent.class, evt -> onChunkLoad(evt.chunk.getPos()));

        resetSearch(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        synchronized (searcherMutex) {
            if (searcher == null) {
                setDebugState("Exploring/Searching for valid chunk");
                // Explore
                return getWanderTask(mod);
            }

            if (searcher.isActive() && searcher.isFinished(mod)) {
                Debug.logWarning("Target object search failed.");
                alreadyExplored.addAll(searcher.getSearchedChunks());
                searcher = null;
            } else if (searcher.finished()) {
                setDebugState("Searching for target object...");
                Debug.logMessage("Search finished.");
                alreadyExplored.addAll(searcher.getSearchedChunks());
                searcher = null;
            }
            //Debug.logMessage("wtf: " + (searcher == null? "(null)" :_searcher.finished()));
            setDebugState("Searching within chunks...");
            return searcher;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        EventBus.unsubscribe(chunkLoadedSubscription);
    }

    // When we find a valid chunk, start our search there.
    private void onChunkLoad(ChunkPos pos) {
        if (searcher != null) return;
        if (!this.isActive()) return;
        if (isChunkWithinSearchSpace(mod, pos)) {
            synchronized (searcherMutex) {
                if (!alreadyExplored.contains(pos)) {
                    Debug.logMessage("New searcher: " + pos);
                    searcher = new SearchSubTask(pos);
                }
            }
        }
    }

    protected Task getWanderTask(AltoClef mod) {
        return new TimeoutWanderTask(true);
    }

    protected abstract boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos);

    public boolean failedSearch() {
        return searcher == null;
    }

    public void resetSearch(AltoClef mod) {
        //Debug.logMessage("Search reset");
        searcher = null;
        alreadyExplored.clear();
        // We want to search the currently loaded chunks too!!!
        for (ChunkPos start : mod.getChunkTracker().getLoadedChunks()) {
            onChunkLoad(start);
        }
    }

    class SearchSubTask extends ChunkSearchTask {

        public SearchSubTask(ChunkPos start) {
            super(start);
        }

        @Override
        protected boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos) {
            return isChunkWithinSearchSpace(mod, pos);
        }

        @Override
        public ChunkPos getBestChunk(AltoClef mod, List<ChunkPos> chunks) {
            ChunkPos override = getBestChunkOverride(mod, chunks);
            if (override != null) return override;
            return super.getBestChunk(mod, chunks);
        }

        @Override
        protected boolean isChunkSearchEqual(ChunkSearchTask other) {
            // Since we're keeping track of "_searcher", we expect the subchild routine to ALWAYS be consistent!
            return other == this;//return other instanceof SearchSubTask;
        }

        @Override
        protected String toDebugString() {
            return "Searching chunks...";
        }
    }

}
