package com.devgopi.offlineconnect.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.devgopi.offlineconnect.model.Message;

import java.nio.charset.StandardCharsets;

/** On-device representation of a message. Message text is encrypted before storage. */
@Entity(tableName = "messages", indices = {@Index("peerId"), @Index("timestamp")})
public class MessageEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String peerId;
    @NonNull public String encryptedBody;
    public long timestamp;
    public boolean outgoing;
    public boolean deleted;
    public boolean starred;
    public boolean pinned;
    public boolean edited;
    @NonNull public String status;

    public MessageEntity(@NonNull String id, @NonNull String peerId,
                         @NonNull String encryptedBody, long timestamp,
                         boolean outgoing, boolean deleted, boolean starred, boolean pinned,
                         boolean edited,
                         @NonNull String status) {
        this.id = id;
        this.peerId = peerId;
        this.encryptedBody = encryptedBody;
        this.timestamp = timestamp;
        this.outgoing = outgoing;
        this.deleted = deleted;
        this.starred = starred;
        this.pinned = pinned;
        this.edited = edited;
        this.status = status;
    }

    public static MessageEntity from(Message message, String encryptedBody) {
        return new MessageEntity(message.getId(), message.getPeerId(), encryptedBody,
                message.getTimestamp(), message.isOutgoing(), false, false, false,
                message.isEdited(),
                message.getStatus().name());
    }

    /**
     * Stable metadata authenticated alongside the encrypted body.
     * Length-prefixing avoids ambiguous values even if a peer identifier contains separators.
     */
    public static String associatedData(String id, String peerId, long timestamp,
                                        boolean outgoing) {
        return lengthPrefixed(id) + lengthPrefixed(peerId) + timestamp + ":" + outgoing;
    }

    public String associatedData() {
        return associatedData(id, peerId, timestamp, outgoing);
    }

    private static String lengthPrefixed(String value) {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        return byteLength + ":" + value;
    }
}
