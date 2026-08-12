package com.devgopi.offlineconnect.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Full media is kept outside the message database; this row stores its private file reference. */
@Entity(tableName = "media_files")
public final class MediaEntity {
    @PrimaryKey @NonNull public String messageId;
    @NonNull public String filePath;
    @NonNull public String mimeType;
    public long sizeBytes;
    public long durationMs;

    public MediaEntity(@NonNull String messageId, @NonNull String filePath,
                       @NonNull String mimeType, long sizeBytes, long durationMs) {
        this.messageId = messageId;
        this.filePath = filePath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
    }
}
