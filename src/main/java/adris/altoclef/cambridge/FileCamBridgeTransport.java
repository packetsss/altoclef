package adris.altoclef.cambridge;

import adris.altoclef.Debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;

final class FileCamBridgeTransport implements CamBridgeTransport {

    private final Path output;

    FileCamBridgeTransport(Path output) throws IOException {
        this.output = output;
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, "[]", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void sendBatch(Collection<String> events) throws IOException {
        // Maintain atomic file drop by rewriting the entire file each tick.
        String joined = String.join("\n", events);
        try {
            Files.writeString(output, joined, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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
        try {
            Files.write(output, List.of(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException ignored) {
        }
    }
}
