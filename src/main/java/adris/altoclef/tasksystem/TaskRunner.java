package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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

    public List<ChainDiagnostics> getChainDiagnostics() {
        List<ChainDiagnostics> diagnostics = new ArrayList<>(chains.size());
        for (TaskChain chain : chains) {
            List<TaskDiagnostics> taskDiagnostics = chain.getTasks().stream()
                    .map(task -> new TaskDiagnostics(
                            task.getClass().getName(),
                            task.toString(),
                            task.isFinished(),
                            task.isActive(),
                            task.stopped()
                    ))
                    .collect(Collectors.toCollection(ArrayList::new));
            diagnostics.add(new ChainDiagnostics(
                    chain.getName(),
                    chain.isActive(),
                    chain == cachedCurrentTaskChain,
                    chain.getDebugContext(),
                    taskDiagnostics
            ));
        }
        return diagnostics;
    }

    // Kinda jank ngl
    public AltoClef getMod() {
        return mod;
    }

    public record ChainDiagnostics(String name, boolean active, boolean current, String debugContext,
                                   List<TaskDiagnostics> tasks) {
    }

    public record TaskDiagnostics(String className, String summary, boolean finished, boolean active,
                                  boolean stopped) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", className);
            map.put("summary", summary);
            map.put("finished", finished);
            map.put("active", active);
            map.put("stopped", stopped);
            return map;
        }
    }
}
