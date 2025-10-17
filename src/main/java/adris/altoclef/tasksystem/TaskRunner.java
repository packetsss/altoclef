package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.WorldHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TaskRunner {

    private final ArrayList<TaskChain> chains = new ArrayList<>();
    private final AltoClef mod;
    private boolean active;

    private TaskChain cachedCurrentTaskChain = null;
    private float cachedCurrentPriority = Float.NEGATIVE_INFINITY;

    private final Deque<TaskTimelineEntry> taskTransitions = new ArrayDeque<>();
    private final Deque<Map<String, Object>> completedTaskHistory = new ArrayDeque<>();
    private final Map<Task, TaskLifetime> activeTaskLifetimes = new IdentityHashMap<>();

    private static final int MAX_TRANSITION_HISTORY = 64;
    private static final int MAX_COMPLETED_HISTORY = 128;

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
            recordTransition(prevName, prevPriority, prevContext, nextName, maxPriority, nextContext);
        }

        if (cachedCurrentTaskChain != null && maxChain != cachedCurrentTaskChain) {
            cachedCurrentTaskChain.onInterrupt(maxChain);
        }

        cachedCurrentTaskChain = maxChain;
        cachedCurrentPriority = maxPriority;

        if (maxChain != null) {
            statusReport = "Chain: "+maxChain.getName() + ", priority: "+maxPriority;
            maxChain.tick();
            updateTaskLifetimes(maxChain);
        } else {
            statusReport = " (no chain running) ";
            updateTaskLifetimes(null);
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

    public List<Map<String, Object>> getRecentTaskTransitions() {
        return taskTransitions.stream()
                .map(entry -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("tick", entry.tick());
                    map.put("from_chain", entry.fromChain());
                    map.put("from_priority", entry.fromPriority());
                    map.put("from_context", entry.fromContext());
                    map.put("to_chain", entry.toChain());
                    map.put("to_priority", entry.toPriority());
                    map.put("to_context", entry.toContext());
                    return map;
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<Map<String, Object>> getRecentCompletedTasks() {
        return new ArrayList<>(completedTaskHistory);
    }

    public void reset() {
        for (TaskChain chain : chains) {
            chain.stop();
        }
        cachedCurrentTaskChain = null;
        cachedCurrentPriority = Float.NEGATIVE_INFINITY;
        statusReport = " (no chain running) ";
        taskTransitions.clear();
        completedTaskHistory.clear();
        activeTaskLifetimes.clear();
    }

    private void recordTransition(String fromChain, float fromPriority, String fromContext,
                                  String toChain, float toPriority, String toContext) {
        long tick = WorldHelper.getTicks();
        taskTransitions.addLast(new TaskTimelineEntry(tick, fromChain, fromPriority, fromContext, toChain, toPriority, toContext));
        while (taskTransitions.size() > MAX_TRANSITION_HISTORY) {
            taskTransitions.removeFirst();
        }
    }

    private void updateTaskLifetimes(TaskChain activeChain) {
        long currentTick = WorldHelper.getTicks();
        Set<Task> currentTasks = new HashSet<>();
        if (activeChain != null) {
            String chainName = activeChain.getName();
            for (Task task : activeChain.getTasks()) {
                currentTasks.add(task);
                activeTaskLifetimes.computeIfAbsent(task, key -> new TaskLifetime(currentTick, chainName, task.getClass().getName()))
                        .updateLastKnownContext(task.toString(), chainName);
            }
        }

        Iterator<Map.Entry<Task, TaskLifetime>> iterator = activeTaskLifetimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Task, TaskLifetime> entry = iterator.next();
            Task task = entry.getKey();
            TaskLifetime lifetime = entry.getValue();
            if (!currentTasks.contains(task)) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("task", lifetime.lastContext != null ? lifetime.lastContext : task.toString());
                summary.put("class", lifetime.className);
                summary.put("chain", lifetime.lastKnownChain != null ? lifetime.lastKnownChain : lifetime.declaredChain);
                summary.put("started_tick", lifetime.startTick);
                summary.put("ended_tick", currentTick);
                summary.put("duration_ticks", Math.max(0, currentTick - lifetime.startTick));
                completedTaskHistory.addLast(summary);
                while (completedTaskHistory.size() > MAX_COMPLETED_HISTORY) {
                    completedTaskHistory.removeFirst();
                }
                iterator.remove();
            }
        }
    }

    private record TaskTimelineEntry(long tick, String fromChain, float fromPriority, String fromContext,
                                     String toChain, float toPriority, String toContext) {
    }

    private static final class TaskLifetime {
        private final long startTick;
        private final String declaredChain;
        private final String className;
        private String lastContext;
        private String lastKnownChain;

        private TaskLifetime(long startTick, String chainName, String className) {
            this.startTick = startTick;
            this.declaredChain = chainName;
            this.className = className;
            this.lastKnownChain = chainName;
        }

        private void updateLastKnownContext(String context, String chain) {
            this.lastContext = context;
            if (chain != null) {
                this.lastKnownChain = chain;
            }
        }
    }
}
