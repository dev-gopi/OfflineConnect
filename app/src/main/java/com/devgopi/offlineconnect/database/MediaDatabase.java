package com.devgopi.offlineconnect.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Separate database for local full-resolution/transfer media file records. */
@Database(entities = {MediaEntity.class}, version = 1, exportSchema = false)
public abstract class MediaDatabase extends RoomDatabase {
    private static volatile MediaDatabase instance;
    public abstract MediaDao mediaDao();

    public static MediaDatabase getInstance(Context context) {
        MediaDatabase local = instance;
        if (local == null) {
            synchronized (MediaDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(context.getApplicationContext(),
                            MediaDatabase.class, "offline-connect-media.db").build();
                    instance = local;
                }
            }
        }
        return local;
    }
}
