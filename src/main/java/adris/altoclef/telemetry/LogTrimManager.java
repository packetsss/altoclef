package adris.altoclef.telemetry;

import adris.altoclef.Debug;
import adris.altoclef.util.time.TimerReal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ArrayDeque<String> tail = new ArrayDeque<>(MAX_LINES + 1);
            int lineCount = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
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
        }
    }
}
