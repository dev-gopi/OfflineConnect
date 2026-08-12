package com.devgopi.offlineconnect.model;

import androidx.annotation.NonNull;

import java.util.Objects;
import java.util.UUID;

/** Domain model used by the UI and transport layers. */
public final class Message {
    public enum Status { PENDING, SENT, DELIVERED, READ, RECEIVED, FAILED }

    private final String id;
    private final String peerId;
    private final String body;
    private final long timestamp;
    private final boolean outgoing;
    private final Status status;
    private final boolean edited;

    public Message(String id, @NonNull String peerId, @NonNull String body, long timestamp,
                   boolean outgoing, @NonNull Status status) {
        this(id, peerId, body, timestamp, outgoing, status, false);
    }

    public Message(String id, @NonNull String peerId, @NonNull String body, long timestamp,
                   boolean outgoing, @NonNull Status status, boolean edited) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
        this.peerId = Objects.requireNonNull(peerId, "peerId");
        this.body = Objects.requireNonNull(body, "body");
        this.timestamp = timestamp;
        this.outgoing = outgoing;
        this.status = Objects.requireNonNull(status, "status");
        this.edited = edited;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getPeerId() { return peerId; }
    @NonNull public String getBody() { return body; }
    public long getTimestamp() { return timestamp; }
    public boolean isOutgoing() { return outgoing; }
    @NonNull public Status getStatus() { return status; }
    public boolean isEdited() { return edited; }
}
