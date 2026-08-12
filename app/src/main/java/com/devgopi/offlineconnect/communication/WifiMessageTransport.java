package com.devgopi.offlineconnect.communication;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** TCP messaging session carried over the private network created by Wi-Fi Direct. */
public final class WifiMessageTransport implements AutoCloseable {
    private static final int PORT = 28991;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int CONNECT_RETRY_COUNT = 6;
    private static final long CONNECT_RETRY_DELAY_MS = 500L;
    private static final int MAGIC = 0x4F434D31;
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

    public interface Listener {
        void onConnecting();
        void onConnected();
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean serverStarting = new AtomicBoolean();
    private final AtomicBoolean clientConnecting = new AtomicBoolean();
    private final Object writeLock = new Object();
    private final AtomicInteger priorityWriters = new AtomicInteger();
    private final Map<String, IncomingMedia> incomingMedia = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;
    private volatile Socket socket;
    private volatile DataOutputStream output;

    public WifiMessageTransport(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void startServer() {
        if (isConnected() || !serverStarting.compareAndSet(false, true)) return;
        post(listener::onConnecting);
        executor.execute(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                serverSocket = server;
                attach(server.accept());
            } catch (IOException exception) {
                if (!closed.get()) postError(context.getString(R.string.wifi_message_server_failed));
            } finally {
                serverStarting.set(false);
            }
        });
    }

    public void connect(InetAddress groupOwnerAddress) {
        if (isConnected() || !clientConnecting.compareAndSet(false, true)) return;
        post(listener::onConnecting);
        executor.execute(() -> {
            try {
                connectWithRetry(groupOwnerAddress);
            } catch (IOException exception) {
                if (!closed.get() && !isConnected()) {
                    postError(context.getString(R.string.wifi_message_channel_failed));
                    post(listener::onDisconnected);
                }
            } finally {
                clientConnecting.set(false);
            }
        });
    }

    /** Allows the group-owner phone time to bind its TCP server after P2P negotiation. */
    private void connectWithRetry(InetAddress groupOwnerAddress) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < CONNECT_RETRY_COUNT && !closed.get(); attempt++) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(groupOwnerAddress, PORT),
                        CONNECT_TIMEOUT_MS);
                attach(candidate);
                return;
            } catch (IOException exception) {
                lastFailure = exception;
                closeQuietly(candidate);
                if (attempt + 1 < CONNECT_RETRY_COUNT) {
                    try {
                        Thread.sleep(CONNECT_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Wi-Fi connection interrupted", interrupted);
                    }
                }
            }
        }
        throw lastFailure == null ? new IOException("Wi-Fi connection closed") : lastFailure;
    }

    public boolean isConnected() {
        Socket current = socket;
        return current != null && current.isConnected() && !current.isClosed() && output != null;
    }

    public void send(Message message) {
        executor.execute(() -> {
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
                        MediaEntity media = MediaRepository.find(context, mediaId(message.getBody()));
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

    public void sendDeletion(String messageId) {
        executor.execute(() -> {
            try { writePriorityFrame(DELETED, messageId, 0L, 0L, false, new byte[0]); }
            catch (IOException ignored) { }
        });
    }

    public void sendTyping(boolean typing) {
        if (!isConnected()) return;
        executor.execute(() -> {
            try {
                writePriorityFrame(typing ? TYPING_STARTED : TYPING_STOPPED, "", 0L, 0L,
                        false, new byte[0]);
            } catch (IOException ignored) { }
        });
    }


    private synchronized void attach(Socket connected) throws IOException {
        if (closed.get()) { closeQuietly(connected); return; }
        if (isConnected()) { closeQuietly(connected); return; }
        socket = connected;
        output = new DataOutputStream(new BufferedOutputStream(connected.getOutputStream()));
        closeQuietly(serverSocket);
        serverSocket = null;
        post(listener::onConnected);
        executor.execute(() -> readLoop(connected));
    }

    private void readLoop(Socket connected) {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(connected.getInputStream()))) {
            while (!closed.get() && connected == socket) readFrame(input);
        } catch (EOFException ignored) {
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) postError(context.getString(R.string.wifi_connection_lost));
        } finally {
            if (connected == socket) {
                discardIncomingMedia();
                closeQuietly(connected);
                socket = null;
                output = null;
                post(listener::onDisconnected);
            }
        }
    }

    private void readFrame(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid Wi-Fi message frame");
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
                    new File(context.getFilesDir(), "wifi_received_" + id
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
            File media = new File(context.getFilesDir(), "wifi_received_" + id + extension);
            try (FileOutputStream stream = new FileOutputStream(media)) { stream.write(payload); }
            if (type == VOICE) body = prefix + media.getAbsolutePath() + "|" + duration;
            else {
                MediaRepository.store(context, new MediaEntity(id, media.getAbsolutePath(),
                        type == IMAGE ? "image/jpeg" : "video/mp4", media.length(), duration));
                body = prefix + id + "|" + MediaRepository.createThumbnail(
                        media.getAbsolutePath(), type == VIDEO);
            }
        } else {
            throw new IOException("Unknown Wi-Fi message type");
        }
        Message message = new Message(id, "wifi-direct-peer", body, timestamp,
                false, Message.Status.RECEIVED, edited);
        post(() -> listener.onMessageReceived(message));
        sendReceipt(DELIVERED, id);
        sendReceipt(READ, id);
    }

    private void sendReceipt(byte type, String id) {
        executor.execute(() -> {
            try { writePriorityFrame(type, id, 0L, 0L, false, new byte[0]); }
            catch (IOException ignored) { }
        });
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
        Message message = new Message(transfer.id, "wifi-direct-peer", body,
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

    private void post(Runnable action) { mainHandler.post(action); }
    private void postError(String message) { post(() -> listener.onError(message)); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(serverSocket);
        closeQuietly(socket);
        serverSocket = null;
        socket = null;
        output = null;
        serverStarting.set(false);
        clientConnecting.set(false);
        executor.shutdownNow();
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
