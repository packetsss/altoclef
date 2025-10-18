package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.blockpos.BlockPosVer;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LocateStructureCommandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;
import java.util.function.Predicate;

public class CollectBlazeRodsTask extends ResourceTask {

    private static final double SPAWNER_BLAZE_RADIUS = 32;
    private static final double SPAWNER_CAMP_RADIUS = 12;
    private static final int SPAWNER_FLOOR_CLEAR_RADIUS = 2;
    private static final double SPAWNER_FORCEFIELD_RANGE = 10;
    private static final double TOO_LITTLE_HEALTH_BLAZE = 10;
    private static final int TOO_MANY_BLAZES = 5;
    private static final double FORTRESS_VERIFY_RANGE = 64;
    private static final double FORTRESS_ABSENCE_TIMEOUT_SECONDS = 12;
    private final int _count;
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
    private LocateStructureCommandHelper fortressLocator;
    private final TimerGame fortressAbsenceTimer = new TimerGame(FORTRESS_ABSENCE_TIMEOUT_SECONDS);
    private boolean fortressAbsenceTimerActive = false;
    private BlockPos _lastLocateTarget = null;

    // Why was this here???
    //private Entity _toKill;
    private BlockPos _foundBlazeSpawner = null;

    public CollectBlazeRodsTask(int count) {
        super(Items.BLAZE_ROD, count);
        _count = count;
    }

    private static boolean isHoveringAboveLavaOrTooHigh(AltoClef mod, Entity entity) {
        int MAX_HEIGHT = 11;
        for (BlockPos check = entity.getBlockPos(); entity.getBlockPos().getY() - check.getY() < MAX_HEIGHT; check = check.down()) {
            if (mod.getWorld().getBlockState(check).getBlock() == Blocks.LAVA) return true;
            if (WorldHelper.isSolidBlock(check)) return false;
        }
        return true;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        fortressLocator = new LocateStructureCommandHelper(mod,
            "minecraft:fortress",
            "fortress",
            Dimension.NETHER,
            45,
            10);
        mod.getMobDefenseChain().setForceFieldRange(SPAWNER_FORCEFIELD_RANGE);
        fortressAbsenceTimer.reset();
        fortressAbsenceTimerActive = false;
        _lastLocateTarget = null;
        _foundBlazeSpawner = null;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }

        if (fortressLocator != null) {
            fortressLocator.tick();
        }

