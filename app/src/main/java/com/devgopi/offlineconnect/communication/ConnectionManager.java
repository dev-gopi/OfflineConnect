package com.devgopi.offlineconnect.communication;

import android.content.Context;

import androidx.annotation.NonNull;

import com.devgopi.offlineconnect.model.Device;
import com.devgopi.offlineconnect.model.Message;
import com.devgopi.offlineconnect.R;

/** Selects the peer transport and exposes one messaging API to the chat screen. */
public final class ConnectionManager implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public interface Listener {
        void onStateChanged(State state);
        void onMessageReceived(Message message);
        void onMessageDeleted(String messageId);
        void onReceipt(String messageId, Message.Status status);
        void onSendProgress(String messageId, int percent);
        void onTypingChanged(boolean typing);
        void onSendFailed(String messageId, String reason);
        void onError(String message);
    }

    private final Listener listener;
    private final Context context;
    private final WifiDirectManager wifiDirect;
    private WifiMessageTransport wifiMessages;
    private final BluetoothMessageTransport bluetooth;
    private State state = State.DISCONNECTED;
    private Device activeDevice;

    public ConnectionManager(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        wifiDirect = new WifiDirectManager(context, new WifiDirectManager.Listener() {
            @Override public void onDeviceFound(Device device) { }
            @Override public void onConnectionChanged(boolean connected) {
                if (!connected) setState(State.DISCONNECTED);
            }
            @Override public void onConnectionReady(java.net.InetAddress owner, boolean groupOwner) {
                if (groupOwner) wifiMessages.startServer();
                else wifiMessages.connect(owner);
            }
            @Override public void onDiscoveryChanged(boolean discovering) { }
            @Override public void onError(String message) {
                setState(State.DISCONNECTED);
                listener.onError(message);
            }
        });
        wifiMessages = new WifiMessageTransport(context, new WifiMessageTransport.Listener() {
            @Override public void onConnecting() { setState(State.CONNECTING); }
            @Override public void onConnected() { setState(State.CONNECTED); }
            @Override public void onDisconnected() { setState(State.DISCONNECTED); }
            @Override public void onMessageReceived(Message message) {
                listener.onMessageReceived(message);
            }
            @Override public void onMessageDeleted(String messageId) {
                listener.onMessageDeleted(messageId);
            }
            @Override public void onReceipt(String messageId, Message.Status status) {
                listener.onReceipt(messageId, status);
            }
            @Override public void onSendProgress(String messageId, int percent) {
                listener.onSendProgress(messageId, percent);
            }
            @Override public void onTypingChanged(boolean typing) {
                listener.onTypingChanged(typing);
            }
            @Override public void onSendFailed(String messageId, String reason) {
                listener.onSendFailed(messageId, reason);
            }
            @Override public void onError(String message) {
                setState(State.DISCONNECTED);
                listener.onError(message);
            }
        });
        bluetooth = new BluetoothMessageTransport(context, new BluetoothMessageTransport.Listener() {
            @Override public void onConnecting() { setState(State.CONNECTING); }
            @Override public void onConnected(String peerAddress) { setState(State.CONNECTED); }
            @Override public void onDisconnected() { setState(State.DISCONNECTED); }
            @Override public void onMessageReceived(Message message) {
                listener.onMessageReceived(message);
            }
            @Override public void onMessageDeleted(String messageId) {
                listener.onMessageDeleted(messageId);
            }
            @Override public void onReceipt(String messageId, Message.Status status) {
                listener.onReceipt(messageId, status);
            }
            @Override public void onSendProgress(String messageId, int percent) {
                listener.onSendProgress(messageId, percent);
            }
            @Override public void onTypingChanged(boolean typing) {
                listener.onTypingChanged(typing);
            }
            @Override public void onSendFailed(String messageId, String reason) {
                listener.onSendFailed(messageId, reason);
            }
            @Override public void onError(String message) {
                setState(State.DISCONNECTED);
                listener.onError(message);
            }
        });
    }

    public State getState() { return state; }

    /** Selects the chat peer and prepares its transport to accept an incoming connection. */
    public void prepare(Device device) {
        activeDevice = device;
        if (device.getTransport() == Device.Transport.BLUETOOTH) bluetooth.startListening();
        else wifiDirect.prepareForConnection();
    }

    public void connect(Device device) {
        // prepare() is called when the chat opens. Do not enqueue a second listener here;
        // BluetoothMessageTransport also guards this internally for lifecycle races.
        activeDevice = device;
        if (device.getTransport() == Device.Transport.BLUETOOTH) {
            bluetooth.connect(device.getId());
        } else {
            setState(State.CONNECTING);
            wifiDirect.connect(device.getId());
        }
    }

    public void send(Message message) {
        if (activeDevice == null || state != State.CONNECTED) {
            listener.onSendFailed(message.getId(), context.getString(R.string.not_connected));
            return;
        }
        if (activeDevice.getTransport() == Device.Transport.BLUETOOTH) {
            bluetooth.send(message);
        } else {
            wifiMessages.send(message);
        }
    }

    /** Cancels only the selected media transfer; text and receipt frames remain unaffected. */
    public void cancelMediaTransfer(String messageId) {
        if (activeDevice == null) return;
        if (activeDevice.getTransport() == Device.Transport.BLUETOOTH) {
            bluetooth.cancelMediaTransfer(messageId);
        } else {
            wifiMessages.cancelMediaTransfer(messageId);
        }
    }

    public void sendDeletion(String messageId) {
        if (activeDevice == null || state != State.CONNECTED) return;
        if (activeDevice.getTransport() == Device.Transport.BLUETOOTH) {
            bluetooth.sendDeletion(messageId);
        } else {
            wifiMessages.sendDeletion(messageId);
        }
    }

    /** Typing presence is ephemeral and is never queued when disconnected. */
    public void sendTyping(boolean typing) {
        if (activeDevice == null || state != State.CONNECTED) return;
        if (activeDevice.getTransport() == Device.Transport.BLUETOOTH) {
            bluetooth.sendTyping(typing);
        } else {
            wifiMessages.sendTyping(typing);
        }
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        listener.onStateChanged(newState);
    }

    @Override public void close() {
        bluetooth.close();
        wifiMessages.close();
        wifiDirect.close();
        setState(State.DISCONNECTED);
    }
}
