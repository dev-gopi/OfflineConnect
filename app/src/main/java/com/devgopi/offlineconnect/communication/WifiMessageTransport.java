package com.devgopi.offlineconnect.communication;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.devgopi.offlineconnect.model.Message;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** TCP messaging session carried over the private network created by Wi-Fi Direct. */
public final class WifiMessageTransport implements AutoCloseable {
    private static final int PORT = 28991;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAGIC = 0x4F434D31;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final byte TEXT = 1;
    private static final byte VOICE = 2;
    private static final byte DELIVERED = 3;
    private static final byte READ = 4;

    public interface Listener {
        void onConnecting();
        void onConnected();
        void onDisconnected();
        void onMessageReceived(Message message);
        void onReceipt(String messageId, Message.Status status);
        void onSendFailed(String messageId, String reason);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    private volatile ServerSocket serverSocket;
    private volatile Socket socket;
    private volatile DataOutputStream output;

    public WifiMessageTransport(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void startServer() {
        if (serverSocket != null || isConnected()) return;
        post(listener::onConnecting);
        executor.execute(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                serverSocket = server;
                attach(server.accept());
            } catch (IOException exception) {
                if (!closed.get()) postError("Wi-Fi message server failed");
            }
        });
    }

    public void connect(InetAddress groupOwnerAddress) {
        if (isConnected()) return;
        post(listener::onConnecting);
        executor.execute(() -> {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(groupOwnerAddress, PORT),
                        CONNECT_TIMEOUT_MS);
                attach(candidate);
            } catch (IOException exception) {
                closeQuietly(candidate);
                postError("Could not open the Wi-Fi Direct message channel");
                post(listener::onDisconnected);
            }
        });
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
                if (message.getBody().startsWith("voice://")) {
                    type = VOICE;
                    VoiceMetadata voice = VoiceMetadata.parse(message.getBody().substring(8));
                    File file = new File(voice.path);
                    if (!file.isFile() || file.length() > MAX_PAYLOAD_BYTES) {
                        throw new IOException("Voice recording is unavailable or too large");
                    }
                    payload = Files.readAllBytes(file.toPath());
                    duration = voice.duration;
                } else {
                    type = TEXT;
                    payload = message.getBody().getBytes(StandardCharsets.UTF_8);
                }
                writeFrame(type, message.getId(), message.getTimestamp(), duration, payload);
                post(() -> listener.onReceipt(message.getId(), Message.Status.SENT));
            } catch (IOException | RuntimeException exception) {
                post(() -> listener.onSendFailed(message.getId(), exception.getMessage()));
            }
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
            if (!closed.get()) postError("Wi-Fi Direct connection was lost");
        } finally {
            if (connected == socket) {
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
        int length = input.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) throw new IOException("Invalid payload size");
        byte[] payload = new byte[length];
        input.readFully(payload);
        if (type == DELIVERED || type == READ) {
            Message.Status status = type == READ ? Message.Status.READ : Message.Status.DELIVERED;
            post(() -> listener.onReceipt(id, status));
            return;
        }
        String body;
        if (type == TEXT) {
            body = new String(payload, StandardCharsets.UTF_8);
        } else if (type == VOICE) {
            File voice = new File(context.getFilesDir(), "wifi_received_" + id + ".m4a");
            try (FileOutputStream stream = new FileOutputStream(voice)) { stream.write(payload); }
            body = "voice://" + voice.getAbsolutePath() + "|" + duration;
        } else {
            throw new IOException("Unknown Wi-Fi message type");
        }
        Message message = new Message(id, "wifi-direct-peer", body, timestamp,
                false, Message.Status.RECEIVED);
        post(() -> listener.onMessageReceived(message));
        sendReceipt(DELIVERED, id);
        sendReceipt(READ, id);
    }

    private void sendReceipt(byte type, String id) {
        executor.execute(() -> {
            try { writeFrame(type, id, 0L, 0L, new byte[0]); }
            catch (IOException ignored) { }
        });
    }

    private void writeFrame(byte type, String id, long timestamp, long duration, byte[] payload)
            throws IOException {
        synchronized (writeLock) {
            DataOutputStream stream = output;
            if (!isConnected() || stream == null) throw new IOException("Not connected");
            stream.writeInt(MAGIC);
            stream.writeByte(type);
            stream.writeUTF(id);
            stream.writeLong(timestamp);
            stream.writeLong(duration);
            stream.writeInt(payload.length);
            stream.write(payload);
            stream.flush();
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
        executor.shutdownNow();
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
}
