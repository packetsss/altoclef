package adris.altoclef.tasks.squashed;

import adris.altoclef.tasks.ResourceTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class TypeSquasher<T extends ResourceTask> {

    private final List<T> tasks = new ArrayList<>();

    void add(T task) {
        tasks.add(task);
    }

    public List<ResourceTask> getSquashed() {
        if (tasks.isEmpty()) {
            // We're empty, don't run any logic.
            return Collections.emptyList();
        }
        return getSquashed(tasks);
    }

    protected abstract List<ResourceTask> getSquashed(List<T> tasks);
}
