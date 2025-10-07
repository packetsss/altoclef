package adris.altoclef.tasks.speedrun.beatgame;

import adris.altoclef.Debug;
import adris.altoclef.util.helpers.ConfigHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists {@link BeatMinecraftState} objects to disk per world so that progress survives restarts.
 */
public class BeatMinecraftStateStore {

    private static final String STATE_ROOT = "state/beat_minecraft";
    private final String worldId;
    private final String relativePath;

    public BeatMinecraftStateStore(String worldId) {
        this.worldId = worldId == null || worldId.isBlank() ? "unknown" : sanitize(worldId);
        this.relativePath = STATE_ROOT + "/" + this.worldId + ".json";
    }

    public Optional<BeatMinecraftState> load() {
        Path file = resolvePath();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        AtomicReference<BeatMinecraftState> ref = new AtomicReference<>();
        ConfigHelper.loadConfig(relativePath, BeatMinecraftState::new, BeatMinecraftState.class, ref::set);
        BeatMinecraftState state = ref.get();
        if (state == null) {
            return Optional.empty();
        }
        if (state.worldId == null || state.worldId.isBlank() || !state.worldId.equals(this.worldId)) {
            state.worldId = this.worldId;
        }
        return Optional.of(state);
    }

    public void save(BeatMinecraftState state) {
        if (state == null) {
            return;
        }
        state.worldId = this.worldId;
        state.savedAtEpochMs = System.currentTimeMillis();
        Debug.logInternal("Persisting BeatMinecraft progress for world " + worldId);
        ConfigHelper.saveConfig(relativePath, state);
    }

    private Path resolvePath() {
        return Paths.get("altoclef", relativePath);
    }

    private static String sanitize(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-_]+", "_");
    }
}
