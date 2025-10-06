package adris.altoclef.cambridge;

import adris.altoclef.Debug;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

final class UdpCamBridgeTransport implements CamBridgeTransport {

    private final DatagramSocket socket;
    private final InetAddress address;
    private final int port;
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    UdpCamBridgeTransport(String host, int port) throws IOException {
        this.port = port;
        try {
            this.socket = new DatagramSocket();
        } catch (SocketException e) {
            throw new IOException("Unable to open UDP socket", e);
        }
        this.socket.connect(InetAddress.getByName(host), port);
        this.address = this.socket.getInetAddress();
    }

    @Override
    public void sendBatch(Collection<String> events) throws IOException {
        if (!healthy.get()) {
            throw new IOException("UDP transport marked unhealthy");
        }
        for (String payload : events) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
            try {
                socket.send(packet);
            } catch (IOException e) {
                healthy.set(false);
                Debug.logWarning("CamBridge UDP send failed: " + e.getMessage());
                throw e;
            }
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy.get();
    }

    @Override
    public void close() {
        healthy.set(false);
        socket.close();
    }
}
