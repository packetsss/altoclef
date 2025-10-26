package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.EscapeFromLavaTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasks.resources.CollectBucketLiquidTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.registry.tag.FluidTags;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame wasInLavaTimer = new TimerGame(1);
    private final TimerGame portalStuckTimer = new TimerGame(5);
    private boolean wasAvoidingDrowning;

    private BlockPos _extinguishWaterPosition;
    private boolean avoidUndergroundWater;
    private final Predicate<BlockPos> undergroundWaterAvoider;

    private static final long UNDERGROUND_WATER_BLACKLIST_DURATION_TICKS = 90 * 20L;
    private final Map<BlockPos, Long> undergroundWaterBlacklist = new HashMap<>();

    private static final int SKY_SCAN_RADIUS = 5;
    private static final int SKY_SCAN_VERTICAL = 2;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
        AltoClef mod = AltoClef.getInstance();
        undergroundWaterAvoider = this::shouldAvoidUndergroundWaterBlock;
        if (mod != null && mod.getBehaviour() != null) {
            mod.getBehaviour().avoidWalkingThrough(undergroundWaterAvoider);
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        AltoClef mod = AltoClef.getInstance();

        updateUndergroundWaterAvoidance(mod);

        // Drowning
        handleDrowning(mod);

        // Lava Escape
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            setTask(new EscapeFromLavaTask(mod));
            return 100;
        }

        // Fire escape
        if (isInFire(mod)) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Extinguish with water
        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (!(mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && !mod.getWorld().getDimension().ultrawarm()) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolidBlock(targetWaterPos.down()) && WorldHelper.canPlace(targetWaterPos)) {
                        Optional<Rotation> reach = LookHelper.getReach(targetWaterPos.down(), Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(targetWaterPos.down())) {
                                if (mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                                    _extinguishWaterPosition = targetWaterPos;
                                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                    setTask(null);
                                    return 90;
                                }
                            }
                        }
                    }
                }
                setTask(new DoToClosestBlockTask(GetToBlockTask::new, Blocks.WATER));
                return 90;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockScanner().isBlockAtPosition(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                setTask(new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), Direction.UP, _extinguishWaterPosition.down(), true));
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal()) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        return Float.NEGATIVE_INFINITY;
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (mod.getModSettings().shouldAvoidDrowning()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (wasAvoidingDrowning && !avoidedDrowning) {
            wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !wasInLavaTimer.elapsed();
    }

    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer()) {
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof AbstractFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal() {
        return WorldHelper.isInNetherPortal()
                && !AltoClef.getInstance().getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    private void updateUndergroundWaterAvoidance(AltoClef mod) {
        if (mod == null || mod.getWorld() == null || mod.getPlayer() == null) {
            setAvoidUndergroundWater(false);
            undergroundWaterBlacklist.clear();
            return;
        }
        Settings settings = mod.getModSettings();
        if (settings == null || !settings.shouldAvoidUndergroundWater()) {
            setAvoidUndergroundWater(false);
            undergroundWaterBlacklist.clear();
            return;
        }
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setAvoidUndergroundWater(false);
            undergroundWaterBlacklist.clear();
            return;
        }
        pruneExpiredUndergroundWaterEntries();

        if (shouldIgnoreUndergroundWaterForTask(mod)) {
            setAvoidUndergroundWater(false);
            undergroundWaterBlacklist.clear();
            return;
        }

        BlockPos playerPos = mod.getPlayer().getBlockPos();
        boolean skylightVisible = WorldHelper.isSkylightVisible(playerPos, SKY_SCAN_RADIUS, SKY_SCAN_VERTICAL);
        setAvoidUndergroundWater(!skylightVisible);

        if (avoidUndergroundWater) {
            updatePathUndergroundWaterBlacklist(mod);
        } else {
            undergroundWaterBlacklist.clear();
        }
    }

    private void setAvoidUndergroundWater(boolean value) {
        avoidUndergroundWater = value;
    }

    private boolean shouldAvoidUndergroundWaterBlock(BlockPos pos) {
        if (!avoidUndergroundWater) {
            return false;
        }
        long now = WorldHelper.getTicks();
        Long expiry = undergroundWaterBlacklist.get(pos);
        if (expiry == null || expiry <= now) {
            return false;
        }
        return true;
    }

    private void updatePathUndergroundWaterBlacklist(AltoClef mod) {
        if (mod.getClientBaritone() == null || mod.getClientBaritone().getPathingBehavior() == null) {
            return;
        }
        ClientWorld world = mod.getWorld();
        if (world == null) {
            return;
        }
        Optional<IPath> pathOpt = mod.getClientBaritone().getPathingBehavior().getPath();
        if (pathOpt.isEmpty()) {
            return;
        }

        boolean registeredNew = false;
        IPath path = pathOpt.get();
        for (BetterBlockPos node : path.positions()) {
            BlockPos pos = node;
            if (registerUndergroundWaterNode(world, pos)) {
                registeredNew = true;
            }
        }

        if (registeredNew && mod.getClientBaritone().getPathingBehavior().isPathing()) {
            Debug.logInternal("Cancelling path to avoid underground water.");
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
    }

    private boolean registerUndergroundWaterNode(ClientWorld world, BlockPos pos) {
        BlockPos check = pos;
        boolean registered = false;
        registered |= registerSingleUndergroundWaterBlock(world, check);
        registered |= registerSingleUndergroundWaterBlock(world, check.down());
        return registered;
    }

    private boolean registerSingleUndergroundWaterBlock(ClientWorld world, BlockPos candidate) {
        if (!isUndergroundWaterBlock(world, candidate)) {
            return false;
        }
        BlockPos key = candidate.toImmutable();
        long expiryTick = WorldHelper.getTicks() + UNDERGROUND_WATER_BLACKLIST_DURATION_TICKS;
        Long existing = undergroundWaterBlacklist.get(key);
        if (existing == null || existing < expiryTick) {
            undergroundWaterBlacklist.put(key, expiryTick);
            Debug.logInternal("Blacklisting underground water path node at " + key);
            return true;
        }
        return false;
    }

    private boolean isUndergroundWaterBlock(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        if (!world.getFluidState(pos).isIn(FluidTags.WATER)) {
            return false;
        }
        return !WorldHelper.isSkylightVisible(pos, 1, SKY_SCAN_VERTICAL);
    }

    private void pruneExpiredUndergroundWaterEntries() {
        long now = WorldHelper.getTicks();
        Iterator<Map.Entry<BlockPos, Long>> iterator = undergroundWaterBlacklist.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private boolean shouldIgnoreUndergroundWaterForTask(AltoClef mod) {
        if (mod.getUserTaskChain() == null) {
            return false;
        }
        Task current = mod.getUserTaskChain().getCurrentTask();
        if (current == null) {
            return false;
        }
        return current.thisOrChildSatisfies(task -> task instanceof CollectBucketLiquidTask);
    }
}
