package com.devgopi.offlineconnect.database;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;

/** Utilities shared by chat and transports for media records and compact JPEG thumbnails. */
public final class MediaRepository {
    private static final int THUMBNAIL_SIDE = 320;
    private MediaRepository() { }

    public static MediaEntity find(Context context, String messageId) {
        return MediaDatabase.getInstance(context).mediaDao().getByMessageId(messageId);
    }

    public static void store(Context context, MediaEntity media) {
        MediaDatabase.getInstance(context).mediaDao().upsert(media);
    }

    public static void delete(Context context, String messageId) {
        MediaEntity media = find(context, messageId);
        if (media != null) {
            File file = new File(media.filePath);
            if (file.isFile() && !file.delete()) android.util.Log.w("MediaRepository",
                    "Could not delete media file");
        }
        MediaDatabase.getInstance(context).mediaDao().deleteByMessageId(messageId);
    }

    public static String createThumbnail(String path, boolean video) {
        Bitmap source = null;
        MediaMetadataRetriever retriever = null;
        try {
            if (video) {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(path);
                source = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } else {
                source = BitmapFactory.decodeFile(path);
            }
            if (source == null) return "";
            float scale = Math.min(1f, THUMBNAIL_SIDE
                    / (float) Math.max(source.getWidth(), source.getHeight()));
            Bitmap thumbnail = Bitmap.createScaledBitmap(source,
                    Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 68, bytes);
            if (thumbnail != source) thumbnail.recycle();
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } catch (RuntimeException exception) {
            android.util.Log.w("MediaRepository", "Could not create thumbnail", exception);
            return "";
        } finally {
            if (source != null && !source.isRecycled()) source.recycle();
            if (retriever != null) try { retriever.release(); }
            catch (java.io.IOException | RuntimeException ignored) { }
        }
    }
}
