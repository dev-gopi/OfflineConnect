package com.devgopi.offlineconnect.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Process-wide Room database. Database work must be performed off the main thread. */
@Database(entities = {MessageEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    public abstract MessageDao messageDao();

    public static AppDatabase getInstance(Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "offline-connect.db").build();
                    instance = local;
                }
            }
        }
        return local;
    }
}
