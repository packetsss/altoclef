package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.cambridge.CamBridge;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Locale;

public class WorldBootstrapChain extends SingleTaskChain {

    private static final double CHECK_INTERVAL_SECONDS = 0.35;
    private static final double LOG_INTERVAL_SECONDS = 5.0;
    private static final int REQUIRED_READY_SAMPLES = 6;
    private static final int MAX_CHUNK_RADIUS = 2;
    private static final int SAMPLE_HORIZONTAL_RADIUS = 10;
    private static final int SAMPLE_VERTICAL_UP = 6;
    private static final int SAMPLE_VERTICAL_DOWN = 10;
    private static final int MIN_NON_AIR_BLOCKS = 40;
    private static final double MAX_BOOTSTRAP_SECONDS = 600.0;

    private final TimerGame checkTimer = new TimerGame(CHECK_INTERVAL_SECONDS);
    private final TimerGame logTimer = new TimerGame(LOG_INTERVAL_SECONDS);

    private boolean bootstrapActive = false;
    private long bootstrapStartTick = -1;
    private int readySamples = 0;
    private Identifier lastWorldId = null;
    private String activeReason = "<none>";

    public WorldBootstrapChain(TaskRunner runner) {
        super(runner);
        EventBus.subscribe(ClientTickEvent.class, evt -> monitorWorldState());
    }

    public boolean isBootstrapActive() {
        return bootstrapActive;
    }

    public void requestBootstrap(String reason) {
        if (reason == null || reason.isBlank()) {
            reason = "unspecified";
        }
        activeReason = reason;
        readySamples = 0;
        bootstrapStartTick = WorldHelper.getTicks();
        bootstrapActive = true;
        // Force timers to run on next priority evaluation
        checkTimer.forceElapse();
        logTimer.forceElapse();

        if (!(mainTask instanceof IdleTask)) {
            setTask(new IdleTask());
        }

        Debug.logMessage(String.format(Locale.ROOT,
                "[WorldBootstrap] Activated (reason=%s)", activeReason), false);

        CamBridge camBridge = AltoClef.getInstance() != null ? AltoClef.getInstance().getCamBridge() : null;
        if (camBridge != null) {
            camBridge.notifyBootstrapStarted(activeReason);
        }

        if (AltoClef.inGame() && evaluateReadiness(AltoClef.getInstance())) {
            completeBootstrap("immediate-ready");
        }
    }

    private void completeBootstrap(String note) {
        if (!bootstrapActive) {
            return;
        }
        bootstrapActive = false;
        readySamples = 0;
        activeReason = note;
        Debug.logMessage(String.format(Locale.ROOT,
                "[WorldBootstrap] World ready (note=%s)", note), false);
        CamBridge camBridge = AltoClef.getInstance() != null ? AltoClef.getInstance().getCamBridge() : null;
        if (camBridge != null) {
            camBridge.notifyBootstrapFinished(note);
        }
        if (mainTask != null) {
            setTask(null);
        }
    }

    @Override
    public float getPriority() {
        AltoClef mod = AltoClef.getInstance();
        if (AltoClef.inGame()) {
            trackWorldChange(mod);
        } else {
            lastWorldId = null;
        }

        if (!bootstrapActive) {
            return Float.NEGATIVE_INFINITY;
        }

        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }

        if (!(mainTask instanceof IdleTask)) {
            setTask(new IdleTask());
        }

        if (checkTimer.elapsed()) {
            checkTimer.reset();
            boolean ready = evaluateReadiness(mod);
            if (ready) {
                readySamples++;
            } else {
                readySamples = 0;
            }
            if (readySamples >= REQUIRED_READY_SAMPLES) {
                completeBootstrap("stable" );
                return Float.NEGATIVE_INFINITY;
            }
        }

        if (logTimer.elapsed()) {
            logTimer.reset();
            long ticksHeld = bootstrapStartTick >= 0 ? WorldHelper.getTicks() - bootstrapStartTick : -1;
            double secondsHeld = ticksHeld < 0 ? -1 : ticksHeld / 20.0;
            Debug.logMessage(String.format(Locale.ROOT,
                    "[WorldBootstrap] Waiting for world readiness (reason=%s, samples=%d/%d, held=%.1fs)",
                    activeReason,
                    readySamples,
                    REQUIRED_READY_SAMPLES,
                    secondsHeld), false);
        }

