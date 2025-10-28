package adris.altoclef.telemetry;

import adris.altoclef.Debug;
import adris.altoclef.util.time.TimerReal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.AbstractOutputStreamAppender;
import org.apache.logging.log4j.core.appender.OutputStreamManager;
import org.apache.logging.log4j.core.config.Configuration;

/**
 * Periodically truncates large log files so they only keep a small trailing window.
 */
public final class LogTrimManager {
    private static final int MAX_LINES = 3000;
    private static final double TRIM_INTERVAL_SECONDS = 600.0;
    private final TimerReal trimTimer = new TimerReal(TRIM_INTERVAL_SECONDS);
    private final List<Path> trackedFiles = new ArrayList<>();
    private final Set<Path> failedPaths = new HashSet<>();

    public LogTrimManager(Path runDirectory) {
        if (runDirectory != null) {
            trackedFiles.add(runDirectory.resolve("logs/latest.log"));
            trackedFiles.add(runDirectory.resolve("logs/baritone.log"));
        }
        trimTimer.forceElapse();
    }

    public void tick() {
        if (trackedFiles.isEmpty()) {
            return;
        }
        if (!trimTimer.elapsed()) {
            return;
        }
        trimTimer.reset();
        for (Path path : trackedFiles) {
            trimFile(path);
        }
    }

    private void trimFile(Path path) {
        if (path == null) {
            return;
        }
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return;
        }

        List<Appender> pausedAppenders = pauseAppenders(path);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ArrayDeque<String> tail = new ArrayDeque<>(MAX_LINES + 1);
            int lineCount = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                String sanitized = sanitize(line);
                tail.addLast(sanitized);
                if (tail.size() > MAX_LINES) {
                    tail.removeFirst();
                }
                lineCount++;
            }

            if (lineCount <= MAX_LINES) {
                return;
            }

            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                Iterator<String> iterator = tail.iterator();
                while (iterator.hasNext()) {
                    writer.write(iterator.next());
                    if (iterator.hasNext()) {
                        writer.newLine();
                    }
                }
            }
            failedPaths.remove(path);
            Debug.logInternal(String.format(Locale.ROOT,
                    "[LogTrim] Truncated %s to the last %d lines",
                    path.getFileName(),
                    MAX_LINES));
        } catch (IOException ex) {
            if (failedPaths.add(path)) {
                Debug.logInternal(String.format(Locale.ROOT,
                        "[LogTrim] Failed to trim %s: %s",
                        path,
                        ex.getMessage()));
            }
        } finally {
            resumeAppenders(pausedAppenders);
        }
    }

    private String sanitize(String line) {
        StringBuilder builder = null;
        int length = line.length();
        for (int i = 0; i < length; i++) {
            char c = line.charAt(i);
            if (c == '\u0000' || (Character.isISOControl(c) && c != '\t')) {
                if (builder == null) {
                    builder = new StringBuilder(length);
                    builder.append(line, 0, i);
                }
                continue;
            }
            if (builder != null) {
                builder.append(c);
            }
        }
        return builder == null ? line : builder.toString();
    }

    private List<Appender> pauseAppenders(Path target) {
        List<Appender> paused = new ArrayList<>();
        if (target == null) {
            return paused;
        }

        LoggerContext context;
        try {
            context = (LoggerContext) LogManager.getContext(false);
        } catch (Exception ignored) {
            return paused;
        }
        if (context == null) {
            return paused;
        }

        Path normalizedTarget = normalizePath(target);
        if (normalizedTarget == null) {
            return paused;
        }

        Configuration configuration = context.getConfiguration();
        if (configuration == null) {
            return paused;
        }

        for (Appender appender : configuration.getAppenders().values()) {
            Path appenderPath = resolveAppenderPath(appender);
            if (appenderPath == null) {
                continue;
            }
            Path normalizedAppender = normalizePath(appenderPath);
            if (normalizedAppender == null) {
                continue;
            }
            if (normalizedAppender.equals(normalizedTarget)) {
                try {
                    appender.stop();
                    paused.add(appender);
                } catch (Exception ignored) {
                    // Failing to stop isn't fatal; continue without pausing.
                }
            }
        }
        return paused;
    }

    private void resumeAppenders(List<Appender> appenders) {
        if (appenders == null || appenders.isEmpty()) {
            return;
        }
        for (Appender appender : appenders) {
            try {
                appender.start();
            } catch (Exception ignored) {
                // If we fail to restart, Log4j will recreate on demand; avoid spamming errors here.
            }
        }
    }

    private Path resolveAppenderPath(Appender appender) {
        if (!(appender instanceof AbstractOutputStreamAppender)) {
            return null;
        }
        OutputStreamManager manager = ((AbstractOutputStreamAppender<?>) appender).getManager();
        if (manager == null) {
            return null;
        }
        String fileName = extractFileName(manager);
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        return toPath(fileName);
    }

    private String extractFileName(OutputStreamManager manager) {
        try {
            Method getter = manager.getClass().getMethod("getFileName");
            Object value = getter.invoke(manager);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Path toPath(String fileName) {
        try {
            return Paths.get(fileName);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private Path normalizePath(Path path) {
        try {
            if (Files.exists(path)) {
                return path.toRealPath().normalize();
            }
        } catch (IOException ignored) {
            // Fall back to absolute normalization below
        }
        return path.toAbsolutePath().normalize();
    }
}
