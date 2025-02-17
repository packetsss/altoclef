package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RayTraceContext;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class CollectObsidianTask extends ResourceTask {

    private final TimerGame placeWaterTimeout = new TimerGame(6);
    private final MovementProgressChecker lavaTimeout = new MovementProgressChecker();
    private final Set<BlockPos> lavaBlacklist = new HashSet<>();
    private final int count;
    private Task forceCompleteTask = null;
    private BlockPos lavaWaitCurrentPos;

    private PlaceObsidianBucketTask placeObsidianTask;

    public CollectObsidianTask(int count) {
        super(Items.OBSIDIAN, count);
        this.count = count;
    }

    private static BlockPos getLavaStructurePos(BlockPos lavaPos) {
        return adris.altoclef.multiversion.blockpos.BlockPosHelper.add(lavaPos,1,1,0);
    }

    private static BlockPos getLavaWaterPos(BlockPos lavaPos) {
        return lavaPos.up();
    }

    private static BlockPos getGoodObsidianPosition(AltoClef mod) {
        BlockPos start = adris.altoclef.multiversion.blockpos.BlockPosHelper.add(mod.getPlayer().getBlockPos(),-3,-3,-3);
        BlockPos end = adris.altoclef.multiversion.blockpos.BlockPosHelper.add(mod.getPlayer().getBlockPos(),3,3,3);
        for (BlockPos pos : WorldHelper.scanRegion(mod, start, end)) {
            if (!WorldHelper.canBreak(mod, pos) || !WorldHelper.canPlace(mod, pos)) {
                return null;
            }
        }
        return mod.getPlayer().getBlockPos();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();

        mod.getBehaviour().setRayTracingFluidHandling(RayTraceContext.FluidHandling.SOURCE_ONLY);

        // Avoid placing on the lava block we're trying to mine.
        mod.getBehaviour().avoidBlockPlacing(pos -> {
            if (lavaWaitCurrentPos != null) {
                return pos.equals(lavaWaitCurrentPos) || pos.equals(getLavaWaterPos(lavaWaitCurrentPos));
            }
            return false;
        });
        mod.getBehaviour().avoidBlockBreaking(pos -> {
            if (lavaWaitCurrentPos != null) {
                return pos.equals(getLavaStructurePos(lavaWaitCurrentPos));
            }
            return false;
        });
    }

    @Override
    protected adris.altoclef.tasksystem.Task onResourceTick(AltoClef mod) {

        // Clear the current waiting lava pos if it's no longer lava.
        if (lavaWaitCurrentPos != null && mod.getChunkTracker().isChunkLoaded(lavaWaitCurrentPos) && mod.getWorld().getBlockState(lavaWaitCurrentPos).getBlock() != Blocks.LAVA) {
            lavaWaitCurrentPos = null;
        }

        // Get a diamond pickaxe FIRST
        if (!StorageHelper.miningRequirementMet(mod, MiningRequirement.DIAMOND)) {
            setDebugState("Getting diamond pickaxe first");
            return new SatisfyMiningRequirementTask(MiningRequirement.DIAMOND);
        }

        if (forceCompleteTask != null && forceCompleteTask.isActive() && !forceCompleteTask.isFinished(mod)) {
            return forceCompleteTask;
        }

        Predicate<BlockPos> goodObsidian = (blockPos ->
                blockPos.isWithinDistance(mod.getPlayer().getPos(), 800)
                        && WorldHelper.canBreak(mod, blockPos)
        );

        /*
        // Check for nearby obsidian
        // WHY do we do this?
        //      - because our jank 'portal' task protects our obsidian.
        boolean obsidianNearby = false;
        BlockPos start = mod.getPlayer().getBlockPos().add(-3, -3, -3);
        BlockPos end = mod.getPlayer().getBlockPos().add(3, 3, 3);
        for (BlockPos pos : WorldUtil.scanRegion(mod, start, end)) {
            if (mod.getBlockTracker().blockIsValid(pos, Blocks.OBSIDIAN) && !badObsidian.test(pos)) {
                obsidianNearby = true;
                break;
            }
        }
         */
        if (/*obsidianNearby || */mod.getBlockScanner().anyFound(goodObsidian, Blocks.OBSIDIAN) || mod.getEntityTracker().itemDropped(Items.OBSIDIAN)) {
            /*
            // Clear nearby water
            BlockPos nearestObby = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.OBSIDIAN);
            if (nearestObby != null) {
                BlockPos nearestWater = mod.getBlockTracker().getNearestTracking(WorldWorldHelper.toVec3d(nearestObby), blockPos -> !WorldUtil.isSourceBlock(mod, blockPos, true), Blocks.WATER);

                if (nearestWater != null && nearestWater.getSquaredDistance(nearestObby) < 5 * 5) {
                    forceCompleteTask = new ClearLiquidTask(nearestWater);
                    setDebugState("Clearing water nearby obsidian");
                    return forceCompleteTask;
                }
            }
             */

            setDebugState("Mining/Collecting obsidian");
            placeObsidianTask = null;
            return new MineAndCollectTask(new ItemTarget(Items.OBSIDIAN, count), new Block[]{Blocks.OBSIDIAN}, MiningRequirement.DIAMOND);
        }

        if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            final double AVERAGE_GOLD_PER_OBSIDIAN = 11.475;
            int gold_buffer = (int) (AVERAGE_GOLD_PER_OBSIDIAN * count);
            setDebugState("We can't place water, so we're trading for obsidian");
            return new TradeWithPiglinsTask(gold_buffer, Items.OBSIDIAN, count);
        }

        if (placeObsidianTask == null) {
            BlockPos goodPos = getGoodObsidianPosition(mod);
            if (goodPos != null) {
                placeObsidianTask = new PlaceObsidianBucketTask(goodPos);
            } else {
                setDebugState("Walking until we find a spot to place obsidian");
                return new TimeoutWanderTask();
            }
        }
        // Try to see if we can nudge the obsidian placer closer to lava.
        //noinspection ConstantConditions
        if (placeObsidianTask != null && !mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
            // We've moved sort of far away from our post, and this will STOP running when we grab our lava
            // (which is exactly when we want it to run and no more!
            if (!placeObsidianTask.getPos().isWithinDistance(mod.getPlayer().getPos(), 4)) {
                BlockPos goodPos = getGoodObsidianPosition(mod);
                if (goodPos != null) {
                    Debug.logMessage("(nudged obsidian target closer)");
                    placeObsidianTask = new PlaceObsidianBucketTask(goodPos);
                }
            }
        }

        // lmfao
        setDebugState("Placing Obsidian");
        return placeObsidianTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, adris.altoclef.tasksystem.Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectObsidianTask task) {
            return task.count == count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + count + " blocks of obsidian";
    }
}
