package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that fires the {@code /locate biome} command, captures the response, and caches the coordinate result.
 * Designed to run passively each tick: call {@link #tick()} and then {@link #getLocatedPosition()} to
 * retrieve the most recently discovered position if one is available.
 */
public class LocateBiomeCommandHelper implements AutoCloseable {

    private static final Pattern COORD_PATTERN = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(?:~|(-?\\d+))\\s*,\\s*(-?\\d+)\\s*\\]");
    private static final Pattern ALT_COORD_PATTERN = Pattern.compile("x\\s*=\\s*(-?\\d+).+z\\s*=\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAT_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]", Pattern.CASE_INSENSITIVE);
    private static final String[] PERMISSION_STRINGS = new String[]{
            "unknown or incomplete command",
            "you do not have permission",
            "you must be an operator",
            "is not enabled",
            "only players may use this command"
    };

    private static final Map<CacheKey, CachedLocate> CACHE = new ConcurrentHashMap<>();
    private static int lastCacheWorldIdentity = Integer.MIN_VALUE;

    private final AltoClef mod;
    private final String biomeId;
    private final String biomeIdLower;
    private final String responseTokenLower;
    private final Dimension requiredDimension;
    private final TimerGame retryTimer;
    private final TimerGame responseTimer;
    private final Subscription<ChatMessageEvent> subscription;
    private final CacheKey cacheKey;

    private boolean awaitingResponse;
    private boolean unsupported;
    private BlockPos locatedPos;
    private String lastMessage;

    public LocateBiomeCommandHelper(AltoClef mod,
                                    String biomeId,
                                    String responseToken,
                                    Dimension requiredDimension,
                                    double retryIntervalSeconds,
                                    double responseTimeoutSeconds) {
        this.mod = mod;
        this.biomeId = biomeId;
        this.biomeIdLower = biomeId == null ? null : biomeId.toLowerCase(Locale.ROOT);
        this.responseTokenLower = responseToken == null ? null : responseToken.toLowerCase(Locale.ROOT);
        this.requiredDimension = requiredDimension;
        this.retryTimer = new TimerGame(retryIntervalSeconds);
        this.responseTimer = new TimerGame(responseTimeoutSeconds);
        this.subscription = EventBus.subscribe(ChatMessageEvent.class, this::handleChatMessage);
        this.cacheKey = new CacheKey(this.biomeIdLower, this.requiredDimension);

        synchronizeCacheWithCurrentWorld();

        CachedLocate cached = CACHE.get(cacheKey);
        if (cached != null) {
            locatedPos = cached.pos();
            retryTimer.reset();
            Debug.logInternal("[LocateBiome] Using cached location for " + biomeId + " at " + locatedPos.toShortString());
        } else {
            retryTimer.forceElapse();
        }
        responseTimer.forceElapse();
    }

    /**
     * Tick the helper. This should be called every game tick while the helper is in use.
     * It will trigger a new locate command when appropriate and handle timeouts.
     */
    public void tick() {
        boolean worldChanged = synchronizeCacheWithCurrentWorld();

        if (worldChanged) {
            if (locatedPos != null) {
                Debug.logInternal("[LocateBiome] Discarding cached " + biomeId + " location due to world/session change.");
            }
            locatedPos = null;
            awaitingResponse = false;
            retryTimer.forceElapse();
        }

        if (unsupported || !AltoClef.inGame()) {
            return;
        }

        if (requiredDimension != null && WorldHelper.getCurrentDimension() != requiredDimension) {
            awaitingResponse = false;
            return;
        }

        if (awaitingResponse) {
            if (responseTimer.elapsed()) {
                awaitingResponse = false;
                retryTimer.forceElapse();
                Debug.logInternal("[LocateBiome] Response timeout for " + biomeId + ", will retry.");
            }
            return;
        }

        if (locatedPos == null && retryTimer.elapsed()) {
            sendLocateCommand();
        }
    }

    /**
     * @return {@code true} if this helper detected that the command cannot be used (missing permission, disabled command, etc.).
     */
    public boolean isUnsupported() {
        return unsupported;
    }

    /**
     * Returns the last chat line that was processed while awaiting a locate response. Useful for diagnostics.
     */
    public Optional<String> getLastMessage() {
        return Optional.ofNullable(lastMessage);
    }

    /**
     * Clear the cached location so the next {@link #tick()} call will attempt a new locate request immediately.
     */
    public void invalidateLocatedPosition() {
        locatedPos = null;
        retryTimer.forceElapse();
        CACHE.remove(cacheKey);
    }

    /**
     * @return the cached position discovered via {@code /locate} if a result has been received.
     */
    public Optional<BlockPos> getLocatedPosition() {
        return Optional.ofNullable(locatedPos);
    }

    private void sendLocateCommand() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        PlayerVer.sendChatCommand(player, "locate biome " + biomeId);
        awaitingResponse = true;
        responseTimer.reset();
        retryTimer.reset();
        Debug.logInternal("[LocateBiome] Issued locate command for " + biomeId);
    }

    private void handleChatMessage(ChatMessageEvent event) {
        boolean worldChanged = synchronizeCacheWithCurrentWorld();
        if (worldChanged) {
            locatedPos = null;
            awaitingResponse = false;
            retryTimer.forceElapse();
        }

        String message = event.messageContent();
        lastMessage = message;
        if (message == null) {
            return;
        }

        String sanitized = FORMAT_CODE_PATTERN.matcher(message).replaceAll("");
        String normalized = sanitized.toLowerCase(Locale.ROOT);

        boolean isAwaiting = awaitingResponse;
        boolean mentionsBiomeId = biomeIdLower != null && normalized.contains(biomeIdLower);
        boolean mentionsToken = responseTokenLower != null && normalized.contains(responseTokenLower);

        Matcher primaryMatcher = COORD_PATTERN.matcher(sanitized);
        boolean hasPrimaryCoords = primaryMatcher.find();
        Matcher altMatcher = ALT_COORD_PATTERN.matcher(sanitized);
        boolean hasAltCoords = altMatcher.find();

        if (!isAwaiting && !mentionsBiomeId && !mentionsToken) {
            // Ignore unrelated messages unless we're actively awaiting a locate response.
            return;
        }

        if (isAwaiting) {
            for (String deny : PERMISSION_STRINGS) {
                if (normalized.contains(deny)) {
                    unsupported = true;
                    awaitingResponse = false;
                    Debug.logWarning("[LocateBiome] Locate command unsupported: " + message);
                    return;
                }
            }

            if (normalized.contains("no ") && normalized.contains("could") && normalized.contains("found")) {
                awaitingResponse = false;
                retryTimer.reset();
                Debug.logMessage("[LocateBiome] No biome found for " + biomeId + ".");
                return;
            }
        }

        boolean relevant = mentionsToken || mentionsBiomeId;
        if (!relevant && biomeIdLower != null) {
            // Some servers omit the namespace or replace separators, so fall back to more tolerant matching.
            String simplifiedId = biomeIdLower.replace(':', ' ').replace('_', ' ');
            relevant = normalized.contains(simplifiedId);
        }
        if (!relevant && (hasPrimaryCoords || hasAltCoords) && isAwaiting) {
            // When we're already waiting for a locate response, any coordinate-bearing message is likely ours.
            relevant = true;
        }
        if (!relevant) {
            return;
        }

        if (hasPrimaryCoords) {
            int x = Integer.parseInt(primaryMatcher.group(1));
            String yCapture = primaryMatcher.group(2);
            int z = Integer.parseInt(primaryMatcher.group(3));
            int y = determineY(yCapture);
            locatedPos = new BlockPos(x, y, z);
            CACHE.put(cacheKey, new CachedLocate(locatedPos, System.currentTimeMillis()));
            awaitingResponse = false;
            Debug.logMessage("[LocateBiome] Located " + biomeId + " at " + locatedPos.toShortString() + (isAwaiting ? "" : " (passive capture)"));
            return;
        }

        if (hasAltCoords) {
            int x = Integer.parseInt(altMatcher.group(1));
            int z = Integer.parseInt(altMatcher.group(2));
            int y = determineY(null);
            locatedPos = new BlockPos(x, y, z);
            CACHE.put(cacheKey, new CachedLocate(locatedPos, System.currentTimeMillis()));
            awaitingResponse = false;
            Debug.logMessage("[LocateBiome] Located (alt format) " + biomeId + " at " + locatedPos.toShortString() + (isAwaiting ? "" : " (passive capture)"));
        }
    }

    private static boolean synchronizeCacheWithCurrentWorld() {
        ClientWorld world = MinecraftClient.getInstance().world;
        int identity = world == null ? 0 : System.identityHashCode(world);
        if (identity != lastCacheWorldIdentity) {
            lastCacheWorldIdentity = identity;
            if (!CACHE.isEmpty()) {
                CACHE.clear();
                Debug.logInternal("[LocateBiome] Cleared cached biome locations due to world/session change.");
            }
            return true;
        }
        return false;
    }

    private int determineY(String capturedY) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (capturedY != null && !capturedY.isEmpty() && !capturedY.equals("~")) {
            try {
                return Integer.parseInt(capturedY);
            } catch (NumberFormatException ignored) {
            }
        }
        if (player != null) {
            return player.getBlockY();
        }
        return 64;
    }

    @Override
    public void close() {
        EventBus.unsubscribe(subscription);
    }

    private record CacheKey(String biomeIdLower, Dimension dimension) {
    }

    private record CachedLocate(BlockPos pos, long timestamp) {
    }
}
