package adris.altoclef.cambridge;

import adris.altoclef.Debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;

final class FileCamBridgeTransport implements CamBridgeTransport {

    private final Path output;
    private final Object lock = new Object();

    FileCamBridgeTransport(Path output) throws IOException {
        this.output = output.toAbsolutePath();
        Path parent = this.output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(this.output, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void sendBatch(Collection<String> events) throws IOException {
        if (events.isEmpty()) {
            return;
        }
        try {
            synchronized (lock) {
                StringBuilder builder = new StringBuilder();
                for (String line : events) {
                    builder.append(line).append(System.lineSeparator());
                }
        Files.writeString(output,
            builder.toString(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
            }
        } catch (IOException ex) {
            Debug.logWarning("CamBridge file transport failed: " + ex.getMessage());
            throw ex;
        }
    }

    @Override
    public boolean isHealthy() {
        return Files.exists(output);
    }

    @Override
    public void close() {
        // Leave the collected telemetry on disk for post-run inspection.
    }

    Path getOutputPath() {
        return output;
    }
}
