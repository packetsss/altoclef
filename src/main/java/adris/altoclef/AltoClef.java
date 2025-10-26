package adris.altoclef;


import adris.altoclef.butler.Butler;
import adris.altoclef.cambridge.CamBridge;
import adris.altoclef.chains.*;
import adris.altoclef.trackers.BlockScanner;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.commandsystem.TabCompleter;
import adris.altoclef.control.InputControls;
import adris.altoclef.control.PlayerExtraController;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientRenderEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.multiversion.DrawContextWrapper;
import adris.altoclef.multiversion.RenderLayerVer;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasksystem.persistence.TaskPersistenceManager;
import adris.altoclef.trackers.*;
import adris.altoclef.trackers.storage.ContainerSubTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.ui.AltoClefTickChart;
import adris.altoclef.ui.CommandStatusOverlay;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.ui.MessageSender;
import adris.altoclef.util.helpers.InputHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.telemetry.BaritoneLogManager;
import adris.altoclef.telemetry.DeathLogManager;
import adris.altoclef.telemetry.LogTrimManager;
import adris.altoclef.telemetry.StuckLogManager;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * Central access point for AltoClef
 */
public class AltoClef implements ModInitializer {

    // Static access to altoclef
    private static final Queue<Consumer<AltoClef>> _postInitQueue = new ArrayDeque<>();

    // Central Managers
    private static CommandExecutor commandExecutor;
    private TaskRunner taskRunner;
    private TrackerManager trackerManager;
    private BotBehaviour botBehaviour;
    private PlayerExtraController extraController;
    // Task chains
    private UserTaskChain userTaskChain;
    private DeathMenuChain deathMenuChain;
    private FoodChain foodChain;
    private MobDefenseChain mobDefenseChain;
    private MLGBucketFallChain mlgBucketChain;
    private WorldBootstrapChain worldBootstrapChain;
    // Trackers
    private ItemStorageTracker storageTracker;
    private ContainerSubTracker containerSubTracker;
    private EntityTracker entityTracker;
    private BlockScanner blockScanner;
    private SimpleChunkTracker chunkTracker;
    private MiscBlockTracker miscBlockTracker;
    private CraftingRecipeTracker craftingRecipeTracker;
    // Renderers
    private CommandStatusOverlay commandStatusOverlay;
    private AltoClefTickChart altoClefTickChart;
    // Settings
    private adris.altoclef.Settings settings;
    // Misc managers/input
    private MessageSender messageSender;
    private InputControls inputControls;
    private SlotHandler slotHandler;
    // Butler
    private Butler butler;
    // Telemetry
    private DeathLogManager deathLogManager;
    private StuckLogManager stuckLogManager;
    private TaskPersistenceManager taskPersistenceManager;
    private BaritoneLogManager baritoneLogManager;
    private LogTrimManager logTrimManager;
    private boolean componentsInitialized = false;
    private boolean autoStartTriggered = false;
    private boolean forcedInitializationLogged = false;
    private static final DateTimeFormatter TELEMETRY_SESSION_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private String telemetrySessionId;
    private Path telemetrySessionDir;
    // Pausing
    private boolean paused = false;
    private boolean resumeCommandsLaunched = true;
    private final Deque<String> pendingResumeCommands = new ArrayDeque<>();
    private Task storedTask;
    private Vec3d idleMonitorAnchorPos = null;
    private long idleMonitorAnchorTick = -1;
    private long lastIdleLogTick = -1;
    private long smeltingSuppressionExpiryTick = -1;
    private boolean idleAggressivePathingActive = false;
    private double idleAggressiveBaselineCostHeuristic = Double.NaN;
    private long idleAggressiveExpiryTick = -1;
    private Vec3d idleAggressiveAnchorPos = null;
    private static final int IDLE_STALL_TICKS = 12 * 20;
    private static final int IDLE_STALL_COOLDOWN_TICKS = 30 * 20;
    private static final int SMELTING_SUPPRESSION_GRACE_TICKS = 8 * 20;
    private static final double IDLE_STALL_DISTANCE_SQ = 1.25 * 1.25;
    private static final double IDLE_STALL_HEURISTIC_BOOST = 11.0;
    private static final int IDLE_STALL_HEURISTIC_DURATION_TICKS = 12 * 20;
    private static final double IDLE_STALL_RECOVERY_DISTANCE_SQ = 4.0;
    private static final Set<String> SMELTING_TASK_CLASSES = Set.of(
        "adris.altoclef.tasks.container.SmeltInFurnaceTask",
        "adris.altoclef.tasks.container.SmeltInSmokerTask",
        "adris.altoclef.tasks.container.SmeltInBlastFurnaceTask"
    );

