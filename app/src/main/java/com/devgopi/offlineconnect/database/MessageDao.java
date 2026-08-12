package com.devgopi.offlineconnect.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MessageEntity message);

    @Update void update(MessageEntity message);
    @Delete void delete(MessageEntity message);

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND deleted = 0 ORDER BY timestamp ASC")
    List<MessageEntity> getForPeer(String peerId);

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND deleted = 0 " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    List<MessageEntity> getLatestForPeer(String peerId, int limit);

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND deleted = 0 " +
            "AND (timestamp < :beforeTimestamp OR " +
            "(timestamp = :beforeTimestamp AND id < :beforeId)) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit")
    List<MessageEntity> getOlderForPeer(String peerId, long beforeTimestamp, String beforeId,
                                        int limit);

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    MessageEntity getById(String messageId);

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    void deleteForPeer(String peerId);

    @Query("SELECT * FROM messages WHERE peerId = :peerId AND outgoing = 1 AND deleted = 0 AND status IN ('PENDING', 'FAILED') ORDER BY timestamp ASC")
    List<MessageEntity> getQueuedForPeer(String peerId);

    /** Messages authored on this device are the source of truth during peer synchronization. */
    @Query("SELECT * FROM messages WHERE peerId = :peerId AND outgoing = 1 AND deleted = 0 ORDER BY timestamp ASC")
    List<MessageEntity> getAuthoredForPeer(String peerId);

    @Query("SELECT id FROM messages WHERE peerId = :peerId AND deleted = 1")
    List<String> getDeletedIdsForPeer(String peerId);

    @Query("UPDATE messages SET deleted = 1 WHERE id = :messageId")
    void markDeleted(String messageId);

    @Query("INSERT OR IGNORE INTO messages (id, peerId, encryptedBody, timestamp, outgoing, deleted, starred, pinned, edited, status) " +
            "VALUES (:messageId, :peerId, '', 0, 0, 1, 0, 0, 0, 'RECEIVED')")
    void insertDeletionIfMissing(String messageId, String peerId);

    @Query("UPDATE messages SET starred = :starred WHERE id = :messageId")
    void setStarred(String messageId, boolean starred);

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :messageId")
    void setPinned(String messageId, boolean pinned);

    @Query("DELETE FROM messages WHERE id = :messageId")
    void deleteById(String messageId);
}
