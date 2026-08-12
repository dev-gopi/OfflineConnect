package com.devgopi.offlineconnect.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.format.DateFormat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.devgopi.offlineconnect.R;
import com.devgopi.offlineconnect.communication.ConnectionManager;
import com.devgopi.offlineconnect.database.AppDatabase;
import com.devgopi.offlineconnect.database.MessageEntity;
import com.devgopi.offlineconnect.model.Device;
import com.devgopi.offlineconnect.model.Message;
import com.devgopi.offlineconnect.security.EncryptionManager;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chat UI with text messages, hold-to-record voice notes, and receipt states. */
public final class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_DEVICE = "com.devgopi.offlineconnect.DEVICE";
    private static final String TAG = "ChatActivity";
    private static final String VOICE_PREFIX = "voice://";
    private static final float CANCEL_DISTANCE_DP = 90f;

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, View> messageViews = new HashMap<>();
    private final Map<String, Message> messages = new HashMap<>();
    private final EncryptionManager encryption = new EncryptionManager();
    private ConnectionManager connectionManager;
    private TextView connectionStatus;
    private TextView recordingHint;
    private TextView emptyState;
    private EditText messageInput;
    private ImageButton sendButton;
    private LinearLayout messageContainer;
    private ScrollView messageScroll;
    private Device device;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File recordingFile;
    private long recordingStartedAt;
    private float recordingDownX;
    private boolean recording;
    private boolean cancelRecording;

    private final ActivityResultLauncher<String> microphonePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) Toast.makeText(this, R.string.microphone_permission,
                        Toast.LENGTH_LONG).show();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        applySystemBarInsets();
        device = readDevice();
        if (device == null) {
            Toast.makeText(this, R.string.invalid_device, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        configureConnection();
        configureComposer();
        loadMessages();
    }

    /** Keeps the composer above both system navigation and the on-screen keyboard. */
    private void applySystemBarInsets() {
        View root = findViewById(R.id.chatRoot);
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int safeBottom = Math.max(bars.bottom, keyboard.bottom);
            view.setPadding(initialLeft + bars.left, initialTop + bars.top,
                    initialRight + bars.right, initialBottom + safeBottom);
            if (windowInsets.isVisible(WindowInsetsCompat.Type.ime()) && messageScroll != null) {
                messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.txtPeerName)).setText(device.getName());
        connectionStatus = findViewById(R.id.txtConnectionStatus);
        recordingHint = findViewById(R.id.txtRecordingHint);
        emptyState = findViewById(R.id.txtConversationEmpty);
        messageInput = findViewById(R.id.editMessage);
        sendButton = findViewById(R.id.btnSend);
        messageContainer = findViewById(R.id.messageContainer);
        messageScroll = findViewById(R.id.scrollMessages);
    }

    private void configureConnection() {
        Button connect = findViewById(R.id.btnConnect);
        connectionManager = new ConnectionManager(this, new ConnectionManager.Listener() {
            @Override public void onStateChanged(ConnectionManager.State state) {
                connectionStatus.setText(state.name());
            }
            @Override public void onError(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
            }
            @Override public void onMessageReceived(Message message) {
                Message local = new Message(message.getId(), device.getId(), message.getBody(),
                        message.getTimestamp(), false, Message.Status.RECEIVED);
                showMessage(local);
                persistMessage(local);
            }
            @Override public void onReceipt(String messageId, Message.Status status) {
                updateMessageStatus(messageId, status);
            }
            @Override public void onSendFailed(String messageId, String reason) {
                updateMessageStatus(messageId, Message.Status.FAILED);
                if (reason != null) Toast.makeText(ChatActivity.this, reason,
                        Toast.LENGTH_SHORT).show();
            }
        });
        connectionManager.prepare(device);
        connect.setOnClickListener(view -> connectionManager.connect(device));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureComposer() {
        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                updateComposerIcon();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        sendButton.setOnClickListener(view -> sendText());
        sendButton.setOnTouchListener((view, event) -> {
            if (!messageInput.getText().toString().trim().isEmpty()) return false;
            return handleRecordingGesture(event);
        });
        updateComposerIcon();
    }

    private void updateComposerIcon() {
        boolean hasText = !messageInput.getText().toString().trim().isEmpty();
        sendButton.setImageResource(hasText ? R.drawable.ic_send : R.drawable.ic_mic);
        sendButton.setContentDescription(getString(hasText
                ? R.string.send_message : R.string.record_voice));
    }

    private boolean handleRecordingGesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                recordingDownX = event.getRawX();
                cancelRecording = false;
                if (!hasMicrophonePermission()) {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
                    return true;
                }
                startRecording();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (recording && event.getRawX() - recordingDownX >= dp(CANCEL_DISTANCE_DP)) {
                    cancelRecording = true;
                    recordingHint.setText(R.string.recording_cancelled);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (recording) finishRecording(cancelRecording);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (recording) finishRecording(true);
                return true;
            default:
                return false;
        }
    }

    private boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void startRecording() {
        recordingFile = new File(getFilesDir(), "voice_" + System.currentTimeMillis() + ".m4a");
        recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordingStartedAt = System.currentTimeMillis();
            messageInput.setVisibility(View.GONE);
            recordingHint.setText(R.string.recording_voice);
            recordingHint.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.swipe_right_cancel, Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Voice recording could not start", exception);
            releaseRecorder();
            deleteRecording();
            Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void finishRecording(boolean cancelled) {
        long duration = System.currentTimeMillis() - recordingStartedAt;
        try {
            recorder.stop();
        } catch (RuntimeException exception) {
            cancelled = true; // Very short or interrupted recordings are invalid.
            Log.w(TAG, "Discarding incomplete voice recording", exception);
        } finally {
            releaseRecorder();
            recording = false;
            recordingHint.setVisibility(View.GONE);
            messageInput.setVisibility(View.VISIBLE);
        }

        if (cancelled || duration < 500L) {
            deleteRecording();
            Toast.makeText(this, R.string.recording_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        queueMessage(VOICE_PREFIX + recordingFile.getAbsolutePath() + "|" + duration);
        recordingFile = null;
    }

    private void sendText() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        messageInput.setText("");
        queueMessage(text);
    }

    private void queueMessage(String body) {
        Message message = new Message(null, device.getId(), body, System.currentTimeMillis(),
                true, Message.Status.PENDING);
        showMessage(message);
        persistMessage(message);
        connectionManager.send(message);
    }

    private void persistMessage(Message message) {
        databaseExecutor.execute(() -> {
            try {
                String encryptedBody = encryption.encrypt(message.getBody());
                AppDatabase.getInstance(this).messageDao().upsert(
                        MessageEntity.from(message, encryptedBody));
            } catch (GeneralSecurityException | RuntimeException exception) {
                Log.e(TAG, "Could not persist outgoing message", exception);
                Message failed = copyWithStatus(message, Message.Status.FAILED);
                runOnUiThread(() -> showMessage(failed));
            }
        });
    }

    private void retryMessage(Message failed) {
        Message queued = copyWithStatus(failed, Message.Status.PENDING);
        showMessage(queued);
        persistMessage(queued);
        connectionManager.send(queued);
    }

    private void updateMessageStatus(String messageId, Message.Status status) {
        Message existing = messages.get(messageId);
        if (existing == null) return;
        Message updated = copyWithStatus(existing, status);
        showMessage(updated);
        persistMessage(updated);
    }

    private Message copyWithStatus(Message message, Message.Status status) {
        return new Message(message.getId(), message.getPeerId(), message.getBody(),
                message.getTimestamp(), message.isOutgoing(), status);
    }

    private void loadMessages() {
        databaseExecutor.execute(() -> {
            List<MessageEntity> stored = AppDatabase.getInstance(this)
                    .messageDao().getForPeer(device.getId());
            for (MessageEntity entity : stored) {
                try {
                    Message message = new Message(entity.id, entity.peerId,
                            encryption.decrypt(entity.encryptedBody), entity.timestamp,
                            entity.outgoing, Message.Status.valueOf(entity.status));
                    runOnUiThread(() -> showMessage(message));
                } catch (GeneralSecurityException | IllegalArgumentException exception) {
                    Log.e(TAG, "Skipping unreadable stored message", exception);
                }
            }
        });
    }

    private void showMessage(Message message) {
        messages.put(message.getId(), message);
        emptyState.setVisibility(View.GONE);
        View oldView = messageViews.get(message.getId());
        int oldIndex = oldView == null ? -1 : messageContainer.indexOfChild(oldView);
        if (oldView != null) messageContainer.removeView(oldView);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(8));
        bubble.setBackgroundResource(R.drawable.bg_message_outgoing);

        if (message.getBody().startsWith(VOICE_PREFIX)) {
            addVoiceContent(bubble, message.getBody().substring(VOICE_PREFIX.length()));
        } else {
            TextView body = new TextView(this);
            body.setText(message.getBody());
            body.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            body.setTextSize(16f);
            bubble.addView(body);
        }

        addMessageFooter(bubble, message);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = message.isOutgoing() ? Gravity.END : Gravity.START;
        params.setMargins(dp(36), dp(4), 0, dp(8));
        if (oldIndex >= 0) messageContainer.addView(bubble, oldIndex, params);
        else messageContainer.addView(bubble, params);
        messageViews.put(message.getId(), bubble);
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addMessageFooter(LinearLayout bubble, Message message) {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setOrientation(LinearLayout.HORIZONTAL);

        TextView details = new TextView(this);
        String time = DateFormat.getTimeFormat(this).format(new Date(message.getTimestamp()));
        String state = receiptDescription(message.getStatus());
        details.setText(String.format(Locale.getDefault(), "%s  %s %s", time,
                receiptText(message.getStatus()), state));
        details.setTextColor(receiptColor(message.getStatus()));
        details.setTextSize(12f);
        details.setContentDescription(time + ", " + state);
        footer.addView(details);

        if (message.getStatus() == Message.Status.FAILED && message.isOutgoing()) {
            Button retry = new Button(this);
            retry.setText(R.string.retry);
            retry.setTextColor(ContextCompat.getColor(this, R.color.primary));
            retry.setTextSize(12f);
            retry.setAllCaps(false);
            retry.setContentDescription(getString(R.string.retry_message));
            retry.setBackgroundColor(Color.TRANSPARENT);
            retry.setMinHeight(dp(40));
            retry.setMinWidth(dp(64));
            retry.setOnClickListener(view -> retryMessage(message));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            retryParams.setMarginStart(dp(8));
            footer.addView(retry, retryParams);
        }
        bubble.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addVoiceContent(LinearLayout bubble, String metadata) {
        int separator = metadata.lastIndexOf('|');
        String path = separator < 0 ? metadata : metadata.substring(0, separator);
        long duration = 0L;
        if (separator >= 0) {
            try { duration = Long.parseLong(metadata.substring(separator + 1)); }
            catch (NumberFormatException ignored) { }
        }

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton play = new ImageButton(this);
        play.setImageResource(R.drawable.ic_play);
        play.setBackgroundColor(Color.TRANSPARENT);
        play.setContentDescription(getString(R.string.play_voice));
        play.setOnClickListener(view -> playVoice(path));
        row.addView(play, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView label = new TextView(this);
        label.setText(String.format(Locale.getDefault(), "%d:%02d", duration / 60_000,
                (duration / 1_000) % 60));
        label.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        label.setTextSize(15f);
        row.addView(label);
        bubble.addView(row);
    }

    private void playVoice(String path) {
        releasePlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(path);
            player.setOnCompletionListener(ignored -> releasePlayer());
            player.prepare();
            player.start();
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Voice message playback failed", exception);
            releasePlayer();
            Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String receiptText(Message.Status status) {
        if (status == Message.Status.DELIVERED) return "✓✓";
        if (status == Message.Status.READ) return "✓✓";
        if (status == Message.Status.FAILED) return "!";
        return "✓";
    }

    private int receiptColor(Message.Status status) {
        int color = status == Message.Status.READ ? R.color.primary : R.color.text_secondary;
        return ContextCompat.getColor(this, color);
    }

    private String receiptDescription(Message.Status status) {
        if (status == Message.Status.READ) return getString(R.string.receipt_read);
        if (status == Message.Status.DELIVERED) return getString(R.string.receipt_delivered);
        if (status == Message.Status.SENT) return getString(R.string.receipt_sent);
        if (status == Message.Status.FAILED) return getString(R.string.receipt_failed);
        if (status == Message.Status.RECEIVED) return getString(R.string.receipt_received);
        return getString(R.string.receipt_pending);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void releaseRecorder() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    private void deleteRecording() {
        if (recordingFile != null && recordingFile.exists() && !recordingFile.delete()) {
            Log.w(TAG, "Could not delete cancelled recording");
        }
        recordingFile = null;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @SuppressWarnings("deprecation")
    private Device readDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getIntent().getSerializableExtra(EXTRA_DEVICE, Device.class);
        }
        return (Device) getIntent().getSerializableExtra(EXTRA_DEVICE);
    }

    @Override protected void onDestroy() {
        if (recording) finishRecording(true);
        releasePlayer();
        databaseExecutor.shutdown();
        if (connectionManager != null) connectionManager.close();
        super.onDestroy();
    }
}
