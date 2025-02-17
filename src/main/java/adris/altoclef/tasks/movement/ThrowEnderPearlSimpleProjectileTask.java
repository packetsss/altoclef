package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class ThrowEnderPearlSimpleProjectileTask extends Task {

    private final TimerGame thrownTimer = new TimerGame(5);
    private final BlockPos target;

    private boolean thrown = false;

    public ThrowEnderPearlSimpleProjectileTask(BlockPos target) {
        this.target = target;
    }

    private static boolean cleanThrow(AltoClef mod, float yaw, float pitch) {
        Rotation rotation = new Rotation(yaw, -1 * pitch);
        float range = 3f;
        Vec3d delta = LookHelper.toVec3d(rotation).multiply(range);
        Vec3d start = LookHelper.getCameraPos(mod);
        return LookHelper.cleanLineOfSight(start.add(delta), range);
    }

    private static Rotation calculateThrowLook(AltoClef mod, BlockPos end) {
        Vec3d start = ProjectileHelper.getThrowOrigin(mod.getPlayer());
        Vec3d endCenter = WorldHelper.toVec3d(end);
        double gravity = ProjectileHelper.THROWN_ENTITY_GRAVITY_ACCEL;
        double speed = 1.5;
        float yaw = LookHelper.getLookRotation(mod, end).getYaw();
        double flatDistance = WorldHelper.distanceXZ(start, endCenter);
        double[] pitches = ProjectileHelper.calculateAnglesForSimpleProjectileMotion(start.y - endCenter.y, flatDistance, speed, gravity);
        double pitch = cleanThrow(mod, yaw, (float) pitches[0]) ? pitches[0] : pitches[1];
        return new Rotation(yaw, -1 * (float) pitch);
    }

    @Override
    protected void onStart(AltoClef mod) {
        thrownTimer.forceElapse();
        thrown = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // TODO: Unlikely/minor nitpick, but there could be other people throwing ender pearls, which would delay the bot.
        if (mod.getEntityTracker().entityFound(EnderPearlEntity.class)) {
            thrownTimer.reset();
        }
        if (thrownTimer.elapsed()) {
            if (mod.getSlotHandler().forceEquipItem(Items.ENDER_PEARL)) {
                Rotation lookTarget = calculateThrowLook(mod, target);
                LookHelper.lookAt(mod, lookTarget);
                if (LookHelper.isLookingAt(mod, lookTarget)) {
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                    thrown = true;
                    thrownTimer.reset();
                }
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return thrown && thrownTimer.elapsed() || (!thrown && !mod.getItemStorage().hasItem(Items.ENDER_PEARL));
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ThrowEnderPearlSimpleProjectileTask task) {
            return task.target.equals(target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Simple Ender Pearling to " + target;
    }
}