        Optional<Entity> toKill = Optional.empty();
        // If there is a blaze, kill it.
        if (mod.getEntityTracker().entityFound(BlazeEntity.class)) {
            toKill = mod.getEntityTracker().getClosestEntity(BlazeEntity.class);
            if (toKill.isPresent()) {
                if (mod.getPlayer().getHealth() <= TOO_LITTLE_HEALTH_BLAZE &&
                        mod.getEntityTracker().getTrackedEntities(BlazeEntity.class).size() >= TOO_MANY_BLAZES) {
                    setDebugState("Running away as there are too many blazes nearby.");
                    return new RunAwayFromHostilesTask(15 * 2, true);
                }
            }

            if (_foundBlazeSpawner != null && toKill.isPresent()) {
                Entity kill = toKill.get();
                Vec3d nearest = kill.getPos();

                double sqDistanceToPlayer = nearest.squaredDistanceTo(mod.getPlayer().getPos());//_foundBlazeSpawner.getX(), _foundBlazeSpawner.getY(), _foundBlazeSpawner.getZ());
                // Ignore if the blaze is too far away.
                if (sqDistanceToPlayer > SPAWNER_BLAZE_RADIUS * SPAWNER_BLAZE_RADIUS) {
                    // If the blaze can see us it needs to go lol
                    BlockHitResult hit = mod.getWorld().raycast(new RaycastContext(mod.getPlayer().getCameraPosVec(1.0F), kill.getCameraPosVec(1.0F), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
                    if (hit != null && BlockPosVer.getSquaredDistance(hit.getBlockPos(),mod.getPlayer().getPos()) < sqDistanceToPlayer) {
                        toKill = Optional.empty();
                    }
                }
            }
        }
        if (toKill.isPresent() && toKill.get().isAlive() && !isHoveringAboveLavaOrTooHigh(mod, toKill.get())) {
            if (_foundBlazeSpawner != null) {
                Vec3d spawnerCenter = Vec3d.ofCenter(_foundBlazeSpawner);
                if (toKill.get().getPos().squaredDistanceTo(spawnerCenter) > SPAWNER_CAMP_RADIUS * SPAWNER_CAMP_RADIUS) {
                    toKill = Optional.empty();
                }
            }
            if (toKill.isPresent()) {
                setDebugState("Killing blaze");
                Predicate<Entity> safeToPursue = entity -> !isHoveringAboveLavaOrTooHigh(mod, entity);
                return new KillEntitiesTask(safeToPursue, toKill.get().getClass());
            }
        }


        // If the blaze spawner somehow isn't valid
        if (_foundBlazeSpawner != null && mod.getChunkTracker().isChunkLoaded(_foundBlazeSpawner) && !isValidBlazeSpawner(mod, _foundBlazeSpawner)) {
            Debug.logMessage("Blaze spawner at " + _foundBlazeSpawner + " too far away or invalid. Re-searching.");
            _foundBlazeSpawner = null;
        }

        // If we have a blaze spawner, go near it.
        Optional<BlockPos> locatedFortress = fortressLocator != null ? fortressLocator.getLocatedPosition() : Optional.empty();

        if (locatedFortress.isPresent()) {
            BlockPos target = locatedFortress.get();
            if (_lastLocateTarget == null || !_lastLocateTarget.equals(target)) {
                _lastLocateTarget = target.toImmutable();
                fortressAbsenceTimerActive = false;
                fortressAbsenceTimer.reset();
                Debug.logInternal("[BlazeRods] Tracking fortress locate at " + target.toShortString());
            }

            double sqDistanceToTarget = BlockPosVer.getSquaredDistance(target, mod.getPlayer().getPos());
            if (sqDistanceToTarget <= FORTRESS_VERIFY_RANGE * FORTRESS_VERIFY_RANGE && mod.getChunkTracker().isChunkLoaded(target)) {
                boolean fortressDetected = mod.getBlockScanner().getNearestWithinRange(target, FORTRESS_VERIFY_RANGE, Blocks.NETHER_BRICKS).isPresent()
                        || mod.getBlockScanner().getNearestWithinRange(target, FORTRESS_VERIFY_RANGE, Blocks.SPAWNER).isPresent();

                if (fortressDetected) {
                    fortressAbsenceTimerActive = false;
                    fortressAbsenceTimer.reset();
                } else {
                    if (!fortressAbsenceTimerActive) {
                        fortressAbsenceTimer.reset();
                        fortressAbsenceTimerActive = true;
                        Debug.logInternal("[BlazeRods] Fortress verification timer started at " + target.toShortString());
                    } else if (fortressAbsenceTimer.elapsed()) {
                        Debug.logWarning("[BlazeRods] Locate fortress target " + target.toShortString() + " appears empty. Requesting a new locate.");
                        fortressLocator.invalidateLocatedPosition();
                        locatedFortress = Optional.empty();
                        fortressAbsenceTimerActive = false;
                        fortressAbsenceTimer.reset();
                        _lastLocateTarget = null;
                        _foundBlazeSpawner = null;
                    }
                }
            } else {
                fortressAbsenceTimerActive = false;
                fortressAbsenceTimer.reset();
            }
        } else {
            fortressAbsenceTimerActive = false;
            fortressAbsenceTimer.reset();
            _lastLocateTarget = null;
        }

        if (_foundBlazeSpawner != null) {
            if (!_foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4)) {
                setDebugState("Going to blaze spawner");
                return new GetToBlockTask(_foundBlazeSpawner.up(), false);
            } else {

                Optional<BlockPos> floorClearTarget = getNextSpawnerFloorBlockToClear(mod);
                if (floorClearTarget.isPresent()) {
                    setDebugState("Clearing floor around blaze spawner");
                    return new DestroyBlockTask(floorClearTarget.get());
                }

                Optional<BlockPos> sideClearTarget = getNextSpawnerSideBlockToClear(mod);
                if (sideClearTarget.isPresent()) {
                    setDebugState("Opening side walls around blaze spawner");
                    return new DestroyBlockTask(sideClearTarget.get());
                }

                // Put out fire that might mess with us.
                Optional<BlockPos> nearestFire = mod.getBlockScanner().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
                if (nearestFire.isPresent()) {
                    setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                    return new PutOutFireTask(nearestFire.get());
                }

                setDebugState("Waiting near blaze spawner for blazes to spawn");
                return null;
            }
        } else {
            // Search for blaze
            Optional<BlockPos> pos = mod.getBlockScanner().getNearestBlock(blockPos->isValidBlazeSpawner(mod, blockPos),Blocks.SPAWNER);

            pos.ifPresent(blockPos -> _foundBlazeSpawner = blockPos);

            if (_foundBlazeSpawner == null) {
                if (locatedFortress.isPresent()) {
                    BlockPos target = locatedFortress.get();
                    setDebugState("Heading to located fortress at " + target.toShortString());
                    return new GetToXZTask(target.getX(), target.getZ(), Dimension.NETHER);
                }
                if (fortressLocator != null && !fortressLocator.isUnsupported()) {
                    setDebugState("Waiting on locate fortress response...");
                    return new TimeoutWanderTask();
                }
            }
        }

        // We need to find our fortress.
        setDebugState("Searching for fortress/Traveling around fortress");
        return _searcher;
    }

