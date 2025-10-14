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
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that fires the {@code /locate structure} command, captures the response,
 * and caches the coordinate result. Designed to run passively each tick: call {@link #tick()}
 * and then {@link #getLocatedPosition()} to retrieve the most recent discovery if available.
 */
public class LocateStructureCommandHelper implements AutoCloseable {

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

    private final AltoClef mod;
    private final String structureId;
    private final String structureIdLower;
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

    public LocateStructureCommandHelper(AltoClef mod,
                                        String structureId,
                                        String responseToken,
                                        Dimension requiredDimension,
                                        double retryIntervalSeconds,
                                        double responseTimeoutSeconds) {
        this.mod = mod;
        this.structureId = structureId;
        this.structureIdLower = structureId == null ? null : structureId.toLowerCase(Locale.ROOT);
        this.responseTokenLower = responseToken == null ? null : responseToken.toLowerCase(Locale.ROOT);
        this.requiredDimension = requiredDimension;
        this.retryTimer = new TimerGame(retryIntervalSeconds);
        this.responseTimer = new TimerGame(responseTimeoutSeconds);
        this.subscription = EventBus.subscribe(ChatMessageEvent.class, this::handleChatMessage);
        this.cacheKey = new CacheKey(this.structureIdLower, this.requiredDimension);

        CachedLocate cached = CACHE.get(cacheKey);
        if (cached != null) {
            locatedPos = cached.pos();
            retryTimer.reset();
            Debug.logInternal("[LocateStructure] Using cached location for " + structureId + " at " + locatedPos.toShortString());
        } else {
            retryTimer.forceElapse();
        }
        responseTimer.forceElapse();
    }

    /**
     * Tick the helper. Call each game tick while the helper is in use. It will trigger a new locate command
     * when appropriate and handle response timeouts.
     */
    public void tick() {
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
                Debug.logInternal("[LocateStructure] Response timeout for " + structureId + ", will retry.");
            }
            return;
        }

        if (locatedPos == null && retryTimer.elapsed()) {
            sendLocateCommand();
        }
    }

    /**
     * @return {@code true} if this helper detected that the command cannot be used (permissions/disabled).
     */
    public boolean isUnsupported() {
        return unsupported;
    }

    /**
     * Returns the last chat message processed while awaiting a locate response. Useful for debugging.
     */
    public Optional<String> getLastMessage() {
        return Optional.ofNullable(lastMessage);
    }

    /**
     * Clears the cached structure location so the next {@link #tick()} call will immediately attempt another locate.
     */
    public void invalidateLocatedPosition() {
        locatedPos = null;
        retryTimer.forceElapse();
        CACHE.remove(cacheKey);
    }

    /**
     * @return the cached location if the locate command has responded successfully.
     */
    public Optional<BlockPos> getLocatedPosition() {
        return Optional.ofNullable(locatedPos);
    }

    private void sendLocateCommand() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        PlayerVer.sendChatCommand(player, "locate structure " + structureId);
        awaitingResponse = true;
        responseTimer.reset();
        retryTimer.reset();
        Debug.logInternal("[LocateStructure] Issued locate command for " + structureId);
    }

    private void handleChatMessage(ChatMessageEvent event) {
        if (!awaitingResponse) {
            return;
        }
        String message = event.messageContent();
        lastMessage = message;
        if (message == null) {
            return;
        }
        String sanitized = FORMAT_CODE_PATTERN.matcher(message).replaceAll("");
        String normalized = sanitized.toLowerCase(Locale.ROOT);

        for (String deny : PERMISSION_STRINGS) {
            if (normalized.contains(deny)) {
                unsupported = true;
                awaitingResponse = false;
                Debug.logWarning("[LocateStructure] Locate command unsupported: " + message);
                return;
            }
        }

        if (normalized.contains("no ") && normalized.contains("could") && normalized.contains("found")) {
            awaitingResponse = false;
            retryTimer.reset();
            Debug.logMessage("[LocateStructure] No structure found for " + structureId + ".");
            return;
        }

        boolean hasPrimaryCoords;
        boolean hasAltCoords;
        Matcher primaryMatcher = COORD_PATTERN.matcher(sanitized);
        hasPrimaryCoords = primaryMatcher.find();
        Matcher altMatcher = ALT_COORD_PATTERN.matcher(sanitized);
        hasAltCoords = altMatcher.find();

        boolean relevant = responseTokenLower == null || normalized.contains(responseTokenLower) || (structureIdLower != null && normalized.contains(structureIdLower));
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
            Debug.logMessage("[LocateStructure] Located " + structureId + " at " + locatedPos.toShortString());
            return;
        }

        if (hasAltCoords) {
            int x = Integer.parseInt(altMatcher.group(1));
            int z = Integer.parseInt(altMatcher.group(2));
            int y = determineY(null);
            locatedPos = new BlockPos(x, y, z);
            CACHE.put(cacheKey, new CachedLocate(locatedPos, System.currentTimeMillis()));
            awaitingResponse = false;
            Debug.logMessage("[LocateStructure] Located (alt format) " + structureId + " at " + locatedPos.toShortString());
        }
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

    private record CacheKey(String structureIdLower, Dimension dimension) {
    }

    private record CachedLocate(BlockPos pos, long timestamp) {
    }
}
