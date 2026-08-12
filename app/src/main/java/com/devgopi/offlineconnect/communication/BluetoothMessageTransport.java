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
import android.provider.Settings;
import android.util.Log;

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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Secure RFCOMM transport with bounded binary frames and delivery/read acknowledgements. */
public final class BluetoothMessageTransport implements AutoCloseable {
    private static final String TAG = "BluetoothTransport";
    private static final String SERVICE_NAME = "Offline Connect";
    private static final UUID SERVICE_UUID = UUID.fromString("e1f7ad8d-8f32-4f73-9008-f28c51d3b741");
    private static final int MAGIC = 0x4F434D31; // OCM1
    private static final int HANDSHAKE_MAGIC = 0x4F434831; // OCH1
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final byte TEXT = 1;
    private static final byte VOICE = 2;
    private static final byte DELIVERED = 3;
    private static final byte READ = 4;
    private static final byte DELETED = 5;
    private static final byte IMAGE = 6;
    private static final byte VIDEO = 7;
    private static final byte TYPING_STARTED = 8;
    private static final byte TYPING_STOPPED = 9;
    private static final byte MEDIA_START = 10;
    private static final byte MEDIA_CHUNK = 11;
    private static final byte MEDIA_END = 12;
    private static final int MEDIA_CHUNK_BYTES = 32 * 1024;
    private static final int CONNECT_ATTEMPTS = 4;
    private static final long CONNECT_RETRY_DELAY_MS = 650L;
    private static final long SOCKET_TIMEOUT_MS = 12_000L;

    public interface Listener {
        void onConnecting();
        void onConnected(String peerAddress);
        void onDisconnected();
        void onMessageReceived(Message message);
        void onMessageDeleted(String messageId);
        void onReceipt(String messageId, Message.Status status);
        void onSendProgress(String messageId, int percent);
        void onTypingChanged(boolean typing);
        void onSendFailed(String messageId, String reason);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final String localNodeId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean listening = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final Object writeLock = new Object();
    private final AtomicInteger priorityWriters = new AtomicInteger();
    private final Map<String, IncomingMedia> incomingMedia = new ConcurrentHashMap<>();
    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket socket;
    private volatile DataOutputStream output;

