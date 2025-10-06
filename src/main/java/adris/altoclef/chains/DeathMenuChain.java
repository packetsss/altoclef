package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.mixins.DeathScreenAccessor;
import adris.altoclef.multiversion.ConnectScreenVer;
import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.multiplayer.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class DeathMenuChain extends TaskChain {

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


    public DeathMenuChain(TaskRunner runner) {
        super(runner);
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
            AltoClef mod = AltoClef.getInstance();

            if (waitOnDeathScreenBeforeRespawnTimer.elapsed()) {
                waitOnDeathScreenBeforeRespawnTimer.reset();
                if (mod.getModSettings().isRandomRespawnEnabled()) {
                    queueRandomRespawn(mod);
                } else {
                    clearRandomRespawnState();
                }
                if (shouldAutoRespawn()) {
                    deathCount++;
                    Debug.logMessage("RESPAWNING... (this is death #" + deathCount + ")");
                    assert MinecraftClient.getInstance().player != null;
                    Text screenMessage = ((DeathScreenAccessor) screen).getMessage();
                    String deathMessage = screenMessage != null ? screenMessage.getString() : "Unknown"; //"(not implemented yet)"; //screen.children().toString();
                    MinecraftClient.getInstance().player.requestRespawn();
                    MinecraftClient.getInstance().setScreen(null);
                    for (String i : mod.getModSettings().getDeathCommand().split(" & ")) {
                        String command = i.replace("{deathmessage}", deathMessage);
                        String prefix = mod.getModSettings().getCommandPrefix();
                        while (MinecraftClient.getInstance().player.isAlive()) ;
                        if (!command.isEmpty()) {
                            if (command.startsWith(prefix)) {
                                AltoClef.getCommandExecutor().execute(command, () -> {
                                }, Throwable::printStackTrace);
                            } else if (command.startsWith("/")) {
                                PlayerVer.sendChatCommand(MinecraftClient.getInstance().player, command.substring(1));
                            } else {
                                PlayerVer.sendChatMessage(MinecraftClient.getInstance().player, command);
                            }
                        }
                    }
                } else {
                    // Cancel if we die and are not auto-respawning.
                    mod.cancelUserTask();
                }
            }
        } else {
            if (AltoClef.inGame()) {
                waitOnDeathScreenBeforeRespawnTimer.reset();
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
}
