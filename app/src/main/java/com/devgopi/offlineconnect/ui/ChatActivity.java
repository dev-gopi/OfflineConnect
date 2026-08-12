package com.devgopi.offlineconnect.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.Uri;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.VideoView;
import android.text.format.DateFormat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResult;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.devgopi.offlineconnect.R;
import com.devgopi.offlineconnect.communication.ConnectionManager;
import com.devgopi.offlineconnect.database.AppDatabase;
import com.devgopi.offlineconnect.database.MessageEntity;
import com.devgopi.offlineconnect.database.MessageDao;
import com.devgopi.offlineconnect.database.MediaEntity;
import com.devgopi.offlineconnect.database.MediaRepository;
import com.devgopi.offlineconnect.model.Device;
import com.devgopi.offlineconnect.model.Message;
import com.devgopi.offlineconnect.security.EncryptionManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chat UI with text messages, hold-to-record voice notes, and receipt states. */
public final class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_DEVICE = "com.devgopi.offlineconnect.DEVICE";
    private static final String TAG = "ChatActivity";
    private static final String VOICE_PREFIX = "voice://";
    private static final String IMAGE_PREFIX = "image://";
    private static final String VIDEO_PREFIX = "video://";
    private static final String CONTACT_PREFIX = "contact://";
    private static final String LOCATION_PREFIX = "location://";
    private static final long MAX_MEDIA_BYTES = 16L * 1024L * 1024L;
    private static final float CANCEL_DISTANCE_DP = 90f;
    private static final int HISTORY_PAGE_SIZE = 40;
    private static final long LOCATION_TIMEOUT_MS = 12_000L;

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, View> messageViews = new HashMap<>();
    private final Map<String, Message> messages = new HashMap<>();
    private final java.util.Set<String> starredMessageIds = new HashSet<>();
    private final java.util.Set<String> pinnedMessageIds = new HashSet<>();
    private final EncryptionManager encryption = new EncryptionManager();
    private final android.os.Handler playbackHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler recordingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private ConnectionManager connectionManager;
    private TextView connectionStatus;
    private TextView recordingHint;
    private TextView recordingTimer;
    private TextView recordingAction;
    private View recordingPanel;
    private View recordingDot;
    private View emojiButton;
    private TextView emptyState;
    private EditText messageInput;
    private ImageButton sendButton;
    private Button connectButton;
    private LinearLayout messageContainer;
    private ScrollView messageScroll;
    private EditText searchInput;
    private ImageButton searchPreviousButton;
    private ImageButton searchNextButton;
    private TextView searchResultCount;
    private final List<View> searchMatches = new ArrayList<>();
    private int searchMatchIndex = -1;
    private String selectedMessageId;
    private ImageButton scrollBottomButton;
    private MessageViewFilter messageViewFilter = MessageViewFilter.ALL;
    private boolean loadingHistory;
    private boolean hasOlderHistory = true;
    private boolean initialHistoryLoaded;
    private long oldestLoadedTimestamp = Long.MAX_VALUE;
    private String oldestLoadedId = "\uffff";
    private boolean locating;
    private final android.os.Handler locationHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private enum MessageViewFilter { ALL, STARRED, PINNED }
    private Device device;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File recordingFile;
    private long recordingStartedAt;
    private float recordingDownX;
    private boolean recording;
    private boolean cancelRecording;
    private ImageButton activePlayButton;
    private SeekBar activeSeekBar;
    private TextView activeDurationLabel;
    private long activeTotalDuration;
    private float playbackSpeed = 1f;
    private final Runnable recordingTicker = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            recordingTimer.setText(formatDuration(
                    System.currentTimeMillis() - recordingStartedAt));
            recordingDot.animate().alpha(recordingDot.getAlpha() < 0.75f ? 1f : 0.35f)
                    .setDuration(220L).start();
            recordingHandler.postDelayed(this, 250L);
        }
    };

    private final ActivityResultLauncher<String> microphonePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) Toast.makeText(this, R.string.microphone_permission,
                        Toast.LENGTH_LONG).show();
            });
    private final ActivityResultLauncher<String> mediaPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) preparePickedMedia(uri);
            });
    private final ActivityResultLauncher<Intent> contactPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::handleContactResult);
    private final ActivityResultLauncher<String> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) shareCurrentLocation();
                else Toast.makeText(this, R.string.location_permission_required,
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
        configureSearchAndEmoji();
        configureChatMenu();
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
            if (windowInsets.isVisible(WindowInsetsCompat.Type.ime()) && messageScroll != null
                    && messageInput != null && messageInput.hasFocus()) {
                scrollMessagesToBottom();
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.txtPeerName)).setText(device.getName());
        connectionStatus = findViewById(R.id.txtConnectionStatus);
        recordingHint = findViewById(R.id.txtRecordingHint);
        recordingTimer = findViewById(R.id.txtRecordingTimer);
        recordingAction = findViewById(R.id.txtRecordingAction);
        recordingPanel = findViewById(R.id.recordingPanel);
        recordingDot = findViewById(R.id.recordingDot);
        emojiButton = findViewById(R.id.btnEmoji);
        emptyState = findViewById(R.id.txtConversationEmpty);
        messageInput = findViewById(R.id.editMessage);
        sendButton = findViewById(R.id.btnSend);
        messageContainer = findViewById(R.id.messageContainer);
        messageScroll = findViewById(R.id.scrollMessages);
        scrollBottomButton = findViewById(R.id.btnScrollBottom);
        scrollBottomButton.setOnClickListener(view -> scrollMessagesToBottom());
        findViewById(R.id.btnAttachment).setOnClickListener(view -> showAttachmentPicker());
        messageContainer.setVisibility(View.INVISIBLE);
        messageScroll.setOnScrollChangeListener((View view, int scrollX, int scrollY,
                                                  int oldScrollX, int oldScrollY) -> {
            if (initialHistoryLoaded && scrollY <= dp(24) && oldScrollY > scrollY) {
                loadOlderMessages();
            }
            scrollBottomButton.setVisibility(isNearMessageBottom() ? View.GONE : View.VISIBLE);
        });
    }

    private void configureConnection() {
        connectButton = findViewById(R.id.btnConnect);
        findViewById(R.id.btnBackChat).setOnClickListener(view -> finish());
        connectionManager = new ConnectionManager(this, new ConnectionManager.Listener() {
            @Override public void onStateChanged(ConnectionManager.State state) {
                int label = state == ConnectionManager.State.CONNECTED ? R.string.connected
                        : state == ConnectionManager.State.CONNECTING ? R.string.connecting
                        : R.string.disconnected;
                connectionStatus.setText(label);
                connectButton.setText(state == ConnectionManager.State.CONNECTED
                        ? R.string.connected : state == ConnectionManager.State.CONNECTING
                        ? R.string.connecting : R.string.connect);
                connectButton.setEnabled(state == ConnectionManager.State.DISCONNECTED);
                setComposerEnabled(state == ConnectionManager.State.CONNECTED);
                if (state == ConnectionManager.State.CONNECTED) synchronizeMessages();
            }
            @Override public void onError(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
            }
            @Override public void onMessageReceived(Message message) {
                Message local = new Message(message.getId(), device.getId(), message.getBody(),
                        message.getTimestamp(), false, Message.Status.RECEIVED,
                        message.isEdited());
                handleReceivedMessage(local);
            }
            @Override public void onMessageDeleted(String messageId) {
                applyDeletion(messageId, false);
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
        connectButton.setOnClickListener(view -> connectionManager.connect(device));
        setComposerEnabled(false);
    }

    private void configureSearchAndEmoji() {
        searchInput = findViewById(R.id.editChatSearch);
        searchPreviousButton = findViewById(R.id.btnSearchPrevious);
        searchNextButton = findViewById(R.id.btnSearchNext);
        searchResultCount = findViewById(R.id.txtSearchResultCount);
        SearchClearController.attach(searchInput, findViewById(R.id.btnClearChatSearch));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterMessages(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchPreviousButton.setOnClickListener(view -> moveToSearchMatch(-1));
        searchNextButton.setOnClickListener(view -> moveToSearchMatch(1));
        findViewById(R.id.btnEmoji).setOnClickListener(view -> {
            String[] emoji = {"😀", "😂", "❤️", "👍", "🙏", "🎉", "😢", "🔥"};
            new AlertDialog.Builder(this, R.style.ThemeOverlay_OfflineConnect_Dialog)
                    .setTitle(R.string.emoji)
                    .setItems(emoji, (dialog, which) -> {
                        int start = Math.max(0, messageInput.getSelectionStart());
                        messageInput.getText().insert(start, emoji[which]);
                    }).show();
        });
    }

    private void configureChatMenu() {
        findViewById(R.id.btnChatMenu).setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(this, anchor);
            popup.getMenu().add(0, 1, 0, R.string.view_all_messages).setCheckable(true);
            popup.getMenu().add(0, 2, 1, R.string.view_starred_messages).setCheckable(true);
            popup.getMenu().add(0, 3, 2, R.string.view_pinned_messages).setCheckable(true);
            int checked = messageViewFilter == MessageViewFilter.ALL ? 1
                    : messageViewFilter == MessageViewFilter.STARRED ? 2 : 3;
            popup.getMenu().findItem(checked).setChecked(true);
            popup.setOnMenuItemClickListener(item -> {
                messageViewFilter = item.getItemId() == 2 ? MessageViewFilter.STARRED
                        : item.getItemId() == 3 ? MessageViewFilter.PINNED
                        : MessageViewFilter.ALL;
                rebuildSearchResults(searchInput.getText().toString(), true);
                return true;
            });
            popup.show();
        });
    }

    private void filterMessages(String query) {
        rebuildSearchResults(query, true);
    }

    private void rebuildSearchResults(String query, boolean selectFirst) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        searchMatches.clear();
        int visibleCount = 0;
        for (Map.Entry<String, View> entry : messageViews.entrySet()) {
            Message message = messages.get(entry.getKey());
            boolean textMessage = message != null && !isMediaMessage(message.getBody());
            String searchable = message == null ? "" : searchableBody(message.getBody());
            boolean included = messageViewFilter == MessageViewFilter.ALL
                    || messageViewFilter == MessageViewFilter.STARRED
                    && starredMessageIds.contains(entry.getKey())
                    || messageViewFilter == MessageViewFilter.PINNED
                    && pinnedMessageIds.contains(entry.getKey());
            boolean visible = included && (normalized.isEmpty() || (textMessage &&
                    searchable.toLowerCase(Locale.ROOT).contains(normalized)));
            entry.getValue().setVisibility(visible ? View.VISIBLE : View.GONE);
            entry.getValue().setForeground(null);
            if (textMessage) highlightMatchingText(entry.getValue(), message.getBody(), normalized);
            if (visible) visibleCount++;
            if (!normalized.isEmpty() && visible) searchMatches.add(entry.getValue());
        }
        emptyState.setVisibility(visibleCount == 0 ? View.VISIBLE : View.GONE);
        if (visibleCount == 0) emptyState.setText(!normalized.isEmpty()
                ? R.string.no_search_results
                : messageViewFilter == MessageViewFilter.STARRED
                ? R.string.no_starred_messages : messageViewFilter == MessageViewFilter.PINNED
                ? R.string.no_pinned_messages : R.string.conversation_empty);
        searchMatches.sort((first, second) -> Integer.compare(
                messageContainer.indexOfChild(first), messageContainer.indexOfChild(second)));
        searchMatchIndex = searchMatches.isEmpty() ? -1
                : selectFirst ? 0 : Math.min(Math.max(0, searchMatchIndex),
                        searchMatches.size() - 1);
        updateSearchNavigation(!normalized.isEmpty());
        highlightSelectedSearchBubble();
        if (selectFirst && searchMatchIndex >= 0) scrollToSearchMatch();
    }

    private void moveToSearchMatch(int direction) {
        if (searchMatches.size() < 2) return;
        searchMatchIndex = (searchMatchIndex + direction + searchMatches.size())
                % searchMatches.size();
        updateSearchNavigation(true);
        highlightSelectedSearchBubble();
        scrollToSearchMatch();
    }

    private void highlightMatchingText(View bubble, String body, String query) {
        TextView bodyView = bubble.findViewWithTag("searchable_message_body");
        if (bodyView == null) return;
        if (query.isEmpty()) {
            bodyView.setText(body);
            return;
        }
        SpannableString highlighted = new SpannableString(body);
        String normalizedBody = body.toLowerCase(Locale.ROOT);
        int start = normalizedBody.indexOf(query);
        while (start >= 0) {
            highlighted.setSpan(new BackgroundColorSpan(ContextCompat.getColor(this,
                            R.color.search_text_highlight)), start, start + query.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = normalizedBody.indexOf(query, start + query.length());
        }
        bodyView.setText(highlighted);
    }

    private void highlightSelectedSearchBubble() {
        for (Map.Entry<String, View> entry : messageViews.entrySet()) {
            applyMessageOverlay(entry.getKey(), entry.getValue());
        }
    }

    private void selectMessage(String messageId) {
        String previous = selectedMessageId;
        selectedMessageId = messageId;
        if (previous != null && messageViews.containsKey(previous)) {
            applyMessageOverlay(previous, messageViews.get(previous));
        }
        View selected = messageViews.get(messageId);
        if (selected != null) applyMessageOverlay(messageId, selected);
    }

    private void clearMessageSelection() {
        String previous = selectedMessageId;
        selectedMessageId = null;
        if (previous != null && messageViews.containsKey(previous)) {
            applyMessageOverlay(previous, messageViews.get(previous));
        }
    }

    private void applyMessageOverlay(String messageId, View bubble) {
        int color = Color.TRANSPARENT;
        if (messageId.equals(selectedMessageId)) {
            color = ContextCompat.getColor(this, R.color.message_selected_highlight);
        } else if (searchMatchIndex >= 0 && searchMatchIndex < searchMatches.size()
                && searchMatches.get(searchMatchIndex) == bubble) {
            color = ContextCompat.getColor(this, R.color.search_bubble_highlight);
        }
        bubble.setForeground(color == Color.TRANSPARENT ? null : new ColorDrawable(color));
    }

    private void updateSearchNavigation(boolean searching) {
        boolean multiple = searching && searchMatches.size() > 1;
        searchPreviousButton.setVisibility(multiple ? View.VISIBLE : View.GONE);
        searchNextButton.setVisibility(multiple ? View.VISIBLE : View.GONE);
        searchResultCount.setVisibility(searching ? View.VISIBLE : View.GONE);
        searchResultCount.setText(searchMatches.isEmpty() ? "0 / 0" : getString(
                R.string.search_result_count, searchMatchIndex + 1, searchMatches.size()));
    }

    private void scrollToSearchMatch() {
        View target = searchMatches.get(searchMatchIndex);
        messageScroll.post(() -> {
            messageScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(20)));
            target.animate().scaleX(1.025f).scaleY(1.025f).setDuration(120L)
                    .withEndAction(() -> target.animate().scaleX(1f).scaleY(1f)
                            .setDuration(120L).start()).start();
        });
    }

    /**
     * Keeps drafting and queueing available offline; connection state only changes the hint.
     * Authored history and queued messages are synchronized after reconnection.
     */
    private void setComposerEnabled(boolean connected) {
        messageInput.setEnabled(true);
        messageInput.setFocusableInTouchMode(true);
        sendButton.setEnabled(true);
        sendButton.setAlpha(1f);
        emojiButton.setEnabled(true);
        messageInput.setHint(connected ? R.string.message_hint : R.string.message_hint_offline);
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
                    recordingAction.setText(R.string.recording_cancel_hint);
                    recordingPanel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
            recorder.setMaxDuration(5 * 60 * 1_000);
            recorder.setMaxFileSize(16L * 1024L * 1024L);
            recorder.setOnInfoListener((mediaRecorder, what, extra) -> {
                if ((what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                        && recording) {
                    runOnUiThread(() -> {
                        if (!recording) return;
                        finishRecording(false);
                        Toast.makeText(this, R.string.recording_limit_reached,
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordingStartedAt = System.currentTimeMillis();
            messageInput.setVisibility(View.GONE);
            recordingHint.setVisibility(View.GONE);
            emojiButton.setVisibility(View.GONE);
            recordingTimer.setText(formatDuration(0L));
            recordingAction.setText(R.string.recording_release_hint);
            recordingDot.setAlpha(1f);
            recordingPanel.setVisibility(View.VISIBLE);
            recordingPanel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            recordingHandler.post(recordingTicker);
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
            recordingHandler.removeCallbacks(recordingTicker);
            releaseRecorder();
            recording = false;
            recordingDot.animate().cancel();
            recordingPanel.setVisibility(View.GONE);
            messageInput.setVisibility(View.VISIBLE);
            emojiButton.setVisibility(View.VISIBLE);
        }

        if (cancelled || duration < 500L) {
            deleteRecording();
            sendButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
        queuePreparedMessage(message);
    }

    private void queuePreparedMessage(Message message) {
        showMessage(message);
        persistMessage(message);
        connectionManager.send(message);
    }

    private void persistMessage(Message message) {
        databaseExecutor.execute(() -> {
            try {
                String encryptedBody = encryption.encrypt(message.getBody(),
                        MessageEntity.associatedData(message.getId(), message.getPeerId(),
                                message.getTimestamp(), message.isOutgoing()));
                MessageDao dao = AppDatabase.getInstance(this).messageDao();
                MessageEntity existing = dao.getById(message.getId());
                if (existing != null && existing.deleted) return;
                MessageEntity entity = MessageEntity.from(message, encryptedBody);
                if (existing != null) {
                    entity.starred = existing.starred;
                    entity.pinned = existing.pinned;
                }
                dao.upsert(entity);
            } catch (GeneralSecurityException | RuntimeException exception) {
                Log.e(TAG, "Could not persist outgoing message", exception);
                Message failed = copyWithStatus(message, Message.Status.FAILED);
                runOnUiThread(() -> showMessage(failed));
            }
        });
    }

    /** A persistent tombstone always wins over a replayed message, preventing resurrection. */
    private void handleReceivedMessage(Message message) {
        databaseExecutor.execute(() -> {
            MessageEntity existing = AppDatabase.getInstance(this).messageDao()
                    .getById(message.getId());
            if (existing != null && existing.deleted) {
                // Remind an out-of-date peer about the deletion it has not recorded yet.
                runOnUiThread(() -> connectionManager.sendDeletion(message.getId()));
                return;
            }
            runOnUiThread(() -> {
                showMessage(message);
                persistMessage(message);
            });
        });
    }

    private void retryMessage(Message failed) {
        Message queued = copyWithStatus(failed, Message.Status.PENDING);
        showMessage(queued);
        persistMessage(queued);
        connectionManager.send(queued);
    }

    /**
     * Replays this device's authored history after every new transport session. Message IDs make
     * the exchange idempotent: missing records are inserted and edited records replace stale ones
     * on the remote phone. Both phones run this method, producing a two-way synchronization.
     */
    private void synchronizeMessages() {
        connectionStatus.setText(R.string.syncing_messages);
        databaseExecutor.execute(() -> {
            MessageDao dao = AppDatabase.getInstance(this).messageDao();
            List<String> deletedIds = dao.getDeletedIdsForPeer(device.getId());
            for (String deletedId : deletedIds) {
                runOnUiThread(() -> connectionManager.sendDeletion(deletedId));
            }
            List<MessageEntity> authored = dao
                    .getAuthoredForPeer(device.getId());
            for (MessageEntity entity : authored) {
                try {
                    Message message = new Message(entity.id, entity.peerId,
                            encryption.decrypt(entity.encryptedBody, entity.associatedData()), entity.timestamp,
                            true, Message.Status.valueOf(entity.status), entity.edited);
                    message = migrateLegacyMedia(message);
                    if (encryption.needsMigration(entity.encryptedBody)) persistMessage(message);
                    // A voice file may have been cleared by Android or the user. Do not downgrade
                    // an otherwise valid historical receipt by attempting an impossible transfer.
                    if (isMissingVoiceFile(message.getBody())) continue;
                    Message synchronizedMessage = message;
                    runOnUiThread(() -> connectionManager.send(synchronizedMessage));
                } catch (GeneralSecurityException | IllegalArgumentException exception) {
                    Log.e(TAG, "Could not restore message for synchronization", exception);
                }
            }
            runOnUiThread(() -> {
                if (connectionManager.getState() == ConnectionManager.State.CONNECTED) {
                    connectionStatus.setText(R.string.connected);
                }
            });
        });
    }

    private boolean isMissingVoiceFile(String body) {
        if (!isMediaMessage(body)) return false;
        if (body.startsWith(IMAGE_PREFIX) || body.startsWith(VIDEO_PREFIX)) {
            MediaEntity media = MediaRepository.find(this, mediaId(body));
            return media == null || !new File(media.filePath).isFile();
        }
        String metadata = body.substring(8);
        int separator = metadata.lastIndexOf('|');
        String path = separator < 0 ? metadata : metadata.substring(0, separator);
        return !new File(path).isFile();
    }

    private void updateMessageStatus(String messageId, Message.Status status) {
        Message existing = messages.get(messageId);
        if (existing == null || !existing.isOutgoing()
                || receiptRank(status) < receiptRank(existing.getStatus())) return;
        Message updated = copyWithStatus(existing, status);
        showMessage(updated);
        persistMessage(updated);
    }

    /** Prevents reconnect synchronization from changing READ back to SENT or DELIVERED. */
    private int receiptRank(Message.Status status) {
        if (status == Message.Status.READ) return 4;
        if (status == Message.Status.DELIVERED) return 3;
        if (status == Message.Status.SENT) return 2;
        if (status == Message.Status.PENDING || status == Message.Status.FAILED) return 1;
        return 0;
    }

    private Message copyWithStatus(Message message, Message.Status status) {
        return new Message(message.getId(), message.getPeerId(), message.getBody(),
                message.getTimestamp(), message.isOutgoing(), status, message.isEdited());
    }

    private void loadMessages() {
        loadHistoryPage(true);
    }

    private void loadOlderMessages() {
        loadHistoryPage(false);
    }

    /** Loads newest history first, then prepends older pages without moving the viewport. */
    private void loadHistoryPage(boolean initial) {
        if (loadingHistory || (!initial && !hasOlderHistory)) return;
        loadingHistory = true;
        databaseExecutor.execute(() -> {
            MessageDao dao = AppDatabase.getInstance(this).messageDao();
            List<MessageEntity> stored = initial
                    ? dao.getLatestForPeer(device.getId(), HISTORY_PAGE_SIZE)
                    : dao.getOlderForPeer(device.getId(), oldestLoadedTimestamp, oldestLoadedId,
                            HISTORY_PAGE_SIZE);
            Collections.reverse(stored);
            List<LoadedMessage> restored = new ArrayList<>();
            for (MessageEntity entity : stored) {
                try {
                    Message message = new Message(entity.id, entity.peerId,
                            encryption.decrypt(entity.encryptedBody, entity.associatedData()), entity.timestamp,
                            entity.outgoing, Message.Status.valueOf(entity.status), entity.edited);
                    message = migrateLegacyMedia(message);
                    if (encryption.needsMigration(entity.encryptedBody)) persistMessage(message);
                    restored.add(new LoadedMessage(message, entity.starred, entity.pinned));
                } catch (GeneralSecurityException | IllegalArgumentException exception) {
                    Log.e(TAG, "Skipping unreadable stored message", exception);
                }
            }
            runOnUiThread(() -> applyHistoryPage(restored, stored.size(), initial));
        });
    }

    private void applyHistoryPage(List<LoadedMessage> restored, int databaseRowCount,
                                  boolean initial) {
        int previousHeight = messageContainer.getHeight();
        int previousScrollY = messageScroll.getScrollY();
        for (LoadedMessage item : restored) {
            if (item.starred) starredMessageIds.add(item.message.getId());
            if (item.pinned) pinnedMessageIds.add(item.message.getId());
            if (item.message.getTimestamp() < oldestLoadedTimestamp
                    || item.message.getTimestamp() == oldestLoadedTimestamp
                    && item.message.getId().compareTo(oldestLoadedId) < 0) {
                oldestLoadedTimestamp = item.message.getTimestamp();
                oldestLoadedId = item.message.getId();
            }
            showMessage(item.message, false);
        }
        hasOlderHistory = databaseRowCount == HISTORY_PAGE_SIZE;
        loadingHistory = false;
        if (initial) {
            initialHistoryLoaded = true;
            messageContainer.post(() -> {
                messageScroll.scrollTo(0, Math.max(0,
                        messageContainer.getHeight() - messageScroll.getHeight()));
                messageContainer.setVisibility(View.VISIBLE);
            });
        } else {
            messageContainer.post(() -> messageScroll.scrollTo(0,
                    previousScrollY + messageContainer.getHeight() - previousHeight));
        }
    }

    private static final class LoadedMessage {
        final Message message;
        final boolean starred;
        final boolean pinned;
        LoadedMessage(Message message, boolean starred, boolean pinned) {
            this.message = message;
            this.starred = starred;
            this.pinned = pinned;
        }
    }

    /** Imports path-based media created by older app versions into the separate media database. */
    private Message migrateLegacyMedia(Message message) {
        String body = message.getBody();
        if (!body.startsWith(IMAGE_PREFIX) && !body.startsWith(VIDEO_PREFIX)) return message;
        String oldPath = mediaId(body);
        if (!oldPath.startsWith("/")) return message;
        File file = new File(oldPath);
        if (!file.isFile()) return message;
        boolean video = body.startsWith(VIDEO_PREFIX);
        MediaRepository.store(this, new MediaEntity(message.getId(), file.getAbsolutePath(),
                video ? "video/mp4" : "image/jpeg", file.length(), 0L));
        String migratedBody = (video ? VIDEO_PREFIX : IMAGE_PREFIX) + message.getId() + "|"
                + MediaRepository.createThumbnail(file.getAbsolutePath(), video);
        Message migrated = new Message(message.getId(), message.getPeerId(), migratedBody,
                message.getTimestamp(), message.isOutgoing(), message.getStatus(),
                message.isEdited());
        persistMessage(migrated);
        return migrated;
    }

    private void showMessage(Message message) {
        showMessage(message, true);
    }

    private void showMessage(Message message, boolean scrollForNewMessage) {
        messages.put(message.getId(), message);
        emptyState.setVisibility(View.GONE);
        View oldView = messageViews.get(message.getId());
        boolean newMessageNearBottom = oldView == null && isNearMessageBottom();
        int oldIndex = oldView == null ? -1 : messageContainer.indexOfChild(oldView);
        if (oldView != null) messageContainer.removeView(oldView);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(8));
        bubble.setElevation(dp(1));
        bubble.setMinimumWidth(dp(96));
        bubble.setTag(message.getTimestamp());
        bubble.setBackgroundResource(message.isOutgoing() ? R.drawable.bg_message_outgoing
                : R.drawable.bg_message_incoming);

        if (pinnedMessageIds.contains(message.getId()) || starredMessageIds.contains(message.getId())) {
            TextView marker = new TextView(this);
            boolean pinned = pinnedMessageIds.contains(message.getId());
            boolean starred = starredMessageIds.contains(message.getId());
            marker.setText(pinned && starred ? R.string.pinned_starred_message_indicator
                    : pinned ? R.string.pinned_message_indicator
                    : R.string.starred_message_indicator);
            marker.setTextColor(ContextCompat.getColor(this, R.color.primary));
            marker.setTextSize(11f);
            marker.setTypeface(null, android.graphics.Typeface.BOLD);
            bubble.addView(marker);
        }

        if (message.getBody().startsWith(VOICE_PREFIX)) {
            addVoiceContent(bubble, message.getBody().substring(VOICE_PREFIX.length()));
        } else if (message.getBody().startsWith(IMAGE_PREFIX)) {
            addImageContent(bubble, mediaPath(message.getBody()), message.isOutgoing());
        } else if (message.getBody().startsWith(VIDEO_PREFIX)) {
            addVideoContent(bubble, mediaPath(message.getBody()), message.isOutgoing());
        } else if (message.getBody().startsWith(CONTACT_PREFIX)) {
            addContactContent(bubble, message.getBody());
        } else if (message.getBody().startsWith(LOCATION_PREFIX)) {
            addLocationContent(bubble, message.getBody());
        } else {
            TextView body = new TextView(this);
            body.setText(message.getBody());
            body.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            body.setTextSize(16f);
            body.setLineSpacing(dp(2), 1f);
            body.setTextIsSelectable(false);
            body.setTag("searchable_message_body");
            body.setMaxWidth(messageContentWidth());
            bubble.addView(body);
        }

        addMessageFooter(bubble, message);
        bindMessageLongPress(bubble, message);
        bubble.setOnHoverListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                    && !message.getId().equals(selectedMessageId)) {
                view.setForeground(new ColorDrawable(ContextCompat.getColor(this,
                        R.color.message_hover_highlight)));
            } else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                applyMessageOverlay(message.getId(), view);
            }
            return false;
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = message.isOutgoing() ? Gravity.END : Gravity.START;
        if (message.isOutgoing()) params.setMargins(dp(16), dp(4), 0, dp(8));
        else params.setMargins(0, dp(4), dp(16), dp(8));
        if (oldIndex >= 0) messageContainer.addView(bubble, oldIndex, params);
        else {
            int insertionIndex = messageContainer.getChildCount();
            for (int index = 0; index < messageContainer.getChildCount(); index++) {
                Object timestamp = messageContainer.getChildAt(index).getTag();
                if (timestamp instanceof Long && (Long) timestamp > message.getTimestamp()) {
                    insertionIndex = index;
                    break;
                }
            }
            messageContainer.addView(bubble, insertionIndex, params);
        }
        messageViews.put(message.getId(), bubble);
        applyMessageOverlay(message.getId(), bubble);
        if (searchInput != null && (searchInput.length() > 0
                || messageViewFilter != MessageViewFilter.ALL)) {
            rebuildSearchResults(searchInput.getText().toString(), false);
        }
        if (scrollForNewMessage && newMessageNearBottom && initialHistoryLoaded
                && (searchInput == null || searchInput.length() == 0)) {
            scrollMessagesToBottom();
        }
    }

    /** Makes long-press reliable even when a media preview or action child receives the touch. */
    private void bindMessageLongPress(View view, Message message) {
        view.setOnLongClickListener(target -> {
            selectMessage(message.getId());
            showMessageActions(message);
            return true;
        });
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                bindMessageLongPress(group.getChildAt(index), message);
            }
        }
    }

    private boolean isNearMessageBottom() {
        int remaining = messageContainer.getHeight() - messageScroll.getHeight()
                - messageScroll.getScrollY();
        return remaining <= dp(96);
    }

    /** Scrolls by coordinates so restoring history never steals focus from an EditText. */
    private void scrollMessagesToBottom() {
        messageScroll.post(() -> messageScroll.scrollTo(0,
                Math.max(0, messageContainer.getHeight() - messageScroll.getHeight())));
    }

    private void showMessageActions(Message message) {
        boolean text = !isMediaMessage(message.getBody()) && !isStructuredMessage(message.getBody());
        boolean editable = message.isOutgoing() && text;
        List<String> actions = new ArrayList<>();
        List<Integer> actionIds = new ArrayList<>();
        if (text) { actions.add(getString(R.string.copy_message)); actionIds.add(1); }
        if (editable) { actions.add(getString(R.string.edit_message)); actionIds.add(2); }
        actions.add(getString(starredMessageIds.contains(message.getId())
                ? R.string.unstar_message : R.string.star_message));
        actionIds.add(3);
        actions.add(getString(pinnedMessageIds.contains(message.getId())
                ? R.string.unpin_message : R.string.pin_message));
        actionIds.add(4);
        actions.add(getString(R.string.delete_message));
        actionIds.add(5);
        new AlertDialog.Builder(this, R.style.ThemeOverlay_OfflineConnect_Dialog)
                .setTitle(R.string.message_actions)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    int action = actionIds.get(which);
                    if (action == 1) copyMessage(message);
                    else if (action == 2) editMessage(message);
                    else if (action == 3) toggleStarred(message);
                    else if (action == 4) togglePinned(message);
                    else deleteMessage(message);
                }).show();
    }

    private void toggleStarred(Message message) {
        boolean starred = !starredMessageIds.contains(message.getId());
        if (starred) starredMessageIds.add(message.getId());
        else starredMessageIds.remove(message.getId());
        databaseExecutor.execute(() -> AppDatabase.getInstance(this).messageDao()
                .setStarred(message.getId(), starred));
        showMessage(message);
        rebuildSearchResults(searchInput.getText().toString(), false);
    }

    private void togglePinned(Message message) {
        boolean pinned = !pinnedMessageIds.contains(message.getId());
        if (pinned) pinnedMessageIds.add(message.getId());
        else pinnedMessageIds.remove(message.getId());
        databaseExecutor.execute(() -> AppDatabase.getInstance(this).messageDao()
                .setPinned(message.getId(), pinned));
        showMessage(message);
        rebuildSearchResults(searchInput.getText().toString(), false);
    }

    private void copyMessage(Message message) {
        ClipboardManager clipboard = ContextCompat.getSystemService(this, ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name),
                    message.getBody()));
            Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void editMessage(Message message) {
        EditText input = new EditText(this);
        input.setText(message.getBody());
        input.setSelection(input.length());
        new AlertDialog.Builder(this, R.style.ThemeOverlay_OfflineConnect_Dialog)
                .setTitle(R.string.edit_message).setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String body = input.getText().toString().trim();
                    if (body.isEmpty()) return;
                    Message edited = new Message(message.getId(), message.getPeerId(), body,
                            message.getTimestamp(), message.isOutgoing(), Message.Status.PENDING,
                            true);
                    showMessage(edited);
                    persistMessage(edited);
                    if (connectionManager.getState() == ConnectionManager.State.CONNECTED) {
                        connectionManager.send(edited);
                    }
                }).show();
    }

    private void deleteMessage(Message message) {
        new AlertDialog.Builder(this, R.style.ThemeOverlay_OfflineConnect_Dialog)
                .setTitle(R.string.delete_message)
                .setMessage(R.string.delete_message_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> performDelete(message))
                .show();
    }

    /** Removes only the selected local message after explicit user confirmation. */
    private void performDelete(Message message) {
        applyDeletion(message.getId(), true);
    }

    /** Stores a durable deletion and optionally forwards it to the connected peer. */
    private void applyDeletion(String messageId, boolean notifyPeer) {
        Message removedMessage = messages.get(messageId);
        if (messageId.equals(selectedMessageId)) selectedMessageId = null;
        View view = messageViews.remove(messageId);
        messages.remove(messageId);
        if (view != null) messageContainer.removeView(view);
        emptyState.setVisibility(messageViews.isEmpty() ? View.VISIBLE : View.GONE);
        if (searchInput != null && searchInput.length() > 0) {
            rebuildSearchResults(searchInput.getText().toString(), false);
        }
        databaseExecutor.execute(() -> {
            MessageDao dao = AppDatabase.getInstance(this).messageDao();
            // Keep a tombstone even if deletion arrives before the corresponding message.
            dao.insertDeletionIfMissing(messageId, device.getId());
            dao.markDeleted(messageId);
            if (removedMessage != null && (removedMessage.getBody().startsWith(IMAGE_PREFIX)
                    || removedMessage.getBody().startsWith(VIDEO_PREFIX))) {
                MediaRepository.delete(this, messageId);
            }
        });
        if (notifyPeer) connectionManager.sendDeletion(messageId);
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
        if (message.isEdited()) {
            TextView edited = new TextView(this);
            edited.setText(R.string.edited_indicator);
            edited.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            edited.setTextSize(12f);
            LinearLayout.LayoutParams editedParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            editedParams.setMarginStart(dp(6));
            footer.addView(edited, editedParams);
        }

        if (message.getStatus() == Message.Status.FAILED && message.isOutgoing()) {
            Button retry = new Button(this);
            retry.setText(R.string.retry);
            retry.setTextColor(ContextCompat.getColor(this, R.color.primary));
            retry.setTextSize(12f);
            retry.setAllCaps(false);
            retry.setContentDescription(getString(R.string.retry_message));
            retry.setBackgroundColor(Color.TRANSPARENT);
            retry.setMinHeight(dp(48));
            retry.setMinWidth(dp(64));
            retry.setOnClickListener(view -> retryMessage(message));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
            retryParams.setMarginStart(dp(8));
            footer.addView(retry, retryParams);
        }
        bubble.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showAttachmentPicker() {
        new AlertDialog.Builder(this, R.style.ThemeOverlay_OfflineConnect_Dialog)
                .setTitle(R.string.choose_attachment)
                .setItems(new String[]{getString(R.string.choose_image),
                        getString(R.string.choose_video), getString(R.string.choose_contact),
                        getString(R.string.share_location)}, (dialog, which) -> {
                    if (which < 2) mediaPicker.launch(which == 0 ? "image/*" : "video/*");
                    else if (which == 2) contactPicker.launch(new Intent(Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
                    else confirmLocationShare();
                })
                .show();
    }

    private void handleContactResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null
                || result.getData().getData() == null) return;
        Uri uri = result.getData().getData();
        String[] projection = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException();
            String name = cursor.getString(0);
            String phone = cursor.getString(1);
            if (phone == null || phone.trim().isEmpty()) {
                Toast.makeText(this, R.string.contact_phone_missing, Toast.LENGTH_LONG).show();
                return;
            }
            if (name == null || name.trim().isEmpty()) name = getString(R.string.shared_contact);
            queueMessage(CONTACT_PREFIX + encodeMetadata(name) + "|" + encodeMetadata(phone));
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not read selected contact", exception);
            Toast.makeText(this, R.string.contact_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private String encodeMetadata(String value) {
        return android.util.Base64.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
    }

    private String decodeMetadata(String value) {
        return new String(android.util.Base64.decode(value,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private void requestLocationShare() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) shareCurrentLocation();
        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void confirmLocationShare() {
        new AlertDialog.Builder(this, R.style.ThemeOverlay_OfflineConnect_Dialog)
                .setTitle(R.string.location_share_title)
                .setMessage(R.string.location_share_privacy)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.share, (dialog, which) -> requestLocationShare())
                .show();
    }

    @SuppressLint("MissingPermission")
    private void shareCurrentLocation() {
        if (locating) return;
        LocationManager manager = ContextCompat.getSystemService(this, LocationManager.class);
        if (manager == null) {
            showLocationUnavailable();
            return;
        }
        String provider = manager.getBestProvider(new Criteria(), true);
        if (provider == null) {
            showLocationUnavailable();
            return;
        }
        locating = true;
        connectionStatus.setText(R.string.locating);
        locationHandler.removeCallbacksAndMessages(null);
        locationHandler.postDelayed(() -> {
            if (!locating) return;
            locating = false;
            restoreConnectionLabel();
            showLocationUnavailable();
        }, LOCATION_TIMEOUT_MS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, getMainExecutor(), this::handleLocation);
        } else {
            manager.requestSingleUpdate(provider, new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    handleLocation(location);
                }
            }, android.os.Looper.getMainLooper());
        }
    }

    private void handleLocation(Location location) {
        if (!locating) return;
        locating = false;
        locationHandler.removeCallbacksAndMessages(null);
        restoreConnectionLabel();
        if (location == null) {
            showLocationUnavailable();
            return;
        }
        queueMessage(LOCATION_PREFIX + String.format(Locale.US, "%.6f,%.6f",
                location.getLatitude(), location.getLongitude()));
    }

    private void showLocationUnavailable() {
        Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show();
    }

    private void restoreConnectionLabel() {
        connectionStatus.setText(connectionManager.getState() == ConnectionManager.State.CONNECTED
                ? R.string.connected : connectionManager.getState() == ConnectionManager.State.CONNECTING
                ? R.string.connecting : R.string.disconnected);
    }

    private void preparePickedMedia(Uri uri) {
        databaseExecutor.execute(() -> {
            String mime = getContentResolver().getType(uri);
            boolean image = mime != null && mime.startsWith("image/");
            File destination = new File(getFilesDir(), (image ? "image_" : "video_")
                    + System.currentTimeMillis() + (image ? ".jpg" : ".mp4"));
            try {
                if (image) compressImage(uri, destination);
                else copyUri(uri, destination);
                if (!destination.isFile() || destination.length() == 0
                        || destination.length() > MAX_MEDIA_BYTES) {
                    if (!destination.delete()) Log.w(TAG, "Could not remove oversized media");
                    runOnUiThread(() -> Toast.makeText(this, image
                            ? R.string.media_prepare_failed : R.string.video_too_large,
                            Toast.LENGTH_LONG).show());
                    return;
                }
                Message message = new Message(null, device.getId(), "", System.currentTimeMillis(),
                        true, Message.Status.PENDING);
                String thumbnail = MediaRepository.createThumbnail(destination.getAbsolutePath(),
                        !image);
                String body = (image ? IMAGE_PREFIX : VIDEO_PREFIX) + message.getId() + "|"
                        + thumbnail;
                Message mediaMessage = new Message(message.getId(), device.getId(), body,
                        message.getTimestamp(), true, Message.Status.PENDING);
                MediaRepository.store(this, new MediaEntity(message.getId(),
                        destination.getAbsolutePath(), image ? "image/jpeg" : "video/mp4",
                        destination.length(), 0L));
                runOnUiThread(() -> queuePreparedMessage(mediaMessage));
            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "Could not prepare attachment", exception);
                if (destination.exists() && !destination.delete()) {
                    Log.w(TAG, "Could not remove failed attachment");
                }
                runOnUiThread(() -> Toast.makeText(this, R.string.media_prepare_failed,
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void compressImage(Uri uri, File destination) throws IOException {
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input);
        }
        if (bitmap == null) throw new IOException("Unsupported image");
        int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        Bitmap output = bitmap;
        if (maxSide > 1920) {
            float scale = 1920f / maxSide;
            output = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale), true);
        }
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, 82, stream)) {
                throw new IOException("Image compression failed");
            }
        } finally {
            if (output != bitmap) output.recycle();
            bitmap.recycle();
        }
    }

    private void copyUri(Uri uri, File destination) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Unable to open media");
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_MEDIA_BYTES) throw new IOException("Media exceeds limit");
                output.write(buffer, 0, count);
            }
        }
    }

    private String mediaPath(String body) {
        return mediaId(body);
    }

    private String mediaId(String body) {
        String metadata = body.substring(8);
        int separator = metadata.indexOf('|');
        return separator < 0 ? metadata : metadata.substring(0, separator);
    }

    private String mediaThumbnail(String body) {
        int separator = body.indexOf('|', 8);
        return separator < 0 ? "" : body.substring(separator + 1);
    }

    private boolean isMediaMessage(String body) {
        return body.startsWith(VOICE_PREFIX) || body.startsWith(IMAGE_PREFIX)
                || body.startsWith(VIDEO_PREFIX);
    }

    private boolean isStructuredMessage(String body) {
        return body.startsWith(CONTACT_PREFIX) || body.startsWith(LOCATION_PREFIX);
    }

    private String searchableBody(String body) {
        if (body.startsWith(CONTACT_PREFIX)) {
            String[] parts = body.substring(CONTACT_PREFIX.length()).split("\\|", 2);
            try {
                return parts.length == 2 ? decodeMetadata(parts[0]) + " "
                        + decodeMetadata(parts[1]) : getString(R.string.shared_contact);
            } catch (IllegalArgumentException ignored) {
                return getString(R.string.shared_contact);
            }
        }
        if (body.startsWith(LOCATION_PREFIX)) return getString(R.string.shared_location);
        return body;
    }

    private void addContactContent(LinearLayout bubble, String body) {
        String[] parts = body.substring(CONTACT_PREFIX.length()).split("\\|", 2);
        if (parts.length != 2) return;
        try {
            String name = decodeMetadata(parts[0]);
            String phone = decodeMetadata(parts[1]);
            LinearLayout row = createStructuredRow(R.drawable.ic_contact);
            LinearLayout text = createStructuredText(row);
            TextView title = structuredTitle(name);
            TextView subtitle = structuredSubtitle(phone);
            text.addView(title);
            text.addView(subtitle);
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            Button add = structuredAction(getString(R.string.add_contact));
            Button call = structuredAction(getString(R.string.call_contact, name));
            add.setOnClickListener(view -> addToContacts(name, phone));
            call.setOnClickListener(view -> openDialer(phone));
            actions.addView(add);
            LinearLayout.LayoutParams callParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
            callParams.setMarginStart(dp(8));
            actions.addView(call, callParams);
            text.addView(actions);
            row.setContentDescription(getString(R.string.shared_contact) + ", " + name
                    + ", " + phone);
            bubble.addView(row);
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Invalid contact message", exception);
        }
    }

    private void addLocationContent(LinearLayout bubble, String body) {
        String coordinates = body.substring(LOCATION_PREFIX.length());
        String[] parts = coordinates.split(",", 2);
        if (parts.length != 2) return;
        LinearLayout row = createStructuredRow(R.drawable.ic_location);
        LinearLayout text = createStructuredText(row);
        text.addView(structuredTitle(getString(R.string.shared_location)));
        try {
            text.addView(structuredSubtitle(getString(R.string.location_coordinates,
                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1]))));
        } catch (NumberFormatException exception) {
            text.addView(structuredSubtitle(coordinates));
        }
        Button maps = structuredAction(getString(R.string.open_location));
        maps.setOnClickListener(view -> openMap(coordinates));
        LinearLayout.LayoutParams mapsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        mapsParams.gravity = Gravity.END;
        text.addView(maps, mapsParams);
        bubble.addView(row);
    }

    private LinearLayout createStructuredRow(int iconResource) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_shared_card);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private LinearLayout createStructuredText(LinearLayout row) {
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(dp(8));
        row.addView(text, params);
        return text;
    }

    private TextView structuredTitle(String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        return title;
    }

    private TextView structuredSubtitle(String value) {
        TextView subtitle = new TextView(this);
        subtitle.setText(value);
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.primary));
        subtitle.setTextSize(13f);
        return subtitle;
    }

    private Button structuredAction(String label) {
        Button action = new Button(this);
        action.setText(label);
        action.setTextColor(ContextCompat.getColor(this, R.color.primary));
        action.setTextSize(13f);
        action.setAllCaps(false);
        action.setMinHeight(dp(48));
        action.setMinimumWidth(dp(72));
        action.setPadding(dp(12), 0, dp(12), 0);
        action.setBackgroundResource(R.drawable.bg_shared_action);
        return action;
    }

    private void openDialer(String phone) {
        Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)));
        if (dial.resolveActivity(getPackageManager()) != null) startActivity(dial);
    }

    private void addToContacts(String name, String phone) {
        Intent insert = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
        insert.putExtra(ContactsContract.Intents.Insert.NAME, name);
        insert.putExtra(ContactsContract.Intents.Insert.PHONE, phone);
        if (insert.resolveActivity(getPackageManager()) != null) startActivity(insert);
    }

    private void openMap(String coordinates) {
        Uri geo = Uri.parse("geo:" + coordinates + "?q=" + coordinates);
        Intent map = new Intent(Intent.ACTION_VIEW, geo);
        if (map.resolveActivity(getPackageManager()) != null) startActivity(map);
    }

    private void addImageContent(LinearLayout bubble, String messageId, boolean outgoing) {
        FrameLayout mediaFrame = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        setThumbnail(image, messages.values().stream().filter(item ->
                item.getId().equals(messageId)).findFirst().map(Message::getBody).orElse(""));
        image.setOnClickListener(view -> openStoredMedia(messageId, false, !outgoing));
        mediaFrame.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        bubble.addView(mediaFrame, new LinearLayout.LayoutParams(messageContentWidth(), dp(220)));
    }

    private void addVideoContent(LinearLayout bubble, String messageId, boolean outgoing) {
        FrameLayout mediaFrame = new FrameLayout(this);
        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        setThumbnail(thumbnail, messages.values().stream().filter(item ->
                item.getId().equals(messageId)).findFirst().map(Message::getBody).orElse(""));
        thumbnail.setContentDescription(getString(R.string.play_video));
        mediaFrame.addView(thumbnail, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ImageView play = new ImageView(this);
        play.setImageResource(R.drawable.ic_play);
        play.setBackgroundResource(R.drawable.bg_media_action);
        play.setPadding(dp(13), dp(13), dp(13), dp(13));
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.CENTER);
        mediaFrame.addView(play, playParams);
        View.OnClickListener open = view -> openStoredMedia(messageId, true, !outgoing);
        thumbnail.setOnClickListener(open);
        play.setOnClickListener(open);
        bubble.addView(mediaFrame, new LinearLayout.LayoutParams(messageContentWidth(), dp(220)));
    }

    private void setThumbnail(ImageView view, String body) {
        try {
            byte[] bytes = android.util.Base64.decode(mediaThumbnail(body),
                    android.util.Base64.NO_WRAP);
            Bitmap thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (thumbnail != null) view.setImageBitmap(thumbnail);
        } catch (IllegalArgumentException ignored) {
            view.setImageResource(android.R.drawable.ic_menu_gallery);
        }
    }

    private void openStoredMedia(String messageId, boolean video, boolean canSave) {
        databaseExecutor.execute(() -> {
            MediaEntity media = MediaRepository.find(this, messageId);
            if (media == null || !new File(media.filePath).isFile()) {
                runOnUiThread(() -> Toast.makeText(this, R.string.voice_file_missing,
                        Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> openMediaViewer(media.filePath, video, canSave));
        });
    }

    private void openMediaViewer(String path, boolean video, boolean canSave) {
        Intent intent = new Intent(this, MediaViewerActivity.class);
        intent.putExtra(MediaViewerActivity.EXTRA_PATH, path);
        intent.putExtra(MediaViewerActivity.EXTRA_VIDEO, video);
        intent.putExtra(MediaViewerActivity.EXTRA_CAN_SAVE, canSave);
        startActivity(intent);
    }

    private void saveMediaToDevice(String path, String mime, String extension) {
        databaseExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        "OfflineConnect_" + System.currentTimeMillis() + extension);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            mime.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                                    + "/OfflineConnect" : Environment.DIRECTORY_MOVIES
                                    + "/OfflineConnect");
                }
                Uri target = getContentResolver().insert(mime.startsWith("image/")
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IOException("MediaStore insert failed");
                try (FileInputStream input = new FileInputStream(path);
                     java.io.OutputStream output = getContentResolver().openOutputStream(target)) {
                    if (output == null) throw new IOException("MediaStore output failed");
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

    private void addVoiceContent(LinearLayout bubble, String metadata) {
        int separator = metadata.lastIndexOf('|');
        String path = separator < 0 ? metadata : metadata.substring(0, separator);
        long duration = 0L;
        if (separator >= 0) {
            try { duration = Long.parseLong(metadata.substring(separator + 1)); }
            catch (NumberFormatException ignored) { }
        }

        final long totalDuration = duration;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_voice_player);
        row.setMinimumWidth(Math.min(messageContentWidth(), dp(300)));
        ImageButton play = new ImageButton(this);
        play.setImageResource(R.drawable.ic_play);
        play.setBackgroundColor(Color.TRANSPARENT);
        play.setContentDescription(getString(R.string.play_voice));
        SeekBar progress = new SeekBar(this);
        progress.setMax((int) Math.max(1L, duration));
        progress.setContentDescription(getString(R.string.play_voice));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean user) {
                if (user && player != null && seekBar == activeSeekBar) player.seekTo(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        TextView label = new TextView(this);
        label.setText(getString(R.string.voice_time_format, formatDuration(0L),
                formatDuration(totalDuration)));
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        label.setTextSize(12f);

        Button speed = new Button(this);
        speed.setText("1×");
        speed.setTextSize(12f);
        speed.setAllCaps(false);
        speed.setMinWidth(dp(48));
        speed.setMinHeight(dp(48));
        speed.setBackgroundColor(Color.TRANSPARENT);
        speed.setContentDescription(getString(R.string.playback_speed, "1×"));
        speed.setOnClickListener(view -> cyclePlaybackSpeed(speed, play));

        play.setOnClickListener(view -> toggleVoice(path, play, progress, label));
        row.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        center.addView(label);
        row.addView(center, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(speed, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bubble.addView(row);
    }

    private void toggleVoice(String path, ImageButton button, SeekBar seekBar, TextView label) {
        if (!new File(path).isFile()) {
            Toast.makeText(this, R.string.voice_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (player != null && button == activePlayButton) {
            if (player.isPlaying()) {
                player.pause();
                button.setImageResource(R.drawable.ic_play);
                button.setContentDescription(getString(R.string.play_voice));
            } else {
                player.start();
                button.setImageResource(R.drawable.ic_pause);
                button.setContentDescription(getString(R.string.pause_voice));
                schedulePlaybackProgress();
            }
            return;
        }
        releasePlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(path);
            player.setOnCompletionListener(ignored -> releasePlayer());
            player.prepare();
            activePlayButton = button;
            activeSeekBar = seekBar;
            activeDurationLabel = label;
            activeTotalDuration = player.getDuration();
            playbackSpeed = 1f;
            seekBar.setMax(player.getDuration());
            player.start();
            button.setImageResource(R.drawable.ic_pause);
            button.setContentDescription(getString(R.string.pause_voice));
            schedulePlaybackProgress();
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Voice message playback failed", exception);
            releasePlayer();
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void cyclePlaybackSpeed(Button speedButton, ImageButton owner) {
        if (player == null || owner != activePlayButton) return;
        playbackSpeed = playbackSpeed == 1f ? 1.5f : playbackSpeed == 1.5f ? 2f : 1f;
        player.setPlaybackParams(player.getPlaybackParams().setSpeed(playbackSpeed));
        String label = playbackSpeed == 1f ? "1×" : playbackSpeed == 1.5f ? "1.5×" : "2×";
        speedButton.setText(label);
        speedButton.setContentDescription(getString(R.string.playback_speed, label));
    }

    private String formatDuration(long milliseconds) {
        long seconds = Math.max(0L, milliseconds) / 1_000L;
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L);
    }

    /** Leaves comfortable screen-edge spacing while allowing richer text and voice bubbles. */
    private int messageContentWidth() {
        return (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
    }

    private void schedulePlaybackProgress() {
        playbackHandler.removeCallbacksAndMessages(null);
        playbackHandler.post(new Runnable() {
            @Override public void run() {
                if (player == null || activeSeekBar == null) return;
                activeSeekBar.setProgress(player.getCurrentPosition());
                if (activeDurationLabel != null) {
                    activeDurationLabel.setText(getString(R.string.voice_time_format,
                            formatDuration(player.getCurrentPosition()),
                            formatDuration(activeTotalDuration)));
                }
                if (player.isPlaying()) playbackHandler.postDelayed(this, 250L);
            }
        });
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
        playbackHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (activePlayButton != null) {
            activePlayButton.setImageResource(R.drawable.ic_play);
            activePlayButton.setContentDescription(getString(R.string.play_voice));
        }
        if (activeSeekBar != null) activeSeekBar.setProgress(0);
        if (activeDurationLabel != null) activeDurationLabel.setText(
                getString(R.string.voice_time_format, formatDuration(0L),
                        formatDuration(activeTotalDuration)));
        activePlayButton = null;
        activeSeekBar = null;
        activeDurationLabel = null;
        activeTotalDuration = 0L;
        playbackSpeed = 1f;
    }

    @SuppressWarnings("deprecation")
    private Device readDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getIntent().getSerializableExtra(EXTRA_DEVICE, Device.class);
        }
        return (Device) getIntent().getSerializableExtra(EXTRA_DEVICE);
    }

    /** Clears contextual selection when the next touch begins outside the selected bubble. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && selectedMessageId != null) {
            View selected = messageViews.get(selectedMessageId);
            if (selected == null) {
                selectedMessageId = null;
            } else {
                Rect bounds = new Rect();
                selected.getGlobalVisibleRect(bounds);
                if (!bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()))) {
                    clearMessageSelection();
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onDestroy() {
        if (recording) finishRecording(true);
        locating = false;
        locationHandler.removeCallbacksAndMessages(null);
        recordingHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        databaseExecutor.shutdown();
        if (connectionManager != null) connectionManager.close();
        super.onDestroy();
    }
}
