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

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    List<MessageEntity> getForPeer(String peerId);

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    void deleteForPeer(String peerId);
}