        if (bootstrapStartTick >= 0 && MAX_BOOTSTRAP_SECONDS > 0) {
            long elapsedTicks = WorldHelper.getTicks() - bootstrapStartTick;
            if (elapsedTicks / 20.0 >= MAX_BOOTSTRAP_SECONDS) {
                Debug.logWarning(String.format(Locale.ROOT,
                        "[WorldBootstrap] Timeout after %.1fs - releasing control", MAX_BOOTSTRAP_SECONDS));
                completeBootstrap("timeout");
                return Float.NEGATIVE_INFINITY;
            }
        }

        return 200f;
    }

    private void trackWorldChange(AltoClef mod) {
        ClientWorld world = mod.getWorld();
        if (world == null) {
            lastWorldId = null;
            return;
        }
        Identifier currentId = world.getRegistryKey().getValue();
        if (lastWorldId == null || !lastWorldId.equals(currentId)) {
            lastWorldId = currentId;
            if (shouldBootstrapForWorld(currentId)) {
                requestBootstrap("dimension-change:" + currentId);
            }
        }
    }

    private void monitorWorldState() {
        if (!AltoClef.inGame()) {
            lastWorldId = null;
            return;
        }
        AltoClef mod = AltoClef.getInstance();
        trackWorldChange(mod);

        if (!bootstrapActive) {
            ClientWorld world = mod.getWorld();
            if (world != null && shouldBootstrapForWorld(world.getRegistryKey().getValue())) {
                if (!evaluateReadiness(mod)) {
                    requestBootstrap("auto-detect");
                }
            }
        }
    }

    private boolean shouldBootstrapForWorld(Identifier worldId) {
        String namespace = worldId.getNamespace();
        String path = worldId.getPath();
        if ("fahare".equals(namespace)) {
            return path.equals("overworld") || path.equals("limbo");
        }
        return "minecraft".equals(namespace) && path.equals("overworld");
    }

    private boolean evaluateReadiness(AltoClef mod) {
        ClientWorld world = mod.getWorld();
        ClientPlayerEntity player = mod.getPlayer();
        if (world == null || player == null) {
            return false;
        }

        BlockPos playerPos = player.getBlockPos();
        if (!chunksReady(world, playerPos)) {
            return false;
        }

        return hasSufficientBlocks(world, playerPos);
    }

    private boolean chunksReady(ClientWorld world, BlockPos center) {
        int baseChunkX = center.getX() >> 4;
        int baseChunkZ = center.getZ() >> 4;
        for (int dx = -MAX_CHUNK_RADIUS; dx <= MAX_CHUNK_RADIUS; dx++) {
            int chunkX = baseChunkX + dx;
            for (int dz = -MAX_CHUNK_RADIUS; dz <= MAX_CHUNK_RADIUS; dz++) {
                int chunkZ = baseChunkZ + dz;
                if (world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasSufficientBlocks(ClientWorld world, BlockPos center) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int nonAir = 0;
        int minY = world.getBottomY();
        int maxY = world.getTopY();

        for (int dx = -SAMPLE_HORIZONTAL_RADIUS; dx <= SAMPLE_HORIZONTAL_RADIUS; dx++) {
            int x = center.getX() + dx;
            for (int dz = -SAMPLE_HORIZONTAL_RADIUS; dz <= SAMPLE_HORIZONTAL_RADIUS; dz++) {
                int z = center.getZ() + dz;
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
                for (int dy = -SAMPLE_VERTICAL_DOWN; dy <= SAMPLE_VERTICAL_UP; dy++) {
                    int y = MathHelper.clamp(center.getY() + dy, minY, maxY);
                    mutable.set(x, y, z);
                    if (!world.isAir(mutable)) {
                        nonAir++;
                        if (nonAir >= MIN_NON_AIR_BLOCKS) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        setTask(null);
    }

    @Override
    public String getName() {
        return "World Bootstrap";
    }
}