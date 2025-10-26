package adris.altoclef.telemetry;

import adris.altoclef.Debug;
import adris.altoclef.util.time.TimerReal;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Periodically truncates large log files so they only keep a small trailing window.
 */
public final class LogTrimManager {
    private static final int MAX_LINES = 3000;
    private static final double TRIM_INTERVAL_SECONDS = 600.0;
    private static final int BUFFER_SIZE = 128 * 1024;

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

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            long startOffset = findStartOffset(raf, MAX_LINES);
            if (startOffset <= 0) {
                return;
            }
            rewriteFromOffset(raf, startOffset);
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

    private static long findStartOffset(RandomAccessFile raf, int maxLines) throws IOException {
        long length = raf.length();
        if (length <= 0 || maxLines <= 0) {
            return -1L;
        }

        long position = length - 1;
        int lineCount = 0;
        long candidateStart = -1L;

        while (position >= 0) {
            raf.seek(position);
            int value = raf.read();
            if (value == '\n') {
                if (position < length - 1) {
                    lineCount++;
                    if (lineCount == maxLines) {
                        candidateStart = position + 1;
                    } else if (lineCount > maxLines) {
                        return position + 1;
                    }
                }
            }
            position--;
        }

        if (length > 0) {
            lineCount++;
            if (lineCount > maxLines) {
                return candidateStart >= 0 ? candidateStart : 0L;
            }
        }

        return -1L;
    }

    private void rewriteFromOffset(RandomAccessFile raf, long startOffset) throws IOException {
        long length = raf.length();
        if (startOffset <= 0) {
            return;
        }
        long bytesToKeep = length - startOffset;
        if (bytesToKeep <= 0) {
            raf.setLength(0);
            return;
        }

        long readPos = startOffset;
        long writePos = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        while (bytesToKeep > 0) {
            int chunk = (int) Math.min(buffer.length, bytesToKeep);
            raf.seek(readPos);
            raf.readFully(buffer, 0, chunk);
            raf.seek(writePos);
            raf.write(buffer, 0, chunk);
            readPos += chunk;
            writePos += chunk;
            bytesToKeep -= chunk;
        }

        raf.setLength(writePos);
    }
}