    public BluetoothMessageTransport(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        android.bluetooth.BluetoothManager manager = ContextCompat.getSystemService(
                this.context, android.bluetooth.BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        String androidId = Settings.Secure.getString(this.context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        localNodeId = androidId == null ? UUID.randomUUID().toString() : androidId;
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
                    if (accepted != null) {
                        if (negotiateSocket(accepted, false)) attach(accepted);
                        else closeQuietly(accepted);
                    }
                }
            } catch (IOException | SecurityException exception) {
                // attach() deliberately closes accept() after a session connects.
                // That produces an IOException and is not a user-visible failure.
                if (!closed.get() && !isConnected()) {
                    Log.e(TAG, "RFCOMM listener stopped before a connection was established",
                            exception);
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
            try {
                adapter.cancelDiscovery();
                BluetoothDevice peer = adapter.getRemoteDevice(address);
                IOException lastFailure = null;
                for (int attempt = 0; attempt < CONNECT_ATTEMPTS && !closed.get(); attempt++) {
                    if (isConnected()) return;
                    BluetoothSocket candidate = null;
                    Runnable timeout = null;
                    try {
                        candidate = peer.createRfcommSocketToServiceRecord(SERVICE_UUID);
                        BluetoothSocket attemptSocket = candidate;
                        timeout = () -> {
                            if (!isConnected()) closeQuietly(attemptSocket);
                        };
                        mainHandler.postDelayed(timeout, SOCKET_TIMEOUT_MS);
                        candidate.connect();
                        if (negotiateSocket(candidate, true)) {
                            mainHandler.removeCallbacks(timeout);
                            attach(candidate);
                        } else {
                            mainHandler.removeCallbacks(timeout);
                            closeQuietly(candidate);
                        }
                        return;
                    } catch (IOException exception) {
                        lastFailure = exception;
                        if (timeout != null) mainHandler.removeCallbacks(timeout);
                        Log.w(TAG, "RFCOMM connection attempt " + (attempt + 1)
                                + " failed for " + address, exception);
                        closeQuietly(candidate);
                        if (isConnected()) return;
                        if (attempt + 1 < CONNECT_ATTEMPTS) {
                            try {
                                Thread.sleep(CONNECT_RETRY_DELAY_MS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
                if (lastFailure != null) throw lastFailure;
            } catch (IOException | IllegalArgumentException | SecurityException exception) {
                // Both phones may press Connect together. If the incoming socket won that race,
                // the failed outgoing socket is expected and must not undo the live connection.
                if (!closed.get() && !isConnected()) {
                    Log.e(TAG, "Unable to establish RFCOMM connection with " + address,
                            exception);
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

    /**
     * Both endpoints exchange whether they are actively dialing. If both are, the lower stable
     * node ID keeps its outgoing socket and the higher ID keeps that same socket as incoming.
     */
    private boolean negotiateSocket(BluetoothSocket candidate, boolean outgoing)
            throws IOException {
        DataOutputStream handshakeOutput = new DataOutputStream(candidate.getOutputStream());
        DataInputStream handshakeInput = new DataInputStream(candidate.getInputStream());
        handshakeOutput.writeInt(HANDSHAKE_MAGIC);
        handshakeOutput.writeUTF(localNodeId);
        handshakeOutput.writeBoolean(connecting.get());
        handshakeOutput.flush();
        if (handshakeInput.readInt() != HANDSHAKE_MAGIC) {
            throw new IOException("Unsupported Bluetooth handshake");
        }
        String remoteNodeId = handshakeInput.readUTF();
        boolean remoteDialing = handshakeInput.readBoolean();
        if (!connecting.get() || !remoteDialing || localNodeId.equals(remoteNodeId)) return true;
        boolean localIsLower = localNodeId.compareTo(remoteNodeId) < 0;
        return localIsLower == outgoing;
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
                    sendMedia(message, type, file, duration);
                    post(() -> listener.onReceipt(message.getId(), Message.Status.SENT));
                    return;
                } else {
                    type = TEXT;
                    payload = message.getBody().getBytes(StandardCharsets.UTF_8);
                }
                writePriorityFrame(type, message.getId(), message.getTimestamp(), duration,
                        message.isEdited(), payload);
                post(() -> listener.onReceipt(message.getId(), Message.Status.SENT));
            } catch (IOException | RuntimeException exception) {
                post(() -> listener.onSendFailed(message.getId(), exception.getMessage()));
            }
        });
    }

    private void sendMedia(Message message, byte mediaType, File file, long duration)
            throws IOException {
        byte[] metadata = ByteBuffer.allocate(9).put(mediaType).putLong(file.length()).array();
        writeFrame(MEDIA_START, message.getId(), message.getTimestamp(), duration,
                message.isEdited(), metadata);
        byte[] chunk = new byte[MEDIA_CHUNK_BYTES];
        long sent = 0L;
        int lastPercent = -1;
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(chunk)) >= 0) {
                if (count == 0) continue;
                byte[] data = count == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, count);
                writeFrame(MEDIA_CHUNK, message.getId(), 0L, 0L, false, data);
                sent += count;
                int percent = (int) Math.min(100L, sent * 100L / file.length());
                if (percent == 100 || percent >= lastPercent + 2) {
                    lastPercent = percent;
                    int reported = percent;
                    post(() -> listener.onSendProgress(message.getId(), reported));
                }
                Thread.yield();
            }
        }
        writeFrame(MEDIA_END, message.getId(), 0L, 0L, false, new byte[0]);
    }

    private void sendReceipt(byte type, String messageId) {
        ioExecutor.execute(() -> {
            try { writePriorityFrame(type, messageId, 0L, 0L, false, new byte[0]); }
            catch (IOException ignored) { }
        });
    }

    public void sendDeletion(String messageId) {
        ioExecutor.execute(() -> {
            try { writePriorityFrame(DELETED, messageId, 0L, 0L, false, new byte[0]); }
            catch (IOException ignored) { }
        });
    }

    public void sendTyping(boolean typing) {
        if (!isConnected()) return;
        ioExecutor.execute(() -> {
            try {
                writePriorityFrame(typing ? TYPING_STARTED : TYPING_STOPPED, "", 0L, 0L,
                        false, new byte[0]);
            } catch (IOException ignored) { }
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
                discardIncomingMedia();
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
        if (type == TYPING_STARTED || type == TYPING_STOPPED) {
            post(() -> listener.onTypingChanged(type == TYPING_STARTED));
            return;
        }
        if (type == MEDIA_START) {
            if (payload.length != 9) throw new IOException("Invalid media metadata");
            ByteBuffer metadata = ByteBuffer.wrap(payload);
            byte mediaType = metadata.get();
            long expectedBytes = metadata.getLong();
            if ((mediaType != VOICE && mediaType != IMAGE && mediaType != VIDEO)
                    || expectedBytes <= 0 || expectedBytes > MAX_PAYLOAD_BYTES) {
                throw new IOException("Invalid media size");
            }
            IncomingMedia previous = incomingMedia.remove(id);
            if (previous != null) previous.discard();
            IncomingMedia transfer = new IncomingMedia(id, mediaType, timestamp, duration, edited,
                    expectedBytes,
                    new File(context.getFilesDir(), "received_" + id
                            + (mediaType == VOICE ? ".m4a" : mediaType == IMAGE ? ".jpg" : ".mp4")));
            incomingMedia.put(id, transfer);
            return;
        }
        if (type == MEDIA_CHUNK) {
            IncomingMedia transfer = incomingMedia.get(id);
            if (transfer == null) throw new IOException("Media chunk without start");
            transfer.write(payload);
            return;
        }
        if (type == MEDIA_END) {
            IncomingMedia transfer = incomingMedia.remove(id);
            if (transfer == null) throw new IOException("Media end without start");
            transfer.close();
            if (!transfer.isComplete()) {
                transfer.discard();
                throw new IOException("Incomplete media transfer");
            }
            deliverIncomingMedia(transfer);
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
        while (type == MEDIA_CHUNK && priorityWriters.get() > 0) Thread.yield();
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
            if (type == MEDIA_CHUNK) stream.write(payload);
            else writePayload(stream, id, payload);
            stream.flush();
        }
    }

    private void writePriorityFrame(byte type, String id, long timestamp, long duration,
                                    boolean edited, byte[] payload) throws IOException {
        priorityWriters.incrementAndGet();
        try {
            writeFrame(type, id, timestamp, duration, edited, payload);
        } finally {
            priorityWriters.decrementAndGet();
        }
    }

    private void deliverIncomingMedia(IncomingMedia transfer) {
        String body;
        if (transfer.type == VOICE) {
            body = "voice://" + transfer.file.getAbsolutePath() + "|" + transfer.duration;
        } else {
            MediaRepository.store(context, new MediaEntity(transfer.id,
                    transfer.file.getAbsolutePath(), transfer.type == IMAGE
                    ? "image/jpeg" : "video/mp4", transfer.file.length(), transfer.duration));
            body = (transfer.type == IMAGE ? "image://" : "video://") + transfer.id + "|"
                    + MediaRepository.createThumbnail(transfer.file.getAbsolutePath(),
                    transfer.type == VIDEO);
        }
        Message message = new Message(transfer.id, socket.getRemoteDevice().getAddress(), body,
                transfer.timestamp, false, Message.Status.RECEIVED, transfer.edited);
        post(() -> listener.onMessageReceived(message));
        sendReceipt(DELIVERED, transfer.id);
        sendReceipt(READ, transfer.id);
    }

    private void writePayload(DataOutputStream stream, String messageId, byte[] payload)
            throws IOException {
        if (payload.length == 0) return;
        int written = 0;
        int lastPercent = -1;
        while (written < payload.length) {
            int count = Math.min(32 * 1024, payload.length - written);
            stream.write(payload, written, count);
            written += count;
            int percent = Math.min(100, (int) ((written * 100L) / payload.length));
            if (percent == 100 || percent >= lastPercent + 2) {
                lastPercent = percent;
                int reported = percent;
                post(() -> listener.onSendProgress(messageId, reported));
            }
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
        try {
            if (!adapter.isEnabled()) {
                postError(context.getString(R.string.bluetooth_turned_off));
                return false;
            }
        } catch (SecurityException exception) {
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
        discardIncomingMedia();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private static final class IncomingMedia implements java.io.Closeable {
        final String id; final byte type; final long timestamp; final long duration;
        final boolean edited; final long expectedBytes; final File file;
        final FileOutputStream output; long receivedBytes;
        IncomingMedia(String id, byte type, long timestamp, long duration, boolean edited,
                      long expectedBytes, File file) throws IOException {
            this.id = id; this.type = type; this.timestamp = timestamp;
            this.duration = duration; this.edited = edited; this.expectedBytes = expectedBytes;
            this.file = file;
            output = new FileOutputStream(file);
        }
        void write(byte[] data) throws IOException {
            if (receivedBytes + data.length > expectedBytes) {
                throw new IOException("Media transfer exceeds declared size");
            }
            output.write(data);
            receivedBytes += data.length;
        }
        boolean isComplete() { return receivedBytes == expectedBytes; }
        void discard() {
            close();
            if (file.exists() && !file.delete()) file.deleteOnExit();
        }
        @Override public void close() {
            try { output.close(); } catch (IOException ignored) { }
        }
    }

    private void discardIncomingMedia() {
        for (IncomingMedia transfer : incomingMedia.values()) transfer.discard();
        incomingMedia.clear();
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
