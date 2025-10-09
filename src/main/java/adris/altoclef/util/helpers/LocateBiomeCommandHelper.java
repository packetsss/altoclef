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
import java.util.Optional;
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
    private static final String[] PERMISSION_STRINGS = new String[]{
            "unknown or incomplete command",
            "you do not have permission",
            "you must be an operator",
            "is not enabled",
            "only players may use this command"
    };

    private final AltoClef mod;
    private final String biomeId;
    private final String responseTokenLower;
    private final Dimension requiredDimension;
    private final TimerGame retryTimer;
    private final TimerGame responseTimer;
    private final Subscription<ChatMessageEvent> subscription;

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
        this.responseTokenLower = responseToken == null ? null : responseToken.toLowerCase(Locale.ROOT);
        this.requiredDimension = requiredDimension;
        this.retryTimer = new TimerGame(retryIntervalSeconds);
        this.responseTimer = new TimerGame(responseTimeoutSeconds);
        this.subscription = EventBus.subscribe(ChatMessageEvent.class, this::handleChatMessage);
        retryTimer.forceElapse();
        responseTimer.forceElapse();
    }

    /**
     * Tick the helper. This should be called every game tick while the helper is in use.
     * It will trigger a new locate command when appropriate and handle timeouts.
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
        if (!awaitingResponse) {
            return;
        }
        String message = event.messageContent();
        lastMessage = message;
        if (message == null) {
            return;
        }
        String normalized = message.toLowerCase(Locale.ROOT);

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

        boolean relevant = responseTokenLower == null || normalized.contains(responseTokenLower) || normalized.contains(biomeId);
        if (!relevant) {
            return;
        }

        Matcher matcher = COORD_PATTERN.matcher(message);
        int x;
        int z;
        if (matcher.find()) {
            x = Integer.parseInt(matcher.group(1));
            String yCapture = matcher.group(2);
            z = Integer.parseInt(matcher.group(3));
            int y = determineY(yCapture);
            locatedPos = new BlockPos(x, y, z);
            awaitingResponse = false;
            Debug.logMessage("[LocateBiome] Located " + biomeId + " at " + locatedPos.toShortString());
            return;
        }

        matcher = ALT_COORD_PATTERN.matcher(message);
        if (matcher.find()) {
            x = Integer.parseInt(matcher.group(1));
            z = Integer.parseInt(matcher.group(2));
            int y = determineY(null);
            locatedPos = new BlockPos(x, y, z);
            awaitingResponse = false;
            Debug.logMessage("[LocateBiome] Located (alt format) " + biomeId + " at " + locatedPos.toShortString());
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
}
