package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;

import java.util.Locale;

public abstract class SingleTaskChain extends TaskChain {

    protected Task mainTask = null;
    private boolean interrupted = false;

    private final AltoClef mod;

    public SingleTaskChain(TaskRunner runner) {
        super(runner);
        mod = runner.getMod();
    }

    private String taskSummary(Task task) {
        if (task == null) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "%s %s", task.getClass().getSimpleName(), task.toString());
    }

    @Override
    protected void onTick() {
        if (!isActive()) return;

        if (interrupted) {
            interrupted = false;
            if (mainTask != null) {
                mainTask.reset();
            }
        }

        if (mainTask != null) {
            if (mainTask.isFinished()) {
                Task finishedTask = mainTask;
                onTaskFinish(mod);
                if (mainTask != finishedTask) {
                    Debug.logMessage(String.format(Locale.ROOT,
                            "[Chain:%s] Task finished: %s",
                            getName(),
                            taskSummary(finishedTask)), false);
                }
            } else if (mainTask.stopped()) {
                onTaskFinish(mod);
            } else {
                mainTask.tick(this);
            }
        }
    }

    protected void onStop() {
        if (isActive() && mainTask != null) {
            mainTask.stop();
            mainTask = null;
        }
    }

    public void setTask(Task task) {
        if (mainTask != null && mainTask.equals(task)) {
            return;
        }

        Task previous = mainTask;
        if (previous == null && task == null) {
            return;
        }
        if (previous != null) {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[Chain:%s] Replacing task %s (active=%s, finished=%s) -> %s",
                    getName(),
                    taskSummary(previous),
                    previous.isActive(),
                    previous.isFinished(),
                    taskSummary(task)), false);
            previous.stop(task);
        } else if (task != null) {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[Chain:%s] Starting task %s",
                    getName(),
                    taskSummary(task)), false);
        } else {
            Debug.logMessage(String.format(Locale.ROOT,
                    "[Chain:%s] Clearing task queue",
                    getName()), false);
        }

        mainTask = task;
        if (task != null) task.reset();
    }


    @Override
    public boolean isActive() {
        return mainTask != null;
    }

    protected abstract void onTaskFinish(AltoClef mod);

    @Override
    public void onInterrupt(TaskChain other) {
        if (other != null) {
            Debug.logInternal("Chain Interrupted: " + this + " by " + other);
        }
        // Stop our task. When we're started up again, let our task know we need to run.
        interrupted = true;
        if (mainTask != null && mainTask.isActive()) {
            mainTask.interrupt(null);
        }
    }

    protected boolean isCurrentlyRunning(AltoClef mod) {
        return !interrupted && mainTask.isActive() && !mainTask.isFinished();
    }

    public Task getCurrentTask() {
        return mainTask;
    }

    @Override
    public String getDebugContext() {
        Task current = getCurrentTask();
        if (current == null) {
            return "task=<none>";
        }
        return "task=" + current + ", active=" + current.isActive() + ", finished=" + current.isFinished();
    }
}
