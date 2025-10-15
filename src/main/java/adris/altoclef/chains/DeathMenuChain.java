package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.mixins.DeathScreenAccessor;
import adris.altoclef.multiversion.ConnectScreenVer;
import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.tasks.speedrun.beatgame.BeatMinecraftTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.multiplayer.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

public class DeathMenuChain extends TaskChain {

    private static final int DEATH_CHAT_MESSAGE_TIMEOUT_TICKS = 20 * 15;
    private static final String[] DEATH_MESSAGE_KEYWORDS = new String[]{
            " died",
            " slain",
            " shot",
            " fell",
            " hit the ground",
            " tried to swim",
            " drowned",
            " blew up",
            " was blown up",
            " burned",
            " burnt",
            " went up in flames",
            " suffocated",
            " starved",
            " was killed",
            " killed by",
            " was pricked",
            " walked into a cactus",
            " experienced kinetic energy",
            " was squished",
            " was pummeled",
            " fell out of the world",
            " didn't want to live",
            " was roasted",
            " was struck by lightning",
            " was impaled",
            " was doomed",
            " withered",
            " was obliterated",
            " magic",
            " was slain by",
            " was shot by"
    };
    private static final Pattern FORMATTING_CODE_PATTERN = Pattern.compile("\u00A7[0-9A-FK-ORa-fk-or]");

    // Sometimes we fuck up, so we might want to retry considering the death screen.
    private final TimerReal deathRetryTimer = new TimerReal(8);
    private final TimerGame reconnectTimer = new TimerGame(1);
    private final TimerGame waitOnDeathScreenBeforeRespawnTimer = new TimerGame(2);
    private final TimerGame randomRespawnCommandTimeout = new TimerGame(12);
    private ServerInfo prevServerEntry = null;
    private boolean reconnecting = false;
    private int deathCount = 0;
    private Class<? extends Screen> prevScreen = null;
    private final Random random = new Random();
    private boolean randomRespawnQueued = false;
    private boolean randomRespawnAttempted = false;
    private boolean randomRespawnWarnedNoServer = false;
    private boolean randomRespawnUsingServer = false;
    private boolean randomRespawnCommandActive = false;
    private boolean randomRespawnAwaitingSetSpawn = false;
    private int randomRespawnTargetX;
    private int randomRespawnTargetZ;
    private int randomRespawnSpreadCenterX;
    private int randomRespawnSpreadCenterZ;
    private int randomRespawnSpreadRange;
    private int randomRespawnSpreadMinSeparation;
    private double randomRespawnQueuedRadius;
    private double randomRespawnQueuedAngleDeg;
    private Vec3d randomRespawnSpreadOrigin = Vec3d.ZERO;
    private boolean deathContextLogged = false;
    private boolean shouldLogNextRespawn = false;
    private boolean restartBeatTaskAfterRespawn = false;
    private boolean pendingDeathWithoutScreen = false;
    private boolean limboRestartPending = false;
    private final Deque<String> pendingDeathCommands = new ArrayDeque<>();
    private final Subscription<ChatMessageEvent> chatSubscription;
    private String recentDeathChatMessage = null;
    private long recentDeathChatTick = -1L;
    private RegistryKey<World> lastKnownWorldKey = null;
    private PlayerStateSnapshot lastAliveSnapshot = null;


    public DeathMenuChain(TaskRunner runner) {
        super(runner);
        chatSubscription = EventBus.subscribe(ChatMessageEvent.class, this::handleChatMessage);
    }

    private boolean shouldAutoRespawn() {
        return AltoClef.getInstance().getModSettings().isAutoRespawn();
    }

    private boolean shouldAutoReconnect() {
        return AltoClef.getInstance().getModSettings().isAutoReconnect();
    }

    @Override
    protected void onStop() {

    }

    @Override
    public void onInterrupt(TaskChain other) {

    }

    @Override
    protected void onTick() {

    }

