package adris.altoclef.cambridge;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

final class CompositeCamBridgeTransport implements CamBridgeTransport {

    private final List<CamBridgeTransport> delegates;

    CompositeCamBridgeTransport(List<CamBridgeTransport> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void sendBatch(Collection<String> events) throws IOException {
        IOException lastFailure = null;
        int successCount = 0;
        for (CamBridgeTransport delegate : delegates) {
            try {
                delegate.sendBatch(events);
                successCount++;
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }
        if (successCount == 0 && lastFailure != null) {
            throw lastFailure;
        }
    }

    @Override
    public boolean isHealthy() {
        return delegates.stream().anyMatch(CamBridgeTransport::isHealthy);
    }

    @Override
    public void close() throws IOException {
        IOException lastFailure = null;
        for (CamBridgeTransport delegate : delegates) {
            try {
                delegate.close();
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }
}
