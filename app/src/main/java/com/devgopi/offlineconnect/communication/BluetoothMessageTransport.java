package com.devgopi.offlineconnect.communication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.devgopi.offlineconnect.model.Message;
import com.devgopi.offlineconnect.R;
import com.devgopi.offlineconnect.database.MediaEntity;
import com.devgopi.offlineconnect.database.MediaRepository;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secure RFCOMM transport with bounded binary frames and delivery/read acknowledgements. */
public final class BluetoothMessageTransport implements AutoCloseable {
    private static final String SERVICE_NAME = "Offline Connect";
    private static final UUID SERVICE_UUID = UUID.fromString("e1f7ad8d-8f32-4f73-9008-f28c51d3b741");
    private static final int MAGIC = 0x4F434D31; // OCM1
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final byte TEXT = 1;
    private static final byte VOICE = 2;
    private static final byte DELIVERED = 3;
    private static final byte READ = 4;
    private static final byte DELETED = 5;
    private static final byte IMAGE = 6;
    private static final byte VIDEO = 7;

    public interface Listener {
        void onConnecting();
        void onConnected(String peerAddress);
        void onDisconnected();
        void onMessageReceived(Message message);
        void onMessageDeleted(String messageId);
        void onReceipt(String messageId, Message.Status status);
        void onSendFailed(String messageId, String reason);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean listening = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final Object writeLock = new Object();
    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket socket;
    private volatile DataOutputStream output;

