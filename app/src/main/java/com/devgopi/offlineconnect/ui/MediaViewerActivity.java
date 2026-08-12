package com.devgopi.offlineconnect.ui;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.devgopi.offlineconnect.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen image/video viewer with receiver-only save support. */
public final class MediaViewerActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "media_path";
    public static final String EXTRA_VIDEO = "media_video";
    public static final String EXTRA_CAN_SAVE = "media_can_save";
    private static final String TAG = "MediaViewerActivity";

    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private VideoView videoView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureDarkSystemBars();
        setContentView(R.layout.activity_media_viewer);
        applyInsets();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        boolean video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        boolean canSave = getIntent().getBooleanExtra(EXTRA_CAN_SAVE, false);
        if (path == null || !new File(path).isFile()) {
            finish();
            return;
        }

        findViewById(R.id.btnCloseMedia).setOnClickListener(view -> finish());
        View save = findViewById(R.id.btnSaveFullscreenMedia);
        save.setVisibility(canSave ? View.VISIBLE : View.GONE);
        save.setOnClickListener(view -> saveMedia(path, video));

        ImageView image = findViewById(R.id.fullscreenImage);
        videoView = findViewById(R.id.fullscreenVideo);
        if (video) {
            videoView.setVisibility(View.VISIBLE);
            MediaController controls = new MediaController(this);
            controls.setAnchorView(videoView);
            videoView.setMediaController(controls);
            videoView.setVideoPath(path);
            videoView.setOnPreparedListener(player -> {
                player.setLooping(false);
                videoView.start();
                controls.show(3_000);
            });
        } else {
            image.setVisibility(View.VISIBLE);
            image.setImageURI(Uri.fromFile(new File(path)));
        }
    }

    private void configureDarkSystemBars() {
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);
    }

    private void applyInsets() {
        View root = findViewById(R.id.mediaViewerRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void saveMedia(String path, boolean video) {
        fileExecutor.execute(() -> {
            String mime = video ? "video/mp4" : "image/jpeg";
            String extension = video ? ".mp4" : ".jpg";
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        "OfflineConnect_" + System.currentTimeMillis() + extension);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            (video ? Environment.DIRECTORY_MOVIES
                                    : Environment.DIRECTORY_PICTURES) + "/OfflineConnect");
                }
                Uri target = getContentResolver().insert(video
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IOException("Unable to create media file");
                try (FileInputStream input = new FileInputStream(path);
                     OutputStream output = getContentResolver().openOutputStream(target)) {
                    if (output == null) throw new IOException("Unable to open media destination");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
                runOnUiThread(() -> Toast.makeText(this, R.string.media_saved,
                        Toast.LENGTH_SHORT).show());
            } catch (IOException | SecurityException exception) {
                Log.e(TAG, "Could not save media", exception);
                runOnUiThread(() -> Toast.makeText(this, R.string.media_save_failed,
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override protected void onPause() {
        if (videoView != null && videoView.isPlaying()) videoView.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        fileExecutor.shutdownNow();
        super.onDestroy();
    }
}