    @Override
    public float getPriority() {
        processPendingDeathCommands();
        expireStaleDeathChatMessage();
        if (randomRespawnQueued && !randomRespawnAttempted && AltoClef.inGame()) {
            attemptRandomRespawnTeleport();
        }
        if (randomRespawnCommandActive && AltoClef.inGame()) {
            ClientPlayerEntity clientPlayer = MinecraftClient.getInstance().player;
            if (clientPlayer != null) {
                handleRandomRespawnCommandFollowup(clientPlayer);
            }
        }
        //MinecraftClient.getInstance().getCurrentServerEntry().address;
//        MinecraftClient.getInstance().
        Screen screen = MinecraftClient.getInstance().currentScreen;
        AltoClef modInstance = AltoClef.getInstance();
        ClientPlayerEntity clientPlayer = MinecraftClient.getInstance().player;

        if (modInstance != null && clientPlayer != null) {
            updateLastAliveSnapshot(modInstance, clientPlayer);
        }

        monitorSilentDeathIndicators(modInstance, clientPlayer);

        if (modInstance != null && clientPlayer != null && !(screen instanceof DeathScreen)
                && isPlayerCurrentlyDead(clientPlayer) && !deathContextLogged) {
            String deathMessage = resolveImmediateDeathMessage(clientPlayer);
            handleImmediateDeath(modInstance, clientPlayer, deathMessage, lastAliveSnapshot);
        }

        // This might fix Weird fail to respawn that happened only once
        if (prevScreen == DeathScreen.class) {
            if (deathRetryTimer.elapsed()) {
                Debug.logMessage("(RESPAWN RETRY WEIRD FIX...)");
                deathRetryTimer.reset();
                prevScreen = null;
            }
        } else {
            deathRetryTimer.reset();
        }
        // Keep track of the last server we were on so we can re-connect.
        if (AltoClef.inGame()) {
            prevServerEntry = MinecraftClient.getInstance().getCurrentServerEntry();
        }

        if (screen instanceof DeathScreen) {
            AltoClef mod = modInstance;
            if (mod == null) return Float.NEGATIVE_INFINITY;

            if (waitOnDeathScreenBeforeRespawnTimer.elapsed()) {
                waitOnDeathScreenBeforeRespawnTimer.reset();
                if (mod.getModSettings().isRandomRespawnEnabled()) {
                    queueRandomRespawn(mod);
                } else {
                    clearRandomRespawnState();
                }
                Text screenMessage = ((DeathScreenAccessor) screen).getMessage();
                String deathMessage = screenMessage != null ? screenMessage.getString() : "Unknown";
                deathMessage = resolveDeathMessageWithChatFallback(clientPlayer, deathMessage);
                if (!deathContextLogged) {
                    logDeathSnapshot(mod, deathCount + 1, deathMessage, lastAliveSnapshot);
                    deathContextLogged = true;
                }
                if (shouldAutoRespawn()) {
                    if (!pendingDeathWithoutScreen) {
                        deathCount++;
                    }
                    Debug.logMessage("RESPAWNING... (this is death #" + deathCount + ")");
                    assert MinecraftClient.getInstance().player != null;
                    Task currentTask = mod.getUserTaskChain() != null ? mod.getUserTaskChain().getCurrentTask() : null;
                    restartBeatTaskAfterRespawn = currentTask instanceof BeatMinecraftTask;
                    MinecraftClient.getInstance().player.requestRespawn();
                    shouldLogNextRespawn = true;
                    MinecraftClient.getInstance().setScreen(null);
                    enqueueDeathCommands(mod, deathMessage);
                    pendingDeathWithoutScreen = false;
                } else {
                    // Cancel if we die and are not auto-respawning.
                    mod.cancelUserTask();
                }
            }
        } else {
            if (AltoClef.inGame()) {
                AltoClef mod = modInstance;
                waitOnDeathScreenBeforeRespawnTimer.reset();
                if (shouldLogNextRespawn && mod != null) {
                    logRespawnLanding(mod.getPlayer(), "normal");
                    shouldLogNextRespawn = false;
                    if (limboRestartPending && mod.getWorld() != null && !isLimboDimension(mod.getWorld().getRegistryKey())) {
                        resetGamerTaskChain(mod);
                        limboRestartPending = false;
                        restartBeatTaskAfterRespawn = false;
                    }
                    if (restartBeatTaskAfterRespawn) {
                        restartBeatTaskAfterRespawn = false;
                        Debug.logMessage("[Death] Restarting BeatMinecraftTask after respawn", false);
                        mod.runUserTask(new BeatMinecraftTask(mod));
                    }
                }
            }
            if (clientPlayer == null || !isPlayerCurrentlyDead(clientPlayer)) {
                deathContextLogged = false;
                pendingDeathWithoutScreen = false;
            }
            if (clientPlayer == null) {
                lastKnownWorldKey = null;
            }
            if (screen instanceof DisconnectedScreen) {
                if (shouldAutoReconnect()) {
                    Debug.logMessage("RECONNECTING: Going to Multiplayer Screen");
                    reconnecting = true;
                    MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
                } else {
                    // Cancel if we disconnect and are not auto-reconnecting.
                    AltoClef.getInstance().cancelUserTask();
                }
            } else if (screen instanceof MultiplayerScreen && reconnecting && reconnectTimer.elapsed()) {
                reconnectTimer.reset();
                Debug.logMessage("RECONNECTING: Going ");
                reconnecting = false;

                if (prevServerEntry == null) {
                    Debug.logWarning("Failed to re-connect to server, no server entry cached.");
                } else {
                    MinecraftClient client = MinecraftClient.getInstance();
                    ConnectScreenVer.connect(screen, client, ServerAddress.parse(prevServerEntry.address), prevServerEntry, false);
                    //ConnectScreen.connect(screen, client, ServerAddress.parse(_prevServerEntry.address), _prevServerEntry);
                    //client.setScreen(new ConnectScreen(screen, client, _prevServerEntry));
                }
            }
        }
        if (screen != null)
            prevScreen = screen.getClass();
        return Float.NEGATIVE_INFINITY;
    }