    public BluetoothMessageTransport(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        android.bluetooth.BluetoothManager manager = ContextCompat.getSystemService(
                this.context, android.bluetooth.BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    @SuppressLint("MissingPermission")
    public void startListening() {
        if (!ready() || isConnected() || !listening.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            BluetoothServerSocket listeningSocket = null;
            try {
                listeningSocket = adapter.listenUsingRfcommWithServiceRecord(
                        SERVICE_NAME, SERVICE_UUID);
                serverSocket = listeningSocket;
                while (!closed.get() && socket == null) {
                    BluetoothSocket accepted = listeningSocket.accept();
                    if (accepted != null) attach(accepted);
                }
            } catch (IOException | SecurityException exception) {
                // attach() deliberately closes accept() after a session connects.
                // That produces an IOException and is not a user-visible failure.
                if (!closed.get() && !isConnected()) {
                    postError(context.getString(R.string.bluetooth_listening_failed));
                }
            } finally {
                if (serverSocket == listeningSocket) serverSocket = null;
                closeQuietly(listeningSocket);
                listening.set(false);
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void connect(String address) {
        if (!ready()) return;
        startListening();
        if (isConnected() || !connecting.compareAndSet(false, true)) return;
        post(listener::onConnecting);
        ioExecutor.execute(() -> {
            BluetoothSocket candidate = null;
            try {
                adapter.cancelDiscovery();
                BluetoothDevice peer = adapter.getRemoteDevice(address);
                candidate = peer.createRfcommSocketToServiceRecord(SERVICE_UUID);
                candidate.connect();
                attach(candidate);
            } catch (IOException | IllegalArgumentException | SecurityException exception) {
                closeQuietly(candidate);
                // Both phones may press Connect together. If the incoming socket won that race,
                // the failed outgoing socket is expected and must not undo the live connection.
                if (!closed.get() && !isConnected()) {
                    post(() -> {
                        // Recheck on the main thread because attach() may complete after this
                        // worker catches its exception but before UI callbacks are delivered.
                        if (!closed.get() && !isConnected()) {
                            listener.onError(context.getString(
                                    R.string.bluetooth_connection_failed));
                            listener.onDisconnected();
                        }
                    });
                }
            } finally {
                connecting.set(false);
            }
        });
    }

    public boolean isConnected() {
        BluetoothSocket current = socket;
        return current != null && current.isConnected() && output != null;
    }

    public void send(Message message) {
        ioExecutor.execute(() -> {
            try {
                byte type;
                byte[] payload;
                long duration = 0L;
                if (message.getBody().startsWith("voice://")
                        || message.getBody().startsWith("image://")
                        || message.getBody().startsWith("video://")) {
                    boolean voiceMessage = message.getBody().startsWith("voice://");
                    type = voiceMessage ? VOICE : message.getBody().startsWith("image://")
                            ? IMAGE : VIDEO;
                    File file;
                    if (voiceMessage) {
                        VoiceMetadata voice = VoiceMetadata.parse(message.getBody().substring(8));
                        file = new File(voice.path);
                        duration = voice.duration;
                    } else {
                        String mediaId = mediaId(message.getBody());
                        MediaEntity media = MediaRepository.find(context, mediaId);
                        if (media == null) throw new IOException("Media file is unavailable");
                        file = new File(media.filePath);
                        duration = media.durationMs;
                    }
                    if (!file.isFile() || file.length() > MAX_PAYLOAD_BYTES) {
                        throw new IOException(context.getString(R.string.voice_unavailable_or_large));
                    }
                    payload = java.nio.file.Files.readAllBytes(file.toPath());
                } else {
                    type = TEXT;
                    payload = message.getBody().getBytes(StandardCharsets.UTF_8);
                }
                writeFrame(type, message.getId(), message.getTimestamp(), duration,
                        message.isEdited(), payload);
                post(() -> listener.onReceipt(message.getId(), Message.Status.SENT));
            } catch (IOException | RuntimeException exception) {
                post(() -> listener.onSendFailed(message.getId(), exception.getMessage()));
            }
        });
    }

    private void sendReceipt(byte type, String messageId) {
        ioExecutor.execute(() -> {
            try { writeFrame(type, messageId, 0L, 0L, false, new byte[0]); }
            catch (IOException ignored) { }
        });
    }

    public void sendDeletion(String messageId) {
        ioExecutor.execute(() -> {
            try { writeFrame(DELETED, messageId, 0L, 0L, false, new byte[0]); }
            catch (IOException ignored) { }
        });
    }

    @SuppressLint("MissingPermission")
    private synchronized void attach(BluetoothSocket connected) throws IOException {
        if (closed.get()) { closeQuietly(connected); return; }
        if (socket != null && socket.isConnected()) { closeQuietly(connected); return; }
        socket = connected;
        output = new DataOutputStream(new BufferedOutputStream(connected.getOutputStream()));
        closeQuietly(serverSocket);
        serverSocket = null;
        listening.set(false);
        post(() -> listener.onConnected(connected.getRemoteDevice().getAddress()));
        ioExecutor.execute(() -> readLoop(connected));
    }

    private void readLoop(BluetoothSocket connected) {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(connected.getInputStream()))) {
            while (!closed.get() && connected == socket) readFrame(input);
        } catch (EOFException ignored) {
            // Normal remote disconnect.
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) postError(context.getString(R.string.bluetooth_connection_lost));
        } finally {
            if (connected == socket) {
                closeQuietly(connected);
                socket = null;
                output = null;
                post(listener::onDisconnected);
                if (!closed.get()) startListening();
            }
        }
    }

    private void readFrame(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid Bluetooth frame");
        byte type = input.readByte();
        String id = input.readUTF();
        long timestamp = input.readLong();
        long duration = input.readLong();
        boolean edited = input.readBoolean();
        int length = input.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) throw new IOException("Invalid payload size");
        byte[] payload = new byte[length];
        input.readFully(payload);

        if (type == DELIVERED || type == READ) {
            Message.Status status = type == READ ? Message.Status.READ : Message.Status.DELIVERED;
            post(() -> listener.onReceipt(id, status));
            return;
        }
        if (type == DELETED) {
            post(() -> listener.onMessageDeleted(id));
            return;
        }
        String body;
        if (type == TEXT) {
            body = new String(payload, StandardCharsets.UTF_8);
        } else if (type == VOICE || type == IMAGE || type == VIDEO) {
            String extension = type == VOICE ? ".m4a" : type == IMAGE ? ".jpg" : ".mp4";
            String prefix = type == VOICE ? "voice://" : type == IMAGE ? "image://" : "video://";
            File media = new File(context.getFilesDir(), "received_" + id + extension);
            try (FileOutputStream stream = new FileOutputStream(media)) { stream.write(payload); }
            if (type == VOICE) body = prefix + media.getAbsolutePath() + "|" + duration;
            else {
                MediaRepository.store(context, new MediaEntity(id, media.getAbsolutePath(),
                        type == IMAGE ? "image/jpeg" : "video/mp4", media.length(), duration));
                body = prefix + id + "|" + MediaRepository.createThumbnail(
                        media.getAbsolutePath(), type == VIDEO);
            }
        } else {
            throw new IOException("Unknown Bluetooth frame type");
        }
        Message message = new Message(id, socket.getRemoteDevice().getAddress(), body,
                timestamp, false, Message.Status.RECEIVED, edited);
        post(() -> listener.onMessageReceived(message));
        sendReceipt(DELIVERED, id);
        sendReceipt(READ, id); // Chat is visible while this activity-owned session is active.
    }

    private void writeFrame(byte type, String id, long timestamp, long duration, boolean edited,
                            byte[] payload)
            throws IOException {
        synchronized (writeLock) {
            DataOutputStream stream = output;
            if (!isConnected() || stream == null) throw new IOException(context.getString(R.string.not_connected));
            stream.writeInt(MAGIC);
            stream.writeByte(type);
            stream.writeUTF(id);
            stream.writeLong(timestamp);
            stream.writeLong(duration);
            stream.writeBoolean(edited);
            stream.writeInt(payload.length);
            stream.write(payload);
            stream.flush();
        }
    }

    private boolean ready() {
        if (adapter == null) { postError(context.getString(R.string.bluetooth_not_supported)); return false; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            postError(context.getString(R.string.bluetooth_connect_permission_required));
            return false;
        }
        return true;
    }

    private void post(Runnable action) { mainHandler.post(action); }
    private void postError(String message) { post(() -> listener.onError(message)); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(serverSocket);
        closeQuietly(socket);
        serverSocket = null;
        socket = null;
        output = null;
        listening.set(false);
        connecting.set(false);
        ioExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    private static final class VoiceMetadata {
        final String path;
        final long duration;
        VoiceMetadata(String path, long duration) { this.path = path; this.duration = duration; }
        static VoiceMetadata parse(String value) {
            int separator = value.lastIndexOf('|');
            if (separator < 0) return new VoiceMetadata(value, 0L);
            try {
                return new VoiceMetadata(value.substring(0, separator),
                        Long.parseLong(value.substring(separator + 1)));
            } catch (NumberFormatException ignored) {
                return new VoiceMetadata(value.substring(0, separator), 0L);
            }
        }
    }

    private static String mediaId(String body) {
        int separator = body.indexOf('|', 8);
        return separator < 0 ? body.substring(8) : body.substring(8, separator);
    }
}
