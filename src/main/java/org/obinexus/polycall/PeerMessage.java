package org.obinexus.polycall;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * One message taken from a peer node's inbox: the sender's node id, the
 * message id and the exact payload bytes (binary-safe).
 */
public final class PeerMessage {
    private final String sender;
    private final String messageId;
    private final byte[] payload;

    public PeerMessage(String sender, String messageId, byte[] payload) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.payload = payload.clone();
    }

    /** The sending node's id. */
    public String sender() {
        return sender;
    }

    /** The message id (de-duplication key together with the sender). */
    public String messageId() {
        return messageId;
    }

    /** A copy of the payload bytes. */
    public byte[] payload() {
        return payload.clone();
    }

    /** Payload length in bytes. */
    public int length() {
        return payload.length;
    }

    /** The payload decoded as UTF-8. */
    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PeerMessage m && sender.equals(m.sender)
                && messageId.equals(m.messageId) && Arrays.equals(payload, m.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, messageId, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
        return "PeerMessage[from=" + sender + ", id=" + messageId + ", " + payload.length + " bytes]";
    }
}
