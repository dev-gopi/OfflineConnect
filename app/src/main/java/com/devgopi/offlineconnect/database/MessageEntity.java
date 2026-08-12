package com.devgopi.offlineconnect.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.devgopi.offlineconnect.model.Message;

/** On-device representation of a message. Message text is encrypted before storage. */
@Entity(tableName = "messages", indices = {@Index("peerId"), @Index("timestamp")})
public class MessageEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String peerId;
    @NonNull public String encryptedBody;
    public long timestamp;
    public boolean outgoing;
    @NonNull public String status;

    public MessageEntity(@NonNull String id, @NonNull String peerId,
                         @NonNull String encryptedBody, long timestamp,
                         boolean outgoing, @NonNull String status) {
        this.id = id;
        this.peerId = peerId;
        this.encryptedBody = encryptedBody;
        this.timestamp = timestamp;
        this.outgoing = outgoing;
        this.status = status;
    }

    public static MessageEntity from(Message message, String encryptedBody) {
        return new MessageEntity(message.getId(), message.getPeerId(), encryptedBody,
                message.getTimestamp(), message.isOutgoing(), message.getStatus().name());
    }
}