    private void processPendingDeathCommands() {
        if (pendingDeathCommands.isEmpty() || !AltoClef.inGame()) {
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || !player.isAlive()) {
            return;
        }
        String prefix = AltoClef.getInstance().getModSettings().getCommandPrefix();
        while (!pendingDeathCommands.isEmpty()) {
            String command = pendingDeathCommands.pollFirst();
            if (command == null || command.isEmpty()) {
                continue;
            }
            if (command.startsWith(prefix)) {
                AltoClef.getCommandExecutor().execute(command, () -> {
                }, Throwable::printStackTrace);
            } else if (command.startsWith("/")) {
                PlayerVer.sendChatCommand(player, command.substring(1));
            } else {
                PlayerVer.sendChatMessage(player, command);
            }
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Death Menu Respawn Handling";
    }

    private void queueRandomRespawn(AltoClef mod) {
        if (randomRespawnQueued) {
            return;
        }
        int min = mod.getModSettings().getRandomRespawnMinRadius();
        int max = mod.getModSettings().getRandomRespawnMaxRadius();
        if (max < min) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        min = Math.max(0, min);
        max = Math.max(min + 1, max);
        double radius = min + random.nextDouble(max - min);
        double angle = random.nextDouble() * Math.PI * 2.0;
        randomRespawnQueuedRadius = radius;
        randomRespawnQueuedAngleDeg = Math.toDegrees(angle);

        randomRespawnUsingServer = MinecraftClient.getInstance().isIntegratedServerRunning();
        if (randomRespawnUsingServer) {
            randomRespawnTargetX = MathHelper.floor(Math.cos(angle) * radius);
            randomRespawnTargetZ = MathHelper.floor(Math.sin(angle) * radius);
        } else {
            int centerRadius = MathHelper.floor(radius);
            randomRespawnSpreadCenterX = MathHelper.floor(Math.cos(angle) * centerRadius);
            randomRespawnSpreadCenterZ = MathHelper.floor(Math.sin(angle) * centerRadius);
            int rangeEstimate = Math.max(64, Math.min(1024, (max - min) / 2));
            randomRespawnSpreadRange = Math.max(32, rangeEstimate);
            randomRespawnSpreadMinSeparation = Math.max(16, Math.min(randomRespawnSpreadRange, min / 2));
        }
        randomRespawnQueued = true;
        randomRespawnAttempted = false;
        randomRespawnWarnedNoServer = false;
        randomRespawnCommandActive = false;
        randomRespawnAwaitingSetSpawn = false;
        randomRespawnSpreadOrigin = Vec3d.ZERO;
        if (randomRespawnUsingServer) {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[RandomRespawn] Queued target ~%.0f blocks @ %.0f° -> (%d, %d)",
                    radius,
                    randomRespawnQueuedAngleDeg,
                    randomRespawnTargetX,
                    randomRespawnTargetZ), false);
        } else {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[RandomRespawn] Queued remote spread center ~%.0f blocks @ %.0f° -> (%d, %d) range=%d",
                    radius,
                    randomRespawnQueuedAngleDeg,
                    randomRespawnSpreadCenterX,
                    randomRespawnSpreadCenterZ,
                    randomRespawnSpreadRange), false);
        }
    }

    private void attemptRandomRespawnTeleport() {
        ClientPlayerEntity clientPlayer = MinecraftClient.getInstance().player;
        if (clientPlayer == null || !clientPlayer.isAlive()) {
            return;
        }
        randomRespawnAttempted = true;

        if (randomRespawnUsingServer) {
            MinecraftServer server = MinecraftClient.getInstance().getServer();
            if (server == null) {
                if (!randomRespawnWarnedNoServer) {
                    Debug.logWarning("Random respawn requested but no integrated server is running; falling back to command dispatcher.");
                    randomRespawnWarnedNoServer = true;
                }
                executeRandomRespawnCommands(clientPlayer);
                return;
            }

            UUID playerUuid = clientPlayer.getUuid();
            final int targetX = randomRespawnTargetX;
            final int targetZ = randomRespawnTargetZ;
            final double loggedRadius = randomRespawnQueuedRadius;
            final double loggedAngle = randomRespawnQueuedAngleDeg;
            final float yaw = clientPlayer.getYaw();
            final float pitch = clientPlayer.getPitch();

            server.execute(() -> {
                ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(playerUuid);
                if (serverPlayer == null) {
                    Debug.logWarning("Random respawn failed: server-side player entity not found.");
                    return;
                }
                ServerWorld targetWorld = server.getOverworld();
                if (targetWorld == null) {
                    Debug.logWarning("Random respawn failed: overworld dimension unavailable.");
                    return;
                }
                BlockPos safePos = findSafeRespawnPosition(targetWorld, targetX, targetZ);
                serverPlayer.teleport(targetWorld, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5, yaw, pitch);
                serverPlayer.setSpawnPoint(targetWorld.getRegistryKey(), safePos, 0.0f, true, false);
                Debug.logMessage(String.format(Locale.ROOT,
                        "[RandomRespawn] Relocated spawn to %s (r=%.0f, θ=%.0f°)",
                        safePos.toShortString(),
                        loggedRadius,
                        loggedAngle), false);
                Debug.logMessage(String.format(Locale.ROOT,
                        "[Death] Respawn landing via integrated teleport pos=%s dimension=%s health=%.1f hunger=%d worldTime=%d",
                        safePos.toShortString(),
                        targetWorld.getRegistryKey().getValue(),
                        serverPlayer.getHealth(),
                        serverPlayer.getHungerManager().getFoodLevel(),
                        targetWorld.getTime()), false);
                shouldLogNextRespawn = false;
            });

            clearRandomRespawnState();
        } else {
            executeRandomRespawnCommands(clientPlayer);
        }
    }

    private void executeRandomRespawnCommands(ClientPlayerEntity player) {
        randomRespawnQueued = false;
        randomRespawnAttempted = true;
        randomRespawnCommandActive = true;
        randomRespawnAwaitingSetSpawn = true;
        randomRespawnSpreadOrigin = player.getPos();
        randomRespawnCommandTimeout.reset();

        int separation = Math.max(16, randomRespawnSpreadMinSeparation);
        int range = Math.max(separation + 32, randomRespawnSpreadRange);
        String targetName = player.getGameProfile().getName();
        String spreadCommand = String.format(Locale.ROOT,
                "spreadplayers %d %d %d %d false %s",
                randomRespawnSpreadCenterX,
                randomRespawnSpreadCenterZ,
                separation,
                range,
                targetName);
        PlayerVer.sendChatCommand(player, spreadCommand);
        Debug.logMessage(String.format(Locale.ROOT,
                "[RandomRespawn] Executed spreadplayers center=(%d,%d) separation=%d range=%d",
                randomRespawnSpreadCenterX,
                randomRespawnSpreadCenterZ,
                separation,
                range), false);
    }

    private void handleRandomRespawnCommandFollowup(ClientPlayerEntity player) {
        if (!randomRespawnCommandActive) {
            return;
        }
        if (randomRespawnAwaitingSetSpawn) {
            double distanceSq = player.getPos().squaredDistanceTo(randomRespawnSpreadOrigin);
            if (distanceSq > 36) {
                logRespawnLanding(player, "spreadplayers");
                shouldLogNextRespawn = false;
                PlayerVer.sendChatCommand(player, "setworldspawn ~ ~ ~");
                Debug.logMessage(String.format(Locale.ROOT,
                        "[RandomRespawn] Set world spawn to (%d, %d, %d) (r=%.0f, θ=%.0f°)",
                        player.getBlockX(),
                        MathHelper.floor(player.getY()),
                        player.getBlockZ(),
                        randomRespawnQueuedRadius,
                        randomRespawnQueuedAngleDeg), false);
                randomRespawnAwaitingSetSpawn = false;
                randomRespawnCommandActive = false;
                clearRandomRespawnState();
                return;
            }
            if (randomRespawnCommandTimeout.elapsed()) {
                Debug.logWarning("Random respawn spreadplayers command appears to have stalled; keeping current spawn location.");
                randomRespawnAwaitingSetSpawn = false;
                randomRespawnCommandActive = false;
                clearRandomRespawnState();
            }
        }
    }

    private void clearRandomRespawnState() {
        randomRespawnQueued = false;
        randomRespawnAttempted = false;
        randomRespawnWarnedNoServer = false;
        randomRespawnUsingServer = false;
        randomRespawnCommandActive = false;
        randomRespawnAwaitingSetSpawn = false;
        randomRespawnSpreadOrigin = Vec3d.ZERO;
        randomRespawnCommandTimeout.forceElapse();
    }

    private boolean isPlayerCurrentlyDead(ClientPlayerEntity player) {
        return player != null && (!player.isAlive() || player.getHealth() <= 0.0f);
    }

    private String resolveImmediateDeathMessage(ClientPlayerEntity player) {
        if (player == null) {
            return "You died";
        }
        DamageSource source = player.getRecentDamageSource();
        if (source != null) {
            Text message = source.getDeathMessage(player);
            if (message != null) {
                return message.getString();
            }
        }
        return player.getName().getString() + " died";
    }

    private void enqueueDeathCommands(AltoClef mod, String deathMessage) {
        if (mod == null) return;
        String configured = mod.getModSettings().getDeathCommand();
        if (configured == null || configured.isEmpty()) return;
        for (String entry : configured.split(" & ")) {
            String command = entry.replace("{deathmessage}", deathMessage);
            if (!command.isEmpty()) {
                pendingDeathCommands.add(command);
            }
        }
    }

    private void handleChatMessage(ChatMessageEvent event) {
        String raw = event.messageContent();
        if (raw == null) {
            return;
        }
        String normalized = cleanChatMessage(raw);
        if (normalized.isEmpty()) {
            return;
        }
        AltoClef mod = AltoClef.getInstance();
        ClientPlayerEntity clientPlayer = MinecraftClient.getInstance().player;
        String lowered = normalized.toLowerCase(Locale.ROOT);

        if (isLikelyDeathMessageForPlayer(lowered, normalized, clientPlayer)) {
            cacheRecentDeathChatMessage(normalized);
        }

        if (!lowered.contains("baritone") || !lowered.contains("death position saved")) {
            return;
        }
        if (mod == null || clientPlayer == null) {
            return;
        }
        if (deathContextLogged) {
            return;
        }
        Debug.logMessage("[Death] Detected Baritone death marker; forcing snapshot capture.", false);
        String deathMessage = resolveImmediateDeathMessage(clientPlayer);
        handleImmediateDeath(mod, clientPlayer, deathMessage, lastAliveSnapshot);
    }

    private void handleImmediateDeath(AltoClef mod, ClientPlayerEntity clientPlayer, String deathMessage, PlayerStateSnapshot snapshot) {
        if (mod == null || clientPlayer == null) {
            return;
        }
        if (deathContextLogged) {
            return;
        }
        PlayerStateSnapshot effectiveSnapshot = snapshot != null ? snapshot : lastAliveSnapshot;
        if (effectiveSnapshot == null) {
            effectiveSnapshot = capturePlayerSnapshot(mod, clientPlayer);
        }
        String resolvedMessage = resolveDeathMessageWithChatFallback(clientPlayer, deathMessage);
        logDeathSnapshot(mod, deathCount + 1, resolvedMessage, effectiveSnapshot);
        lastAliveSnapshot = null;
        deathContextLogged = true;
        pendingDeathWithoutScreen = true;
        deathCount++;
        shouldLogNextRespawn = true;
        Task userTask = mod.getUserTaskChain() != null ? mod.getUserTaskChain().getCurrentTask() : null;
        restartBeatTaskAfterRespawn = userTask instanceof BeatMinecraftTask;
        if (shouldAutoRespawn()) {
            clientPlayer.requestRespawn();
            MinecraftClient.getInstance().setScreen(null);
            enqueueDeathCommands(mod, resolvedMessage);
        } else {
            mod.cancelUserTask();
        }
    }

    public void notifyDeathFromClient(DamageSource source) {
        AltoClef mod = AltoClef.getInstance();
        ClientPlayerEntity clientPlayer = MinecraftClient.getInstance().player;
        if (mod == null || clientPlayer == null) {
            return;
        }
        Text message = source != null ? source.getDeathMessage(clientPlayer) : null;
        String deathMessage = message != null ? message.getString() : null;
        PlayerStateSnapshot snapshot = capturePlayerSnapshot(mod, clientPlayer);
        if (snapshot != null) {
            lastAliveSnapshot = snapshot;
        }
        handleImmediateDeath(mod, clientPlayer, deathMessage, snapshot);
    }

    private void logDeathSnapshot(AltoClef mod, int deathNumber, String deathMessage, PlayerStateSnapshot snapshot) {
        if (mod == null) return;
        PlayerStateSnapshot effective = snapshot;
        if (effective == null) {
            effective = capturePlayerSnapshot(mod, mod.getPlayer());
        }
        Vec3d pos = effective != null ? effective.position : Vec3d.ZERO;
        String posString = effective != null ? effective.blockPosShort : "<unknown>";
        String dimension = effective != null ? effective.dimension : (mod.getWorld() != null ? mod.getWorld().getRegistryKey().getValue().toString() : "<unknown>");
        float health = effective != null ? effective.health : Float.NaN;
        float absorption = effective != null ? effective.absorption : Float.NaN;
        int hunger = effective != null ? effective.hunger : -1;
        float saturation = effective != null ? effective.saturation : Float.NaN;
        int armor = effective != null ? effective.armor : -1;
        TaskRunner runner = mod.getTaskRunner();
        TaskChain currentChain = runner != null ? runner.getCurrentTaskChain() : null;
        String chainName = currentChain != null ? currentChain.getName() : "<none>";
        String chainContext = currentChain != null ? currentChain.getDebugContext() : "<none>";
        Task userTask = mod.getUserTaskChain() != null ? mod.getUserTaskChain().getCurrentTask() : null;
        String userTaskInfo = userTask != null ? userTask.getClass().getSimpleName() + ":" + userTask : "<none>";
        String runnerStatus = runner != null ? runner.statusReport.trim() : "<unknown>";
        Debug.logMessage(String.format(Locale.ROOT,
                "[Death] Snapshot #%d message=\"%s\" pos=%s (%.2f, %.2f, %.2f) dimension=%s health=%.1f+%.1f hunger=%d saturation=%.1f armor=%d chain=%s context=%s runner=%s userTask=%s",
                deathNumber,
                deathMessage,
                posString,
                pos.x,
                pos.y,
                pos.z,
                dimension,
                health,
                absorption,
                hunger,
                saturation,
                armor,
                chainName,
                chainContext,
                runnerStatus,
                userTaskInfo), false);
        if (mod.getDeathLogManager() != null) {
            mod.getDeathLogManager().recordDeath(deathNumber, deathMessage);
        }
    }

    private void updateLastAliveSnapshot(AltoClef mod, ClientPlayerEntity player) {
        if (mod == null || player == null) {
            return;
        }
        if (!player.isAlive() || player.getHealth() <= 0.0f) {
            return;
        }
        lastAliveSnapshot = capturePlayerSnapshot(mod, player);
    }

    private PlayerStateSnapshot capturePlayerSnapshot(AltoClef mod, ClientPlayerEntity player) {
        if (mod == null || player == null) {
            return null;
        }
        Vec3d pos = player.getPos();
        Vec3d positionCopy = pos != null ? new Vec3d(pos.x, pos.y, pos.z) : Vec3d.ZERO;
        BlockPos blockPos = player.getBlockPos();
        float health = player.getHealth();
        float absorption = player.getAbsorptionAmount();
        int hunger = player.getHungerManager().getFoodLevel();
        float saturation = player.getHungerManager().getSaturationLevel();
        int armor = player.getArmor();
        World world = mod.getWorld();
        if (world == null) {
            world = player.getWorld();
        }
        String dimension = world != null && world.getRegistryKey() != null ? world.getRegistryKey().getValue().toString() : "<unknown>";
        return new PlayerStateSnapshot(positionCopy, blockPos, dimension, health, absorption, hunger, saturation, armor);
    }

    private String cleanChatMessage(String raw) {
        String stripped = FORMATTING_CODE_PATTERN.matcher(raw).replaceAll("");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    private boolean isLikelyDeathMessageForPlayer(String lowered, String normalized, ClientPlayerEntity clientPlayer) {
        if (lowered.contains("you died")) {
            return true;
        }
        if (clientPlayer == null) {
            return false;
        }
        String playerName = clientPlayer.getName() != null ? clientPlayer.getName().getString() : null;
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        String loweredName = playerName.toLowerCase(Locale.ROOT);
        if (!lowered.contains(loweredName)) {
            return false;
        }
        for (String keyword : DEATH_MESSAGE_KEYWORDS) {
            if (lowered.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void cacheRecentDeathChatMessage(String message) {
        recentDeathChatMessage = message;
        recentDeathChatTick = WorldHelper.getTicks();
    }

    private void expireStaleDeathChatMessage() {
        if (recentDeathChatMessage == null) {
            return;
        }
        long currentTick = WorldHelper.getTicks();
        if (recentDeathChatTick < 0L || currentTick - recentDeathChatTick > DEATH_CHAT_MESSAGE_TIMEOUT_TICKS) {
            recentDeathChatMessage = null;
            recentDeathChatTick = -1L;
        }
    }

    private String pollRecentDeathChatMessage() {
        expireStaleDeathChatMessage();
        if (recentDeathChatMessage == null) {
            return null;
        }
        String message = recentDeathChatMessage;
        recentDeathChatMessage = null;
        recentDeathChatTick = -1L;
        return message;
    }

    private boolean isGenericDeathMessage(String message, ClientPlayerEntity player) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String lowered = message.toLowerCase(Locale.ROOT);
        if (lowered.contains("dimension change")) {
            return true;
        }
        if (lowered.equals("you died") || lowered.equals("you died!")) {
            return true;
        }
        if (player != null) {
            String name = player.getName() != null ? player.getName().getString() : null;
            if (name != null && !name.isBlank()) {
                String loweredName = name.toLowerCase(Locale.ROOT);
                if (lowered.startsWith(loweredName + " died")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String resolveDeathMessageWithChatFallback(ClientPlayerEntity player, String candidate) {
        String sanitized = candidate != null ? candidate.trim() : "";
        if (!sanitized.isEmpty() && !isGenericDeathMessage(sanitized, player)) {
            return sanitized;
        }
        String fromChat = pollRecentDeathChatMessage();
        if (fromChat != null && !fromChat.isBlank()) {
            return fromChat;
        }
        if (!sanitized.isEmpty()) {
            return sanitized;
        }
        return resolveImmediateDeathMessage(player);
    }

    private void logRespawnLanding(ClientPlayerEntity player, String mode) {
        if (player == null) {
            Debug.logMessage(String.format(Locale.ROOT, "[Death] Respawn landing via %s could not be logged (player missing)", mode), false);
            return;
        }
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) {
            mod.resetAfterDeath();
        }
        Vec3d pos = player.getPos();
        String dimension = mod.getWorld() != null ? mod.getWorld().getRegistryKey().getValue().toString() : "<unknown>";
        Debug.logMessage(String.format(Locale.ROOT,
                "[Death] Respawn landing via %s pos=%s (%.2f, %.2f, %.2f) dimension=%s health=%.1f hunger=%d saturation=%.1f armor=%d",
                mode,
                player.getBlockPos().toShortString(),
                pos.x,
                pos.y,
                pos.z,
                dimension,
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel(),
                player.getArmor()), false);
    }

    private BlockPos findSafeRespawnPosition(ServerWorld world, int x, int z) {
        BlockPos surface = getSurfaceSpawn(world, x, z);
        if (isSafeRespawnSpot(world, surface)) {
            return surface;
        }
        final int searchStep = 4;
        final int maxRadius = 64;
        for (int radius = searchStep; radius <= maxRadius; radius += searchStep) {
            for (int dx = -radius; dx <= radius; dx += searchStep) {
                for (int dz = -radius; dz <= radius; dz += searchStep) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    BlockPos candidate = getSurfaceSpawn(world, x + dx, z + dz);
                    if (isSafeRespawnSpot(world, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return surface;
    }

    private BlockPos getSurfaceSpawn(ServerWorld world, int x, int z) {
        world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (topY < world.getBottomY() + 2) {
            topY = world.getSeaLevel() + 1;
        }
        return new BlockPos(x, topY, z);
    }

    private boolean isSafeRespawnSpot(ServerWorld world, BlockPos pos) {
        BlockPos floorPos = pos.down();
        BlockState floorState = world.getBlockState(floorPos);
        if (!floorState.isSolidBlock(world, floorPos) || floorState.getCollisionShape(world, floorPos).isEmpty()) {
            return false;
        }
        BlockState feetState = world.getBlockState(pos);
        BlockState headState = world.getBlockState(pos.up());
        if (!feetState.getCollisionShape(world, pos).isEmpty() || !headState.getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        return feetState.getFluidState().isEmpty() && headState.getFluidState().isEmpty();
    }

    private void monitorSilentDeathIndicators(AltoClef mod, ClientPlayerEntity player) {
        if (!AltoClef.inGame() || mod == null || player == null) {
            lastKnownWorldKey = null;
            return;
        }
        ClientWorld world = mod.getWorld();
        if (world == null) {
            return;
        }

        RegistryKey<World> currentKey = world.getRegistryKey();
        if (currentKey != null) {
            if (lastKnownWorldKey != null && !currentKey.equals(lastKnownWorldKey) && isLimboDimension(currentKey) && !deathContextLogged) {
                limboRestartPending = true;
                String baseMessage = resolveImmediateDeathMessage(player);
                String formatted = String.format(Locale.ROOT,
                        "%s [dimension change %s -> %s]",
                        baseMessage,
                        lastKnownWorldKey.getValue(),
                        currentKey.getValue());
                handleImmediateDeath(mod, player, formatted, lastAliveSnapshot);
            }
            lastKnownWorldKey = currentKey;
        }
    }

    private boolean isLimboDimension(RegistryKey<World> key) {
        return key != null && "fahare:limbo".equals(key.getValue().toString());
    }

    private void resetGamerTaskChain(AltoClef mod) {
        CommandExecutor executor = AltoClef.getCommandExecutor();
        if (executor == null) {
            Debug.logWarning("[Death] Unable to reset @gamer task chain after Limbo respawn: command executor unavailable.");
            return;
        }
        Debug.logMessage("[Death] Resetting @gamer task chain after Limbo respawn.", false);
        mod.stopTasks();
        executor.executeWithPrefix("gamer");
    }

    private static final class PlayerStateSnapshot {
        private final Vec3d position;
        private final String blockPosShort;
        private final String dimension;
        private final float health;
        private final float absorption;
        private final int hunger;
        private final float saturation;
        private final int armor;

        private PlayerStateSnapshot(Vec3d position, BlockPos blockPos, String dimension, float health, float absorption, int hunger, float saturation, int armor) {
            this.position = position;
            this.blockPosShort = blockPos != null ? blockPos.toShortString() : "<unknown>";
            this.dimension = dimension;
            this.health = health;
            this.absorption = absorption;
            this.hunger = hunger;
            this.saturation = saturation;
            this.armor = armor;
        }
    }
}
