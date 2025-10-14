package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LocateStructureCommandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;

public class LocateDesertTempleTask extends Task {

    private BlockPos _finalPos;
    private LocateStructureCommandHelper templeLocator;

    @Override
    protected void onStart() {
        templeLocator = new LocateStructureCommandHelper(AltoClef.getInstance(),
            "minecraft:desert_pyramid",
            "desert",
            Dimension.OVERWORLD,
            60,
            10);
    }

    @Override
    protected Task onTick() {
        if (templeLocator != null) {
            templeLocator.tick();
            templeLocator.getLocatedPosition().ifPresent(located -> _finalPos = located.up(14));
        }

        BlockPos desertTemplePos = WorldHelper.getADesertTemple();
        if (desertTemplePos != null) {
            _finalPos = desertTemplePos.up(14);
        }
        if (_finalPos != null) {
            setDebugState("Going to found desert temple");
            return new GetToBlockTask(_finalPos, false);
        }
        if (templeLocator != null && !templeLocator.isUnsupported()) {
            setDebugState("Locating desert temple via /locate command...");
            return new TimeoutWanderTask();
        }
        return new SearchWithinBiomeTask(BiomeKeys.DESERT);
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (templeLocator != null) {
            templeLocator.close();
            templeLocator = null;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LocateDesertTempleTask;
    }

    @Override
    protected String toDebugString() {
        return "Searchin' for temples";
    }

    @Override
    public boolean isFinished() {
        return AltoClef.getInstance().getPlayer().getBlockPos().equals(_finalPos);
    }
}
