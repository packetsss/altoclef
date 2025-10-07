package adris.altoclef.tasks.speedrun.beatgame;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/**
 * Persistent snapshot of the BeatMinecraftTask progress. Stored per-world so that
 * the task can resume after relaunching the client without repeating early stages.
 */
public class BeatMinecraftState {

    public String worldId;
    public boolean ranStrongholdLocator;
    public boolean endPortalOpened;
    public boolean dragonIsDead;
    public boolean gotToFortress;
    public boolean gotToBiome;
    public boolean escapedNetherHub;
    public int cachedFilledPortalFrames;
    public BlockPos endPortalCenterLocation;
    public BlockPos bedSpawnLocation;
    public long savedAtEpochMs;

    public BeatMinecraftState() {
        // Jackson constructor
    }

    public BeatMinecraftState copy() {
        BeatMinecraftState clone = new BeatMinecraftState();
        clone.worldId = worldId;
        clone.ranStrongholdLocator = ranStrongholdLocator;
        clone.endPortalOpened = endPortalOpened;
        clone.dragonIsDead = dragonIsDead;
        clone.gotToFortress = gotToFortress;
        clone.gotToBiome = gotToBiome;
        clone.escapedNetherHub = escapedNetherHub;
        clone.cachedFilledPortalFrames = cachedFilledPortalFrames;
        clone.endPortalCenterLocation = endPortalCenterLocation;
        clone.bedSpawnLocation = bedSpawnLocation;
        clone.savedAtEpochMs = savedAtEpochMs;
        return clone;
    }

    public boolean sameProgressAs(BeatMinecraftState other) {
        if (other == null) {
            return false;
        }
        return ranStrongholdLocator == other.ranStrongholdLocator
                && endPortalOpened == other.endPortalOpened
                && dragonIsDead == other.dragonIsDead
                && gotToFortress == other.gotToFortress
                && gotToBiome == other.gotToBiome
                && escapedNetherHub == other.escapedNetherHub
                && cachedFilledPortalFrames == other.cachedFilledPortalFrames
                && Objects.equals(endPortalCenterLocation, other.endPortalCenterLocation)
                && Objects.equals(bedSpawnLocation, other.bedSpawnLocation);
    }
}
