package com.devgopi.offlineconnect.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RecentDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsert(RecentDeviceEntity device);
    @Query("SELECT * FROM recent_devices ORDER BY lastConnectedAt DESC LIMIT 20")
    List<RecentDeviceEntity> getRecent();
}