    // Trim the floor around the spawner so blazes drop straight down instead of pathing away.
    private Optional<BlockPos> getNextSpawnerFloorBlockToClear(AltoClef mod) {
        if (_foundBlazeSpawner == null || mod.getWorld() == null) {
            return Optional.empty();
        }
        BlockPos center = _foundBlazeSpawner;
        for (int dx = -SPAWNER_FLOOR_CLEAR_RADIUS; dx <= SPAWNER_FLOOR_CLEAR_RADIUS; dx++) {
            for (int dz = -SPAWNER_FLOOR_CLEAR_RADIUS; dz <= SPAWNER_FLOOR_CLEAR_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos candidate = center.add(dx, -1, dz);
                if (!mod.getChunkTracker().isChunkLoaded(candidate)) {
                    continue;
                }
                BlockState state = mod.getWorld().getBlockState(candidate);
                if (state.isAir() || !WorldHelper.canBreak(candidate)) {
                    continue;
                }
                if (!isSpawnerFloorBlock(state)) {
                    continue;
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<BlockPos> getNextSpawnerSideBlockToClear(AltoClef mod) {
        if (_foundBlazeSpawner == null || mod.getWorld() == null) {
            return Optional.empty();
        }
        BlockPos center = _foundBlazeSpawner;
        for (int dx = -SPAWNER_FLOOR_CLEAR_RADIUS; dx <= SPAWNER_FLOOR_CLEAR_RADIUS; dx++) {
            for (int dz = -SPAWNER_FLOOR_CLEAR_RADIUS; dz <= SPAWNER_FLOOR_CLEAR_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos candidate = center.add(dx, 0, dz);
                if (!mod.getChunkTracker().isChunkLoaded(candidate)) {
                    continue;
                }
                BlockState state = mod.getWorld().getBlockState(candidate);
                if (state.isAir() || !WorldHelper.canBreak(candidate)) {
                    continue;
                }
                if (!isSpawnerSideBlock(state)) {
                    continue;
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean isSpawnerFloorBlock(BlockState state) {
        return state.isOf(Blocks.NETHER_BRICKS)
                || state.isOf(Blocks.NETHER_BRICK_SLAB)
                || state.isOf(Blocks.NETHER_BRICK_STAIRS);
    }

    private boolean isSpawnerSideBlock(BlockState state) {
        return state.isOf(Blocks.NETHER_BRICKS)
                || state.isOf(Blocks.CRACKED_NETHER_BRICKS)
                || state.isOf(Blocks.CHISELED_NETHER_BRICKS)
                || state.isOf(Blocks.NETHER_BRICK_STAIRS)
                || state.isOf(Blocks.NETHER_BRICK_SLAB);
    }

    private boolean isValidBlazeSpawner(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            // If unloaded, go to it. Unless it's super far away.
            return false;
            //return pos.isWithinDistance(mod.getPlayer().getPos(),3000);
        }
        return WorldHelper.getSpawnerEntity(pos) instanceof BlazeEntity;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        if (fortressLocator != null) {
            fortressLocator.close();
            fortressLocator = null;
        }
        mod.getMobDefenseChain().resetForceField();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectBlazeRodsTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect blaze rods - "+ AltoClef.getInstance().getItemStorage().getItemCount(Items.BLAZE_ROD)+"/"+_count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
