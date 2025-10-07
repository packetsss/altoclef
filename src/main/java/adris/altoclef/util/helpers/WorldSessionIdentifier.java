package adris.altoclef.util.helpers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.SaveProperties;

import java.util.Locale;

/**
 * Resolves a stable identifier for the current world/session so that persisted state can be
 * associated with the correct save or server.
 */
public final class WorldSessionIdentifier {

    private WorldSessionIdentifier() {
    }

    public static String currentWorldId() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return "unknown";
        }

        IntegratedServer integrated = client.getServer();
        if (integrated != null) {
            SaveProperties properties = integrated.getSaveProperties();
            if (properties != null) {
                String levelName = properties.getLevelName();
                if (levelName != null && !levelName.isBlank()) {
                    return sanitize("local-" + levelName);
                }
            }
            return "local-singleplayer";
        }

        ServerInfo info = client.getCurrentServerEntry();
        if (info != null) {
            String address = info.address == null || info.address.isBlank() ? info.name : info.address;
            return sanitize("server-" + address);
        }

        return "unknown";
    }

    private static String sanitize(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-_]+", "_");
    }
}
