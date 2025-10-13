package adris.altoclef.eventbus.events;

import net.minecraft.network.message.MessageType;
import org.jetbrains.annotations.Nullable;

/**
 * Whenever chat appears
 */
public class ChatMessageEvent {
    private final String message;
    private final String senderName;
    private final @Nullable MessageType messageType;

    public ChatMessageEvent(String message, String senderName, @Nullable MessageType messageType) {
        this.message = message;
        this.senderName = senderName;
        this.messageType = messageType;
    }
    public String messageContent() {
        return message;
    }

    public String senderName() {
        return senderName;
    }

    public @Nullable MessageType messageType() {
        return messageType;
    }
}
