package adris.altoclef.cambridge;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;

interface CamBridgeTransport extends Closeable {

    /**
     * Deliver a batch of already-serialized JSON lines to the remote subscriber.
     */
    void sendBatch(Collection<String> events) throws IOException;

    /**
     * @return true if the transport is ready to send data.
     */
    boolean isHealthy();
}
