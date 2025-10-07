package adris.altoclef.trackers;

import adris.altoclef.AltoClef;

import java.util.ArrayList;

public class TrackerManager {

    private final ArrayList<Tracker> _trackers = new ArrayList<>();

    private final AltoClef _mod;

    private boolean _wasInGame = false;

    public TrackerManager(AltoClef mod) {
        _mod = mod;
    }

    public void tick() {
        boolean inGame = AltoClef.inGame();
        if (!inGame && _wasInGame) {
            // Reset when we leave our world
            for (Tracker tracker : _trackers) {
                tracker.reset();
            }
            // This is a a spaghetti. Fix at some point.
            _mod.getChunkTracker().reset(_mod);
            _mod.getMiscBlockTracker().reset();
        }
        _wasInGame = inGame;

        for (Tracker tracker : _trackers) {
            tracker.setDirty();
        }
    }

    public void resetAllTrackers() {
        for (Tracker tracker : _trackers) {
            tracker.reset();
            tracker.setDirty();
        }
        if (_mod.getChunkTracker() != null) {
            _mod.getChunkTracker().reset(_mod);
        }
        if (_mod.getMiscBlockTracker() != null) {
            _mod.getMiscBlockTracker().reset();
        }
    }

    public void addTracker(Tracker tracker) {
        tracker.mod = _mod;
        _trackers.add(tracker);
    }
}
