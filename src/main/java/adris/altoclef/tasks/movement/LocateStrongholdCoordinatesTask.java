package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.multiversion.blockpos.BlockPosVer;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Optional;

public class LocateStrongholdCoordinatesTask extends Task {

    private static final int EYE_RETHROW_DISTANCE = 10; // target distance to stronghold guess before rethrowing

    private static final int SECOND_EYE_THROW_DISTANCE = 30; // target distance between first throw and second throw

    private final int targetEyes;
    private final int minimumEyes;
    private final TimerGame throwTimer = new TimerGame(5);
    private LocateStrongholdCoordinatesTask.EyeDirection cachedEyeDirection = null;
    private LocateStrongholdCoordinatesTask.EyeDirection cachedEyeDirection2 = null;
    private Entity currentThrownEye = null;
    private Vec3i strongholdEstimatePos = null;

    public LocateStrongholdCoordinatesTask(int targetEyes, int minimumEyes) {
        this.targetEyes = targetEyes;
        this.minimumEyes = minimumEyes;
    }

    public LocateStrongholdCoordinatesTask(int targetEyes) {
        this(targetEyes, 12);
    }


    @SuppressWarnings("UnnecessaryLocalVariable")
    static Vec3i calculateIntersection(Vec3d start1, Vec3d direction1, Vec3d start2, Vec3d direction2) {
        Vec3d s1 = start1;
        Vec3d s2 = start2;
        Vec3d d1 = direction1;
        Vec3d d2 = direction2;
        // Solved for s1 + d1 * t1 = s2 + d2 * t2
        double t2 = ((d1.z * s2.x) - (d1.z * s1.x) - (d1.x * s2.z) + (d1.x * s1.z)) / ((d1.x * d2.z) - (d1.z * d2.x));
        BlockPos blockPos = BlockPosVer.ofFloored(start2.add(direction2.multiply(t2)));
        return new Vec3i(blockPos.getX(), 0, blockPos.getZ());
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    public boolean isSearching() {
        return cachedEyeDirection != null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to overworld");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // Pick up eye if we need to/want to.
        if (mod.getItemStorage().getItemCount(Items.ENDER_EYE) < minimumEyes && mod.getEntityTracker().itemDropped(Items.ENDER_EYE) &&
                !mod.getEntityTracker().entityFound(EyeOfEnderEntity.class)) {
            setDebugState("Picking up dropped ender eye.");
            return new PickupDroppedItemTask(Items.ENDER_EYE, targetEyes);
        }

        // Handle thrown eye
        if (mod.getEntityTracker().entityFound(EyeOfEnderEntity.class)) {
            if (currentThrownEye == null || !currentThrownEye.isAlive()) {
                Debug.logMessage("New eye direction");
                Debug.logMessage(currentThrownEye==null?"null":"is not alive");
                List<EyeOfEnderEntity> enderEyes = mod.getEntityTracker().getTrackedEntities(EyeOfEnderEntity.class);
                if (!enderEyes.isEmpty()) {
                    for (EyeOfEnderEntity enderEye : enderEyes) {
                        currentThrownEye = enderEye;
                    }
                }
                if (cachedEyeDirection2 != null) {
                    cachedEyeDirection = null;
                    cachedEyeDirection2 = null;
                } else if (cachedEyeDirection == null) {
                    cachedEyeDirection = new LocateStrongholdCoordinatesTask.EyeDirection(currentThrownEye.getPos());
                } else {
                    cachedEyeDirection2 = new LocateStrongholdCoordinatesTask.EyeDirection(currentThrownEye.getPos());
                }
            }
            if (cachedEyeDirection2 != null) {
                cachedEyeDirection2.updateEyePos(currentThrownEye.getPos());
            } else if (cachedEyeDirection != null) {
                cachedEyeDirection.updateEyePos(currentThrownEye.getPos());
            }

            if (mod.getEntityTracker().getClosestEntity(EyeOfEnderEntity.class).isPresent() &&
                    !mod.getClientBaritone().getPathingBehavior().isPathing()) {
                LookHelper.lookAt(mod,
                        adris.altoclef.multiversion.entity.EntityHelper.getEyePos(mod.getEntityTracker().getClosestEntity(EyeOfEnderEntity.class).get()));
            }

            setDebugState("Waiting for eye to travel.");
            return null;
        }

        // Calculate stronghold position
        if (cachedEyeDirection2 != null && !mod.getEntityTracker().entityFound(EyeOfEnderEntity.class) && strongholdEstimatePos == null) {
            if (cachedEyeDirection2.getAngle() >= cachedEyeDirection.getAngle()) {
                Debug.logMessage("2nd eye thrown at wrong position, or points to different stronghold. Rethrowing");
                cachedEyeDirection = cachedEyeDirection2;
                cachedEyeDirection2 = null;
            } else {
                Vec3d throwOrigin = cachedEyeDirection.getOrigin();
                Vec3d throwOrigin2 = cachedEyeDirection2.getOrigin();
                Vec3d throwDelta = cachedEyeDirection.getDelta();
                Vec3d throwDelta2 = cachedEyeDirection2.getDelta();


                strongholdEstimatePos = calculateIntersection(throwOrigin, throwDelta, throwOrigin2, throwDelta2); // stronghold estimate
                Debug.logMessage("Stronghold is at " + (int) strongholdEstimatePos.getX() + ", " + (int) strongholdEstimatePos.getZ() + " (" + (int) mod.getPlayer().getPos().distanceTo(Vec3d.of(strongholdEstimatePos)) + " blocks away)");
            }
        }


        // Re-throw the eyes after reaching the estimation to get a more accurate estimate of where the stronghold is.
        if (strongholdEstimatePos != null) {
            if (((mod.getPlayer().getPos().distanceTo(Vec3d.of(strongholdEstimatePos)) < EYE_RETHROW_DISTANCE) && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD)) {
                strongholdEstimatePos = null;
                cachedEyeDirection = null;
                cachedEyeDirection2 = null;
            }
        }


        // Throw the eye since we don't have any eye info.
        if (!mod.getEntityTracker().entityFound(EyeOfEnderEntity.class) && strongholdEstimatePos == null) {
            if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
                setDebugState("Going to overworld.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
            if (!mod.getItemStorage().hasItem(Items.ENDER_EYE)) {
                setDebugState("Collecting eye of ender.");
                return TaskCatalogue.getItemTask(Items.ENDER_EYE, 1);
            }

            // First get to a proper throwing height
            if (cachedEyeDirection == null) {
                setDebugState("Throwing first eye.");
            } else {
                setDebugState("Throwing second eye.");
                double sqDist = mod.getPlayer().squaredDistanceTo(cachedEyeDirection.getOrigin());
                // If first eye thrown, go perpendicular from eye direction until a good distance away
                if (sqDist < SECOND_EYE_THROW_DISTANCE * SECOND_EYE_THROW_DISTANCE && cachedEyeDirection != null) {
                    return new GoInDirectionXZTask(cachedEyeDirection.getOrigin(), cachedEyeDirection.getDelta().rotateY((float) (Math.PI / 2)), 1);
                }
            }
            // Throw it
            if (mod.getSlotHandler().forceEquipItem(Items.ENDER_EYE)) {
                assert MinecraftClient.getInstance().interactionManager != null;
                if (throwTimer.elapsed()) {
                    if (LookHelper.tryAvoidingInteractable(mod)) {
                        MinecraftClient.getInstance().interactionManager.interactItem(mod.getPlayer(),MinecraftClient.getInstance().world,Hand.MAIN_HAND);
                        //MinecraftClient.getInstance().options.keyUse.setPressed(true);
                        throwTimer.reset();
                    }
                } else {
                    MinecraftClient.getInstance().interactionManager.stopUsingItem(mod.getPlayer());
                    //MinecraftClient.getInstance().options.keyUse.setPressed(false);
                }
            } else {
                Debug.logWarning("Failed to equip eye of ender to throw.");
            }
            return null;
        } else if (cachedEyeDirection != null && !cachedEyeDirection.hasDelta() ||
                cachedEyeDirection2 != null && !cachedEyeDirection2.hasDelta()) {
            setDebugState("Waiting for thrown eye to appear...");
            return null;
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    public Optional<BlockPos> getStrongholdCoordinates() {
        if (strongholdEstimatePos == null) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(strongholdEstimatePos));
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LocateStrongholdCoordinatesTask;
    }

    @Override
    protected String toDebugString() {
        return "Locating stronghold coordinates";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return strongholdEstimatePos != null;
    }

    // Represents the direction we need to travel to get to the stronghold.
    private static class EyeDirection {
        private final Vec3d start;
        private Vec3d end;

        public EyeDirection(Vec3d startPos) {
            start = startPos;
        }

        public void updateEyePos(Vec3d endPos) {
            end = endPos;
        }

        public Vec3d getOrigin() {
            return start;
        }

        public Vec3d getDelta() {
            if (end == null) return Vec3d.ZERO;
            return end.subtract(start);
        }

        public double getAngle() {
            if (end == null) return 0;
            return Math.atan2(getDelta().getX(), getDelta().getZ());
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        public boolean hasDelta() {
            return end != null && getDelta().lengthSquared() > 0.00001;
        }
    }
}
