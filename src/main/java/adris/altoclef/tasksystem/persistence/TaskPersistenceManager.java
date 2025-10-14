package adris.altoclef.tasksystem.persistence;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists the user's command/task queue so that AltoClef can resume work after a client restart.
 * <p>
 * The saved file records the currently running command, upcoming commands, and a rolling history of
 * recently completed commands. When the client is reloaded, any unfinished commands are re-played so the
 * bot continues where it left off.
 */
public class TaskPersistenceManager {

    private static final int MAX_COMPLETED_HISTORY = 64;
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final AltoClef mod;
    private final ObjectMapper mapper;
    private final Path stateFile;

    private final List<CommandRecord> queue = new CopyOnWriteArrayList<>();
    private final Deque<CommandRecord> completed = new ArrayDeque<>();
    private final Deque<String> resumeCommands = new ArrayDeque<>();

    private boolean resumeAdvertised = false;

    public TaskPersistenceManager(AltoClef mod) {
        this.mod = mod;
        mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path runDirectory = MinecraftClient.getInstance().runDirectory.toPath();
        stateFile = runDirectory.resolve(Paths.get("altoclef", "state", "task-state.json"));
        loadState();
    }

    public synchronized void enqueueCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String raw : commands) {
            String command = sanitize(raw);
            if (command.isEmpty()) continue;

            CommandRecord resumeRecord = findResumeRecord(command);
            if (resumeRecord != null) {
                resumeRecord.resumePending = false;
                resumeRecord.status = CommandStatus.QUEUED;
                resumeRecord.queuedAtMs = now;
                resumeRecord.startedAtMs = null;
                resumeRecord.completedAtMs = null;
                resumeRecord.lastError = null;
                resumeCommands.removeFirstOccurrence(command);
                continue;
            }

            queue.add(new CommandRecord(command, CommandStatus.QUEUED, now));
        }
        writeState();
    }

    public synchronized void notifyCommandStart(String command) {
        String sanitized = sanitize(command);
        if (sanitized.isEmpty()) return;
        long now = System.currentTimeMillis();

        CommandRecord record = findActiveCandidate(sanitized);
        if (record == null) {
            record = new CommandRecord(sanitized, CommandStatus.RUNNING, now);
            record.startedAtMs = now;
            queue.add(record);
        } else {
            record.status = CommandStatus.RUNNING;
            record.startedAtMs = now;
        }
        writeState();
    }

    public synchronized void notifyCommandComplete(String command, boolean success, String error) {
        String sanitized = sanitize(command);
        if (sanitized.isEmpty()) return;
        long now = System.currentTimeMillis();

        CommandRecord record = findRunningRecord(sanitized);
        if (record == null) {
            record = new CommandRecord(sanitized, success ? CommandStatus.COMPLETED : CommandStatus.FAILED, now);
            record.startedAtMs = now;
        } else {
            queue.remove(record);
            record.status = success ? CommandStatus.COMPLETED : CommandStatus.FAILED;
        }
        record.completedAtMs = now;
        record.lastError = success ? null : error;

        completed.addFirst(record.copyForHistory());
        while (completed.size() > MAX_COMPLETED_HISTORY) {
            completed.removeLast();
        }
        writeState();
    }

    public synchronized List<String> getCommandsToResume() {
        if (resumeAdvertised || resumeCommands.isEmpty()) {
            return List.of();
        }
        resumeAdvertised = true;
        String prefix = getCommandPrefix();
        List<String> result = new ArrayList<>(resumeCommands.size());
        for (String command : resumeCommands) {
            result.add(prefix + command);
        }
        return result;
    }

    private synchronized CommandRecord findResumeRecord(String command) {
        for (CommandRecord record : queue) {
            if (record.resumePending && record.command.equals(command)) {
                return record;
            }
        }
        return null;
    }

    private synchronized CommandRecord findActiveCandidate(String command) {
        for (CommandRecord record : queue) {
            if (record.command.equals(command) && record.status == CommandStatus.QUEUED) {
                return record;
            }
        }
        return null;
    }

    private synchronized CommandRecord findRunningRecord(String command) {
        for (CommandRecord record : queue) {
            if (record.command.equals(command) && record.status == CommandStatus.RUNNING) {
                return record;
            }
        }
        return null;
    }

    private void loadState() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(stateFile.toFile());
            JsonNode queueNode = root.get("queue");
            if (queueNode != null && queueNode.isArray()) {
                for (JsonNode entry : queueNode) {
                    String commandWithPrefix = entry.path("command").asText(null);
                    if (commandWithPrefix == null || commandWithPrefix.isBlank()) continue;
                    String command = sanitize(commandWithPrefix);
                    CommandStatus status = CommandStatus.fromString(entry.path("status").asText("queued"));
                    long queuedAt = entry.path("queued_at_ms").asLong(System.currentTimeMillis());
                    CommandRecord record = new CommandRecord(command, status, queuedAt);
                    if (entry.has("started_at_ms")) {
                        record.startedAtMs = entry.get("started_at_ms").asLong();
                    }
                    if (entry.has("completed_at_ms")) {
                        record.completedAtMs = entry.get("completed_at_ms").asLong();
                    }
                    if (entry.has("last_error")) {
                        record.lastError = entry.get("last_error").asText(null);
                    }
                    queue.add(record);
                }
            }

            JsonNode completedNode = root.get("completed");
            if (completedNode != null && completedNode.isArray()) {
                for (JsonNode entry : completedNode) {
                    String commandWithPrefix = entry.path("command").asText(null);
                    if (commandWithPrefix == null || commandWithPrefix.isBlank()) continue;
                    String command = sanitize(commandWithPrefix);
                    CommandStatus status = CommandStatus.fromString(entry.path("status").asText("completed"));
                    long queuedAt = entry.path("queued_at_ms").asLong(System.currentTimeMillis());
                    CommandRecord record = new CommandRecord(command, status, queuedAt);
                    if (entry.has("started_at_ms")) {
                        record.startedAtMs = entry.get("started_at_ms").asLong();
                    }
                    if (entry.has("completed_at_ms")) {
                        record.completedAtMs = entry.get("completed_at_ms").asLong();
                    }
                    if (entry.has("last_error")) {
                        record.lastError = entry.get("last_error").asText(null);
                    }
                    completed.add(record);
                }
            }
        } catch (IOException ex) {
            Debug.logWarning(String.format(Locale.ROOT,
                    "Failed to load task persistence file %s: %s",
                    stateFile,
                    ex.getMessage()));
        }

        resumeCommands.clear();
        for (CommandRecord record : queue) {
            if (record.status == CommandStatus.RUNNING || record.status == CommandStatus.QUEUED) {
                record.resumePending = true;
                resumeCommands.addLast(record.command);
            }
        }
    }

    private synchronized void writeState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            root.put("updated_at", ISO_FORMAT.format(Instant.now().atOffset(ZoneOffset.UTC)));
            root.put("queue", serializeRecords(queue));
            root.put("completed", serializeRecords(new ArrayList<>(completed)));
            TaskRunner runner = mod.getTaskRunner();
            if (runner != null) {
                Map<String, Object> runnerInfo = new LinkedHashMap<>();
                runnerInfo.put("status_report", runner.statusReport);
                runnerInfo.put("recent_transitions", runner.getRecentTaskTransitions());
                runnerInfo.put("recent_completed_tasks", runner.getRecentCompletedTasks());
                TaskChain current = runner.getCurrentTaskChain();
                runnerInfo.put("current_chain", current != null ? current.getName() : null);
                root.put("task_runner", runnerInfo);
            }
            mapper.writeValue(stateFile.toFile(), root);
        } catch (IOException ex) {
            Debug.logWarning(String.format(Locale.ROOT,
                    "Failed to write task persistence file %s: %s",
                    stateFile,
                    ex.getMessage()));
        }
    }

    private List<Map<String, Object>> serializeRecords(List<CommandRecord> records) {
        List<Map<String, Object>> serialized = new ArrayList<>(records.size());
        String prefix = getCommandPrefix();
        for (CommandRecord record : records) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("command", prefix + record.command);
            entry.put("status", record.status.toString());
            entry.put("queued_at_ms", record.queuedAtMs);
            entry.put("queued_at", ISO_FORMAT.format(Instant.ofEpochMilli(record.queuedAtMs).atOffset(ZoneOffset.UTC)));
            if (record.startedAtMs != null) {
                entry.put("started_at_ms", record.startedAtMs);
                entry.put("started_at", ISO_FORMAT.format(Instant.ofEpochMilli(record.startedAtMs).atOffset(ZoneOffset.UTC)));
            }
            if (record.completedAtMs != null) {
                entry.put("completed_at_ms", record.completedAtMs);
                entry.put("completed_at", ISO_FORMAT.format(Instant.ofEpochMilli(record.completedAtMs).atOffset(ZoneOffset.UTC)));
            }
            if (record.lastError != null) {
                entry.put("last_error", record.lastError);
            }
            serialized.add(entry);
        }
        return serialized;
    }

    private String sanitize(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        String prefix = getCommandPrefix();
        if (!prefix.isEmpty() && trimmed.startsWith(prefix)) {
            trimmed = trimmed.substring(prefix.length()).stripLeading();
        }
        return trimmed;
    }

    private String getCommandPrefix() {
        if (mod.getModSettings() != null && mod.getModSettings().getCommandPrefix() != null) {
            return mod.getModSettings().getCommandPrefix();
        }
        return "@";
    }

    private static final class CommandRecord {
        private final String command;
        private CommandStatus status;
        private long queuedAtMs;
        private Long startedAtMs;
        private Long completedAtMs;
        private String lastError;
        private transient boolean resumePending;

        private CommandRecord(String command, CommandStatus status, long queuedAtMs) {
            this.command = Objects.requireNonNull(command);
            this.status = status;
            this.queuedAtMs = queuedAtMs;
        }

        private CommandRecord copyForHistory() {
            CommandRecord copy = new CommandRecord(command, status, queuedAtMs);
            copy.startedAtMs = startedAtMs;
            copy.completedAtMs = completedAtMs;
            copy.lastError = lastError;
            return copy;
        }
    }

    private enum CommandStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED;

        private static CommandStatus fromString(String value) {
            if (value == null) {
                return QUEUED;
            }
            try {
                return CommandStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return QUEUED;
            }
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
