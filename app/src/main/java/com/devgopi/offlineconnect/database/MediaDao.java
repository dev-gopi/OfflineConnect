package com.devgopi.offlineconnect.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MediaEntity media);

    @Query("SELECT * FROM media_files WHERE messageId = :messageId LIMIT 1")
    MediaEntity getByMessageId(String messageId);

    @Query("DELETE FROM media_files WHERE messageId = :messageId")
    void deleteByMessageId(String messageId);
}
