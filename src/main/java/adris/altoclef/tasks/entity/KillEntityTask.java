package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.BlazeEntity;

import java.util.Objects;
import java.util.Optional;

/**
 * Kill a specific entity
 */
public class KillEntityTask extends AbstractKillEntityTask {

    private final Entity target;
    private boolean behaviourAdjusted;

    public KillEntityTask(Entity entity) {
        target = entity;
    }

    public KillEntityTask(Entity entity, double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
        target = entity;
    }

    @Override
    protected Optional<Entity> getEntityTarget(AltoClef mod) {
        return Optional.of(target);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (target instanceof BlazeEntity) {
            AltoClef mod = AltoClef.getInstance();
            // Prevent risky bridging while pursuing blazes over fortress gaps.
            mod.getBehaviour().push();
            mod.getBehaviour().setBlockPlacePenalty(Double.POSITIVE_INFINITY);
            behaviourAdjusted = true;
        }
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        if (other instanceof KillEntityTask task) {
            return Objects.equals(task.target, target);
        }
        return false;
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (behaviourAdjusted) {
            AltoClef.getInstance().getBehaviour().pop();
            behaviourAdjusted = false;
        }
        super.onStop(interruptTask);
    }

    @Override
    protected String toDebugString() {
        return "Killing " + target.getType().getTranslationKey();
    }
}
