package adris.altoclef.trackers;

import adris.altoclef.AltoClef;

import java.util.ArrayList;

public class TrackerManager {

    private final ArrayList<Tracker> trackers = new ArrayList<>();

    private final AltoClef mod;

    private boolean wasInGame = false;

    public TrackerManager(AltoClef mod) {
        this.mod = mod;
    }

    public void tick() {
        boolean inGame = AltoClef.inGame();
        if (!inGame && wasInGame) {
            // Reset when we leave our world
            for (Tracker tracker : trackers) {
                tracker.reset();
            }
            // This is a a spaghetti. Fix at some point.
            mod.getChunkTracker().reset(mod);
            mod.getMiscBlockTracker().reset();
        }
        wasInGame = inGame;

        for (Tracker tracker : trackers) {
            tracker.setDirty();
        }
    }

    public void addTracker(Tracker tracker) {
        tracker.mod = mod;
        trackers.add(tracker);
    }
}
