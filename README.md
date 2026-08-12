# Offline Connect

Offline Connect is an Android peer-to-peer messaging application that communicates directly over Bluetooth or Wi-Fi Direct. It does not require a messaging server, cloud account, router, or active internet connection for local chats.

The project is written in Java and targets Android 8.0 (API 26) and newer.

## Features

- Discover nearby phones using Bluetooth and Wi-Fi Direct
- Connect and exchange messages in either direction
- Send text, voice notes, images, videos, contacts, and locations
- Keep text and control messages responsive while media is transferring
- Cancel image and video preparation or upload
- Show sending progress, timestamps, delivery states, retries, and read receipts
- Record voice notes with hold-to-record, swipe-to-cancel, and swipe-up-to-lock gestures
- Play voice notes with progress, duration, pause, seek, and playback speed controls
- Preview images and videos in a full-screen media viewer
- Save received media to the device
- Search messages and navigate between matches
- Filter all, starred, or pinned messages
- Edit, copy, star, pin, retry, and delete messages
- Synchronize local message history after reconnecting
- Show typing presence while a peer is connected
- Store recent Bluetooth peers for faster reopening
- Use a categorized emoji picker

## Connection options

### Bluetooth

Bluetooth discovery shows phone-class devices and compatible Offline Connect BLE peers. Message transport uses secure Classic Bluetooth RFCOMM.

For the most reliable connection:

1. Install the same application version on both phones.
2. Enable Bluetooth and grant Nearby Devices permissions.
3. Pair the phones in Android Bluetooth settings if requested.
4. Open the matching chat on both phones.
5. Press **Connect** on one phone.

### Wi-Fi Direct

Wi-Fi Direct creates a private local network between the phones. Internet access and a Wi-Fi router are not required.

1. Enable Wi-Fi on both phones.
2. Grant Nearby Wi-Fi and location permissions when requested.
3. Keep Location Services enabled on Android versions that require it for discovery.
4. Open **Find nearby devices** and scan on both phones.
5. Select the result labelled **Wi-Fi Direct**.
6. Press **Connect** on one phone and accept Android's invitation on the other phone.

One device becomes the Wi-Fi Direct group owner and hosts the local TCP messaging channel. The app automatically retries busy operations and removes stale groups when connection setup fails.

## Security

Offline Connect applies encryption in two separate places.

### Transport encryption

Each Bluetooth or Wi-Fi Direct connection performs an ephemeral ECDH P-256 key agreement. HKDF-SHA-256 derives a 256-bit session key, and every transport frame is protected with AES-256-GCM using a fresh random IV.

Encrypted frames include:

- Text and media data
- Delivery and read receipts
- Typing events
- Edits and deletions
- Upload cancellation events

Frame metadata is authenticated as additional authenticated data, so modified headers or payloads fail authentication.

> **Security limitation:** session keys are encrypted and ephemeral, but peer public keys are not yet verified with a QR code or trusted fingerprint. This protects against passive interception but does not fully prevent an active man-in-the-middle attack.

### Local database encryption

Message bodies are encrypted with AES-256-GCM before Room stores them. The non-exportable encryption key is generated in Android Keystore. Message ID, peer ID, timestamp, and direction are authenticated as additional data.

Media metadata is stored separately from message records. Media files are stored in the application's private files directory; the files themselves are not currently encrypted at rest.

## Architecture

```text
UI
├── SplashActivity
├── MainActivity
├── DevicesActivity
├── ChatActivity
└── MediaViewerActivity
        │
        ▼
ConnectionManager
├── BluetoothManager              discovery and advertising
├── BluetoothMessageTransport     RFCOMM messaging
├── WifiDirectManager             P2P discovery and group negotiation
└── WifiMessageTransport          TCP messaging over the P2P group
        │
        ├── SecureSession          ECDH, HKDF and AES-GCM
        └── Room databases
            ├── messages and recent devices
            └── media metadata
```

Transport frames are bounded to prevent oversized allocations. Media is sent in chunks so priority text, receipt, and typing frames can be written between media chunks.

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Android 8.0/API 26 or newer
- Two physical Android phones for complete Bluetooth and Wi-Fi Direct testing

Bluetooth and Wi-Fi Direct support are declared as optional hardware features, so the application can install on unsupported devices and report capability errors at runtime.

## Build

Clone the repository and run:

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Run static analysis:

```bash
./gradlew lintDebug
```

Build the optimized release variant:

```bash
./gradlew assembleRelease
```

Release builds enable code minification and resource shrinking. Configure a release signing key before distributing an APK.

## Permissions

The application requests only the permissions needed for enabled features:

| Permission | Purpose |
| --- | --- |
| Nearby Bluetooth / Bluetooth scan, connect, advertise | Discover and connect to nearby phones |
| Nearby Wi-Fi devices | Discover and connect through Wi-Fi Direct |
| Location | Required by older Android versions for nearby-device discovery and optional location sharing |
| Microphone | Record voice messages |
| Internet | Open shared map links and support local TCP sockets; local messaging itself does not require internet access |

Contacts are selected or added through Android system activities, so the app does not request broad contacts access.

## Media handling

- Selected images are resized to a maximum side of 1920 pixels and encoded as JPEG.
- Videos are copied into private app storage and checked against the transfer limit.
- Individual media transfers are limited to 16 MB.
- Message records contain media IDs and thumbnail metadata rather than raw media bytes.
- Received media remains private until the user chooses **Save to device**.

## Troubleshooting

### A device does not appear

- Confirm Bluetooth and Wi-Fi are enabled on both phones.
- Grant every requested Nearby Devices permission.
- Enable Location Services when using an Android version that requires it.
- Keep both phones unlocked and close together during discovery.
- Scan again instead of selecting an old Wi-Fi Direct result.

### Bluetooth stays on Connecting

- Install the same build on both phones.
- Pair the phones first if Android requests pairing.
- Open the chat on both phones, then press Connect on only one phone.
- Filter Logcat with `BluetoothTransport` for RFCOMM failures.

### Wi-Fi Direct reports an operation failure

- Remove old Wi-Fi Direct groups in Android settings.
- Toggle Wi-Fi off and on on both phones.
- Scan again and press Connect on one phone only.
- Some manufacturers provide incomplete or unstable Wi-Fi Direct implementations; behavior must be verified on the target physical devices.

## Current limitations

- Chats are one-to-one; group conversations are not implemented.
- Peer identity verification is not yet available.
- Media files are private but are not encrypted at rest.
- Video transcoding is not implemented; large videos must already fit the 16 MB limit.
- Reliable background listening would require a foreground service; the current connection lifecycle is activity-owned.
- Wi-Fi Direct behavior varies between Android manufacturers.

## Project status

Offline Connect is an active Android project. Test connection, reconnect, synchronization, cancellation, and media behavior on at least two physical phone models before production distribution.
