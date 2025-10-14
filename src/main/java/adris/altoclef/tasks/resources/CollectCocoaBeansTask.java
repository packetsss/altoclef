package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.SearchWithinBiomeTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LocateBiomeCommandHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;

import java.util.HashSet;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectCocoaBeansTask extends ResourceTask {
    private final int _count;
    private final HashSet<BlockPos> _wasFullyGrown = new HashSet<>();
    private LocateBiomeCommandHelper jungleLocator;

    public CollectCocoaBeansTask(int targetCount) {
        super(Items.COCOA_BEANS, targetCount);
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        jungleLocator = new LocateBiomeCommandHelper(mod,
            "minecraft:jungle",
            "jungle",
            Dimension.OVERWORLD,
            60,
            10);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        Predicate<BlockPos> validCocoa = (blockPos) -> {
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                return _wasFullyGrown.contains(blockPos);
            }

            BlockState s = mod.getWorld().getBlockState(blockPos);
            boolean mature = s.get(CocoaBlock.AGE) == 2;
            if (_wasFullyGrown.contains(blockPos)) {
                if (!mature) _wasFullyGrown.remove(blockPos);
            } else {
                if (mature) _wasFullyGrown.add(blockPos);
            }
            return mature;
        };

        // Break mature cocoa blocks
        if (mod.getBlockScanner().anyFound(validCocoa, Blocks.COCOA)) {
            setDebugState("Breaking cocoa blocks");
            return new DoToClosestBlockTask(DestroyBlockTask::new, validCocoa, Blocks.COCOA);
        }

        // Dimension
        if (isInWrongDimension(mod)) {
            return getToCorrectDimensionTask(mod);
        }

        if (jungleLocator != null) {
            jungleLocator.tick();
        }
        Optional<BlockPos> locatedJungle = jungleLocator != null ? jungleLocator.getLocatedPosition() : Optional.empty();

        // Search for jungles
        if (locatedJungle.isPresent()) {
            BlockPos target = locatedJungle.get();
            setDebugState("Heading to located jungle at " + target.toShortString());
            return new GetToXZTask(target.getX(), target.getZ(), Dimension.OVERWORLD);
        }
        if (jungleLocator != null && !jungleLocator.isUnsupported()) {
            setDebugState("Locating jungle biome via command...");
            return new TimeoutWanderTask();
        }

        setDebugState("Exploring around jungles");
        return new SearchWithinBiomeTask(BiomeKeys.JUNGLE);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        if (jungleLocator != null) {
            jungleLocator.close();
            jungleLocator = null;
        }
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectCocoaBeansTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " cocoa beans.";
    }
}
