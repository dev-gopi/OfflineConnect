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

    public interface Listener {
        void onConnecting();
        void onConnected(String peerAddress);
        void onDisconnected();
        void onMessageReceived(Message message);
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
        if (!ready() || serverSocket != null) return;
        ioExecutor.execute(() -> {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                while (!closed.get() && socket == null) {
                    BluetoothSocket accepted = serverSocket.accept();
                    if (accepted != null) attach(accepted);
                }
            } catch (IOException | SecurityException exception) {
                if (!closed.get()) postError("Bluetooth listening failed");
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void connect(String address) {
        if (!ready()) return;
        startListening();
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
                postError("Bluetooth connection failed. Ensure both phones opened this chat.");
                post(listener::onDisconnected);
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
                if (message.getBody().startsWith("voice://")) {
                    type = VOICE;
                    VoiceMetadata voice = VoiceMetadata.parse(message.getBody().substring(8));
                    File file = new File(voice.path);
                    if (!file.isFile() || file.length() > MAX_PAYLOAD_BYTES) {
                        throw new IOException("Voice recording is unavailable or too large");
                    }
                    payload = java.nio.file.Files.readAllBytes(file.toPath());
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

    private void sendReceipt(byte type, String messageId) {
        ioExecutor.execute(() -> {
            try { writeFrame(type, messageId, 0L, 0L, new byte[0]); }
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
            if (!closed.get()) postError("Bluetooth connection was lost");
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
            File voice = new File(context.getFilesDir(), "received_" + id + ".m4a");
            try (FileOutputStream stream = new FileOutputStream(voice)) { stream.write(payload); }
            body = "voice://" + voice.getAbsolutePath() + "|" + duration;
        } else {
            throw new IOException("Unknown Bluetooth frame type");
        }
        Message message = new Message(id, socket.getRemoteDevice().getAddress(), body,
                timestamp, false, Message.Status.RECEIVED);
        post(() -> listener.onMessageReceived(message));
        sendReceipt(DELIVERED, id);
        sendReceipt(READ, id); // Chat is visible while this activity-owned session is active.
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

    private boolean ready() {
        if (adapter == null) { postError("Bluetooth is not supported"); return false; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            postError("Bluetooth connection permission is required");
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
}