    private static AltoClef instance;
    private CamBridge camBridge;

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    /**
     * Executes commands (ex. `@get`/`@gamer`)
     */
    public static CommandExecutor getCommandExecutor() {
        if (instance == null) {
            return null;
        }
        instance.ensureInitialized();
        return commandExecutor;
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // As such, nothing will be loaded here but basic initialization.
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());

        if (instance != null) {
            throw new IllegalStateException("AltoClef already loaded!");
        }
        instance = this;
    }

    public void onInitializeLoad() {
        // This code should be run after Minecraft loads everything else in.
        // This is the actual start point, controlled by a mixin.
        if (componentsInitialized) {
            return;
        }
        componentsInitialized = true;

        initializeBaritoneSettings();

    initializeTelemetrySession();
    baritoneLogManager = new BaritoneLogManager(this);
    logTrimManager = new LogTrimManager(MinecraftClient.getInstance().runDirectory.toPath());

        // Central Managers
    commandExecutor = new CommandExecutor(this);
    taskPersistenceManager = new TaskPersistenceManager(this);
    pendingResumeCommands.clear();
    pendingResumeCommands.addAll(taskPersistenceManager.getCommandsToResume());
    resumeCommandsLaunched = pendingResumeCommands.isEmpty();
        taskRunner = new TaskRunner(this);
        trackerManager = new TrackerManager(this);
        botBehaviour = new BotBehaviour(this);
        extraController = new PlayerExtraController(this);
    deathLogManager = new DeathLogManager(this);
    stuckLogManager = new StuckLogManager(this);
    camBridge = new CamBridge(this);

        // Task chains
    userTaskChain = new UserTaskChain(taskRunner);
    mobDefenseChain = new MobDefenseChain(taskRunner);
    deathMenuChain = new DeathMenuChain(taskRunner);
        new PlayerInteractionFixChain(taskRunner);
        mlgBucketChain = new MLGBucketFallChain(taskRunner);
        new UnstuckChain(taskRunner);
        new PreEquipItemChain(taskRunner);
        new WorldSurvivalChain(taskRunner);
        foodChain = new FoodChain(taskRunner);
    worldBootstrapChain = new WorldBootstrapChain(taskRunner);
    worldBootstrapChain.requestBootstrap("initial-load");

        // Trackers
        storageTracker = new ItemStorageTracker(this, trackerManager, container -> containerSubTracker = container);
        entityTracker = new EntityTracker(trackerManager);
        blockScanner = new BlockScanner(this);
        chunkTracker = new SimpleChunkTracker(this);
        miscBlockTracker = new MiscBlockTracker(this);
        craftingRecipeTracker = new CraftingRecipeTracker(trackerManager);

        // Renderers
        commandStatusOverlay = new CommandStatusOverlay();
        altoClefTickChart = new AltoClefTickChart(MinecraftClient.getInstance().textRenderer);

        // Misc managers
        messageSender = new MessageSender();
        inputControls = new InputControls();
        slotHandler = new SlotHandler(this);

        butler = new Butler(this);

        initializeCommands();

        // Load settings
        adris.altoclef.Settings.load(newSettings -> {
            settings = newSettings;
            autoStartTriggered = false;
            if (camBridge != null) {
                camBridge.onSettingsReload(newSettings);
            }
            // Baritone's `acceptableThrowawayItems` should match our own.
            List<Item> baritoneCanPlace = Arrays.stream(settings.getThrowawayItems(true))
                    .filter(item -> item != Items.SOUL_SAND && item != Items.MAGMA_BLOCK && item != Items.SAND && item
                            != Items.GRAVEL).toList();
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(baritoneCanPlace);
            // If we should run an idle command...
            if ((!getUserTaskChain().isActive() || getUserTaskChain().isRunningIdleTask()) && getModSettings().shouldRunIdleCommandWhenNotActive()) {
                getUserTaskChain().signalNextTaskToBeIdleTask();
                getCommandExecutor().executeWithPrefix(getModSettings().getIdleCommand());
            }
            // Don't break blocks or place blocks where we are explicitly protected.
            getExtraBaritoneSettings().avoidBlockBreak(blockPos -> settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().avoidBlockPlace(blockPos -> settings.isPositionExplicitlyProtected(blockPos));
            getExtraBaritoneSettings().getForceSaveToolPredicates().add((state, item) -> StorageHelper.shouldSaveStack(this, state.getBlock(), item));
        });

        // Receive + cancel chat
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (getCommandExecutor().isClientCommand(line)) {
                evt.cancel();
                getCommandExecutor().execute(line);
            }
        });

        // Tick with the client
        EventBus.subscribe(ClientTickEvent.class, evt -> {
            long nanos = System.nanoTime();
            onClientTick();
            if (camBridge != null) {
                camBridge.onClientTick();
            }
            altoClefTickChart.pushTickNanos(System.nanoTime() - nanos);

            if (!inGame()) {
                autoStartTriggered = false;
                resetIdleMovementTelemetry();
            } else {
                if (!resumeCommandsLaunched && settings != null && !pendingResumeCommands.isEmpty()) {
                    CommandExecutor executor = AltoClef.getCommandExecutor();
                    if (executor != null) {
                        String joined = String.join(";", pendingResumeCommands);
                        Debug.logMessage(String.format(Locale.ROOT,
                                "Resuming %d pending command(s): %s",
                                pendingResumeCommands.size(),
                                joined), false);
                        executor.execute(joined);
                    }
                    resumeCommandsLaunched = true;
                }
                if (!autoStartTriggered && settings != null) {
                    String autoStart = settings.getAutoStartCommand();
                    autoStart = autoStart == null ? "" : autoStart.trim();
                    if (!autoStart.isEmpty()) {
                        CommandExecutor executor = AltoClef.getCommandExecutor();
                        if (executor != null) {
                            Debug.logMessage("Auto-start executing: " + autoStart);
                            executor.executeWithPrefix(autoStart);
                            autoStartTriggered = true;
                        }
                    }
                }
                updateIdleMovementTelemetry();
            }
        });

        // Render
        EventBus.subscribe(ClientRenderEvent.class, evt -> onClientRenderOverlay(evt.context));

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // Tasks
        TaskCatalogue.init();

        getClientBaritone().getGameEventHandler().registerEventListener(new TabCompleter());

        // External mod initialization
        runEnqueuedPostInits();
    }

    // Client tick
    private void onClientTick() {
        runEnqueuedPostInits();

        if (logTrimManager != null) {
            logTrimManager.tick();
        }

        inputControls.onTickPre();

        // Cancel shortcut
        if (InputHelper.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) && InputHelper.isKeyPressed(GLFW.GLFW_KEY_K)) {
            stopTasks();
        }

        // TODO: should this go here?
        storageTracker.setDirty();
        containerSubTracker.onServerTick();
        miscBlockTracker.tick();
        trackerManager.tick();
        blockScanner.tick();
        taskRunner.tick();

        messageSender.tick();

        inputControls.onTickPost();
    }

    public void stopTasks() {
        if (userTaskChain != null) {
            userTaskChain.cancel(this);
        }
        if (taskRunner.getCurrentTaskChain() != null) {
            taskRunner.getCurrentTaskChain().stop();
        }
        commandStatusOverlay.resetTimer();
    }

    public void resetAfterDeath() {
        Debug.logMessage("[Death] Resetting cached trackers after respawn", false);
        if (trackerManager != null) {
            trackerManager.resetAllTrackers();
        }
        if (blockScanner != null) {
            blockScanner.reset();
        }
        if (taskRunner != null) {
            taskRunner.reset();
        }
        if (storageTracker != null) {
            storageTracker.setDirty();
        }
        if (entityTracker != null) {
            entityTracker.setDirty();
        }
        if (worldBootstrapChain != null) {
            worldBootstrapChain.requestBootstrap("respawn");
        }
    }

    /// GETTERS AND SETTERS

    private void onClientRenderOverlay(DrawContextWrapper context) {
        context.setRenderLayer(RenderLayerVer.getGuiOverlay());
        if (settings.shouldShowTaskChain()) {
            commandStatusOverlay.render(this, context);
        }

        if (settings.shouldShowDebugTickMs()) {
            altoClefTickChart.render(this, context, 1, context.getScaledWindowWidth() / 2 - 124);
        }
    }

    private void initializeBaritoneSettings() {
        getExtraBaritoneSettings().canWalkOnEndPortal(false);
        getClientBaritoneSettings().freeLook.value = false;
        getClientBaritoneSettings().overshootTraverse.value = false;
        getClientBaritoneSettings().allowOvershootDiagonalDescend.value = true;
        getClientBaritoneSettings().allowInventory.value = true;
        getClientBaritoneSettings().allowParkour.value = false;
        getClientBaritoneSettings().allowParkourAscend.value = false;
        getClientBaritoneSettings().allowParkourPlace.value = false;
        getClientBaritoneSettings().allowDiagonalDescend.value = false;
        getClientBaritoneSettings().allowDiagonalAscend.value = false;
        getClientBaritoneSettings().blocksToAvoid.value = new LinkedList<>(List.of(Blocks.FLOWERING_AZALEA, Blocks.AZALEA,
                Blocks.POWDER_SNOW, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.SWEET_BERRY_BUSH,
                Blocks.WARPED_ROOTS, Blocks.VINE, Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD,
                Blocks.AMETHYST_CLUSTER, Blocks.SCULK, Blocks.SCULK_VEIN));

        // dont try to break nether portal block
        getClientBaritoneSettings().blocksToAvoidBreaking.value.add(Blocks.NETHER_PORTAL);
        getClientBaritoneSettings().blocksToDisallowBreaking.value.add(Blocks.NETHER_PORTAL);

        // Let baritone move items to hotbar to use them
        // Reduces a bit of far rendering to save FPS
        getClientBaritoneSettings().fadePath.value = true;
        // Don't let baritone scan dropped items, we handle that ourselves.
        getClientBaritoneSettings().mineScanDroppedItems.value = false;
        // Don't let baritone wait for drops, we handle that ourselves.
        getClientBaritoneSettings().mineDropLoiterDurationMSThanksLouca.value = 0L;

        // Water bucket placement will be handled by us exclusively
        getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);

        // For render smoothing
        getClientBaritoneSettings().randomLooking.value = 0.0;
        getClientBaritoneSettings().randomLooking113.value = 0.0;

        // Give baritone more time to calculate paths. Sometimes they can be really far away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.reset();
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.reset();
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.reset();
    }

    // List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to initialize new command lists.
            AltoClefCommands.init();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // TODO refactor codebase to use this instead of passing an argument around
    /**
     * @return the instance of this class or null if it has not been initialized yet
     */
    public static AltoClef getInstance() {
        return instance;
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return taskRunner;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks or items
     */
    public BotBehaviour getBehaviour() {
        return botBehaviour;
    }

    /**
     * Controls tasks, for pausing and unpausing the bot
     */
    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean pausing) {
        this.paused = pausing;
    }

    /**
     * storages the task you where doing before pausing.
     */
    public void setStoredTask(Task currentTask) {
        this.storedTask = currentTask;
    }

    /**
     * Gets the task you where doing before pausing.
     */
    public Task getStoredTask() {
        return storedTask;
    }

    /**
     * Tracks items in your inventory and in storage containers.
     */
    public ItemStorageTracker getItemStorage() {
        return storageTracker;
    }

    /**
     * Tracks loaded entities
     */
    public EntityTracker getEntityTracker() {
        return entityTracker;
    }

    /**
     * Manages a list of all available recipes
     */
    public CraftingRecipeTracker getCraftingRecipeTracker() {
        return craftingRecipeTracker;
    }

    /**
     * Tracks blocks and their positions - better version of BlockTracker
     */
    public BlockScanner getBlockScanner() {
        return blockScanner;
    }

    /**
     * Tracks of whether a chunk is loaded/visible or not
     */
    public SimpleChunkTracker getChunkTracker() {
        return chunkTracker;
    }

    /**
     * Tracks random block things, like the last nether portal we used
     */
    public MiscBlockTracker getMiscBlockTracker() {
        return miscBlockTracker;
    }

    /**
     * Baritone access (could just be static honestly)
     */
    public Baritone getClientBaritone() {
        if (getPlayer() == null) {
            return (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        return (Baritone) BaritoneAPI.getProvider().getBaritoneForPlayer(getPlayer());
    }

    /**
     * Baritone settings access (could just be static honestly)
     */
    public Settings getClientBaritoneSettings() {
        return Baritone.settings();
    }

    /**
     * Baritone settings special to AltoClef (could just be static honestly)
     */
    public AltoClefSettings getExtraBaritoneSettings() {
        return AltoClefSettings.getInstance();
    }

    /**
     * AltoClef Settings
     */
    public adris.altoclef.Settings getModSettings() {
        return settings;
    }

    /**
     * Butler controller. Keeps track of users and lets you receive user messages
     */
    public Butler getButler() {
        return butler;
    }

    /**
     * Sends chat messages (avoids auto-kicking)
     */
    public MessageSender getMessageSender() {
        return messageSender;
    }

    /**
     * Does Inventory/container slot actions
     */
    public SlotHandler getSlotHandler() {
        return slotHandler;
    }

    public DeathLogManager getDeathLogManager() {
        return deathLogManager;
    }

    public DeathMenuChain getDeathMenuChain() {
        return deathMenuChain;
    }

    public StuckLogManager getStuckLogManager() {
        return stuckLogManager;
    }

    public TaskPersistenceManager getTaskPersistenceManager() {
        return taskPersistenceManager;
    }

    public BaritoneLogManager getBaritoneLogManager() {
        return baritoneLogManager;
    }

    public Path getTelemetrySessionDir() {
        if (telemetrySessionDir == null) {
            initializeTelemetrySession();
        }
        return telemetrySessionDir;
    }

    public String getTelemetrySessionId() {
        if (telemetrySessionId == null) {
            initializeTelemetrySession();
        }
        return telemetrySessionId;
    }

    /**
     * Minecraft player client access (could just be static honestly)
     */
    public ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    /**
     * Minecraft world access (could just be static honestly)
     */
    public ClientWorld getWorld() {
        return MinecraftClient.getInstance().world;
    }

    /**
     * Minecraft client interaction controller access (could just be static honestly)
     */
    public ClientPlayerInteractionManager getController() {
        return MinecraftClient.getInstance().interactionManager;
    }

    /**
     * Extra controls not present in ClientPlayerInteractionManager. This REALLY should be made static or combined with something else.
     */
    public PlayerExtraController getControllerExtras() {
        return extraController;
    }

    /**
     * Manual control over input actions (ex. jumping, attacking)
     */
    public InputControls getInputControls() {
        return inputControls;
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task) {
        runUserTask(task, () -> {
        });
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task, Runnable onFinish) {
        userTaskChain.runTask(this, task, onFinish);
    }

    /**
     * Cancel currently running user task
     */
    public void cancelUserTask() {
        userTaskChain.cancel(this);
    }

    /**
     * Takes control away to eat food
     */
    public FoodChain getFoodChain() {
        return foodChain;
    }

    /**
     * Takes control away to defend against mobs
     */
    public MobDefenseChain getMobDefenseChain() {
        return mobDefenseChain;
    }

    /**
     * Takes control away to perform bucket saves
     */
    public MLGBucketFallChain getMLGBucketChain() {
        return mlgBucketChain;
    }

    public WorldBootstrapChain getWorldBootstrapChain() {
        return worldBootstrapChain;
    }

    public CamBridge getCamBridge() {
        return camBridge;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    /**
     * Logs to the console and also messages any player using the bot as a butler.
     */
    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    /**
     * Logs a warning to the console and also alerts any player using the bot as a butler.
     */
    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
    }

    private void ensureInitialized() {
        if (componentsInitialized) {
            return;
        }
        if (!forcedInitializationLogged) {
            Debug.logWarning("Forcing AltoClef bootstrap before TitleScreen initialization has run.");
            forcedInitializationLogged = true;
        }
        onInitializeLoad();
    }

    private void initializeTelemetrySession() {
        if (telemetrySessionDir != null) {
            return;
        }
        telemetrySessionId = UUID.randomUUID().toString();
        String folderName = String.format(Locale.ROOT, "%s-%s",
                TELEMETRY_SESSION_FORMAT.format(LocalDateTime.now(ZoneOffset.UTC)),
                telemetrySessionId.substring(0, 8));
        Path runDirectory = MinecraftClient.getInstance().runDirectory.toPath();
        Path sessionRoot = runDirectory.resolve(Paths.get("altoclef", "logs", "session"));
        clearTelemetrySessions(sessionRoot);
        telemetrySessionDir = sessionRoot.resolve(folderName);
        try {
            Files.createDirectories(telemetrySessionDir);
        } catch (IOException ex) {
            Debug.logWarning(String.format(Locale.ROOT,
                    "Failed to prepare telemetry session directory %s: %s",
                    telemetrySessionDir,
                    ex.getMessage()));
        }
    }

    private void clearTelemetrySessions(Path sessionRoot) {
        if (sessionRoot == null || !Files.exists(sessionRoot)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionRoot)) {
            for (Path entry : entries) {
                try {
                    deletePathRecursively(entry);
                } catch (IOException ex) {
                    Debug.logWarning(String.format(Locale.ROOT,
                            "Failed to delete telemetry session path %s: %s",
                            entry,
                            ex.getMessage()));
                }
            }
        } catch (IOException ex) {
            Debug.logWarning(String.format(Locale.ROOT,
                    "Failed to enumerate telemetry session directory %s: %s",
                    sessionRoot,
                    ex.getMessage()));
        }
    }

    private void deletePathRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deletePathRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

    private void updateIdleMovementTelemetry() {
        if (stuckLogManager == null || settings == null || !settings.isStuckLogEnabled()) {
            resetIdleMovementTelemetry();
            return;
        }
        if (paused || taskRunner == null || !taskRunner.isActive()) {
            resetIdleMovementTelemetry();
            return;
        }
        if (worldBootstrapChain != null && worldBootstrapChain.isBootstrapActive()) {
            resetIdleMovementTelemetry();
            return;
        }

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            resetIdleMovementTelemetry();
            return;
        }

        long currentTick = WorldHelper.getTicks();

        if (suppressIdleTelemetry(currentTick)) {
            idleMonitorAnchorPos = player.getPos();
            idleMonitorAnchorTick = currentTick;
            return;
        }

        Vec3d currentPos = player.getPos();
        updateIdleStallRecoveryState(currentPos, currentTick);
        if (idleMonitorAnchorPos == null) {
            idleMonitorAnchorPos = currentPos;
            idleMonitorAnchorTick = currentTick;
            return;
        }

        double distanceSq = currentPos.squaredDistanceTo(idleMonitorAnchorPos);
        if (distanceSq > IDLE_STALL_DISTANCE_SQ) {
            idleMonitorAnchorPos = currentPos;
            idleMonitorAnchorTick = currentTick;
            return;
        }

        long elapsedTicks = currentTick - idleMonitorAnchorTick;
        boolean cooledDown = lastIdleLogTick == -1 || currentTick - lastIdleLogTick >= IDLE_STALL_COOLDOWN_TICKS;

        if (elapsedTicks >= IDLE_STALL_TICKS && cooledDown) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("elapsed_ticks", elapsedTicks);
            payload.put("elapsed_seconds", Math.round((elapsedTicks / 20.0) * 100.0) / 100.0);
            payload.put("distance", Math.sqrt(distanceSq));
            payload.put("anchor_pos", vectorMap(idleMonitorAnchorPos));
            payload.put("current_pos", vectorMap(currentPos));
            payload.put("runner_status", taskRunner != null ? taskRunner.statusReport : "<none>");
            stuckLogManager.recordEvent("PlayerIdleStall", payload);
            triggerIdleStallRecovery(currentPos, currentTick);
            lastIdleLogTick = currentTick;
            idleMonitorAnchorPos = currentPos;
            idleMonitorAnchorTick = currentTick;
        }
    }

    private void resetIdleMovementTelemetry() {
        idleMonitorAnchorPos = null;
        idleMonitorAnchorTick = -1;
        deactivateIdleStallRecovery("reset");
    }

    private void updateIdleStallRecoveryState(Vec3d currentPos, long currentTick) {
        if (!idleAggressivePathingActive) {
            return;
        }
        boolean expired = idleAggressiveExpiryTick >= 0 && currentTick >= idleAggressiveExpiryTick;
        boolean movedEnough = idleAggressiveAnchorPos != null && currentPos != null
                && idleAggressiveAnchorPos.squaredDistanceTo(currentPos) >= IDLE_STALL_RECOVERY_DISTANCE_SQ;
        if (expired || movedEnough) {
            String reason;
            if (expired && movedEnough) {
                reason = "expired+moved";
            } else if (expired) {
                reason = "expired";
            } else {
                reason = "movement";
            }
            deactivateIdleStallRecovery(reason);
        }
    }

    private void triggerIdleStallRecovery(Vec3d anchorPos, long currentTick) {
        Settings baritoneSettings = getClientBaritoneSettings();
        double previous = baritoneSettings.costHeuristic.value;
        if (!idleAggressivePathingActive) {
            idleAggressiveBaselineCostHeuristic = previous;
        }
        double boosted = Math.max(previous, IDLE_STALL_HEURISTIC_BOOST);
        if (!idleAggressivePathingActive || boosted != previous) {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[IdleRecovery] Boosting Baritone costHeuristic %.3f -> %.3f for %.1fs",
                    previous,
                    boosted,
                    IDLE_STALL_HEURISTIC_DURATION_TICKS / 20.0), false);
        }
        baritoneSettings.costHeuristic.value = boosted;
        idleAggressivePathingActive = true;
        idleAggressiveAnchorPos = anchorPos;
        idleAggressiveExpiryTick = currentTick + IDLE_STALL_HEURISTIC_DURATION_TICKS;
        refreshActiveBaritoneGoal();
    }

    private void deactivateIdleStallRecovery(String reason) {
        if (idleAggressivePathingActive) {
            Settings baritoneSettings = getClientBaritoneSettings();
            double target = Double.isNaN(idleAggressiveBaselineCostHeuristic)
                    ? baritoneSettings.costHeuristic.defaultValue
                    : idleAggressiveBaselineCostHeuristic;
            baritoneSettings.costHeuristic.value = target;
            Debug.logMessage(String.format(Locale.ROOT,
                    "[IdleRecovery] Restoring Baritone costHeuristic to %.3f (%s)",
                    target,
                    reason), false);
        }
        idleAggressivePathingActive = false;
        idleAggressiveBaselineCostHeuristic = Double.NaN;
        idleAggressiveExpiryTick = -1;
        idleAggressiveAnchorPos = null;
    }

    private boolean suppressIdleTelemetry(long currentTick) {
        if (isSmeltingContextActiveNow()) {
            smeltingSuppressionExpiryTick = currentTick + SMELTING_SUPPRESSION_GRACE_TICKS;
            return true;
        }
        if (smeltingSuppressionExpiryTick != -1 && currentTick <= smeltingSuppressionExpiryTick) {
            return true;
        }
        if (smeltingSuppressionExpiryTick != -1 && currentTick > smeltingSuppressionExpiryTick) {
            smeltingSuppressionExpiryTick = -1;
        }
        return false;
    }

    private boolean isSmeltingContextActiveNow() {
        if (StorageHelper.isFurnaceOpen() || StorageHelper.isSmokerOpen() || StorageHelper.isBlastFurnaceOpen()) {
            return true;
        }
        if (taskRunner == null) {
            return false;
        }
        for (TaskRunner.ChainDiagnostics chain : taskRunner.getChainDiagnostics()) {
            if (!chain.active()) {
                continue;
            }
            for (TaskRunner.TaskDiagnostics task : chain.tasks()) {
                if (!task.active()) {
                    continue;
                }
                String className = task.className();
                if (SMELTING_TASK_CLASSES.contains(className)) {
                    return true;
                }
                if (className != null && className.toLowerCase(Locale.ROOT).contains("smelt")) {
                    return true;
                }
                if (containsSmeltKeywords(task.summary())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsSmeltKeywords(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("smelt") || lower.contains("furnace") || lower.contains("blast furnace") || lower.contains("campfire");
    }

    private Map<String, Object> vectorMap(Vec3d vec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", Math.round(vec.x * 1000.0) / 1000.0);
        map.put("y", Math.round(vec.y * 1000.0) / 1000.0);
        map.put("z", Math.round(vec.z * 1000.0) / 1000.0);
        return map;
    }

    private void refreshActiveBaritoneGoal() {
        Baritone baritone = getClientBaritone();
        var pathingBehavior = baritone.getPathingBehavior();
        pathingBehavior.cancelEverything();
        pathingBehavior.forceCancel();
        baritone.getExploreProcess().onLostControl();
        baritone.getCustomGoalProcess().onLostControl();
        requestCurrentTaskGoalReissue();
    }

    private void requestCurrentTaskGoalReissue() {
        if (taskRunner == null || !taskRunner.isActive()) {
            return;
        }
        TaskChain currentChain = taskRunner.getCurrentTaskChain();
        if (currentChain == null || !currentChain.isActive()) {
            return;
        }
        currentChain.onInterrupt(currentChain);
    }

}
