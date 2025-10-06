package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

import java.util.ArrayList;
import java.util.Locale;

public class TaskRunner {

    private final ArrayList<TaskChain> chains = new ArrayList<>();
    private final AltoClef mod;
    private boolean active;

    private TaskChain cachedCurrentTaskChain = null;
    private float cachedCurrentPriority = Float.NEGATIVE_INFINITY;

    public String statusReport = " (no chain running) ";

    public TaskRunner(AltoClef mod) {
        this.mod = mod;
        active = false;
    }

    public void tick() {
        if (!active || !AltoClef.inGame()) {
            statusReport = " (no chain running) ";
            return;
        }

        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : chains) {
            if (!chain.isActive()) continue;
            float priority = chain.getPriority();
            if (priority > maxPriority) {
                maxPriority = priority;
                maxChain = chain;
            }
        }
        boolean chainChanged = cachedCurrentTaskChain != maxChain;

    if (chainChanged) {
            String prevName = cachedCurrentTaskChain != null ? cachedCurrentTaskChain.getName() : "<none>";
            String prevContext = cachedCurrentTaskChain != null ? cachedCurrentTaskChain.getDebugContext() : "";
            float prevPriority = cachedCurrentTaskChain != null ? cachedCurrentPriority : Float.NEGATIVE_INFINITY;
            String nextName = maxChain != null ? maxChain.getName() : "<none>";
            String nextContext = maxChain != null ? maxChain.getDebugContext() : "";
        Debug.logMessage(String.format(Locale.ROOT,
            "[TaskRunner] Switch %s(p=%.1f) -> %s(p=%.1f) | prev={%s} | next={%s}",
            prevName,
            prevPriority,
            nextName,
            maxPriority,
            prevContext,
            nextContext), false);
        }

        if (cachedCurrentTaskChain != null && maxChain != cachedCurrentTaskChain) {
            cachedCurrentTaskChain.onInterrupt(maxChain);
        }

        cachedCurrentTaskChain = maxChain;
        cachedCurrentPriority = maxPriority;

        if (maxChain != null) {
            statusReport = "Chain: "+maxChain.getName() + ", priority: "+maxPriority;
            maxChain.tick();
        } else {
            statusReport = " (no chain running) ";
        }
    }

    public void addTaskChain(TaskChain chain) {
        chains.add(chain);
    }

    public void enable() {
        if (!active) {
            mod.getBehaviour().push();
            mod.getBehaviour().setPauseOnLostFocus(false);
        }
        active = true;
    }

    public void disable() {
        if (active) {
            mod.getBehaviour().pop();
            Debug.logMessage("Stopped");
        }
        for (TaskChain chain : chains) {
            chain.stop();
        }
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public TaskChain getCurrentTaskChain() {
        return cachedCurrentTaskChain;
    }

    // Kinda jank ngl
    public AltoClef getMod() {
        return mod;
    }
}
