# TransportChat

<a href="https://play.google.com/store/apps/details?id=dev.alsatianconsulting.transportchat"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"></a>

A LAN-only Android chat app with end-to-end encrypted messaging, file sharing, trust verification, and 1:1 voice/video calling. All communication is peer-to-peer over the local network — no server, no internet required.

## Features

- **End-to-end encryption** — Signal Protocol (Double Ratchet) for all messages
- **Encrypted local storage** — SQLCipher database protected by a user PIN or passphrase
- **LAN peer discovery** — UDP broadcast with TTL-based deduplication; no configuration required
- **Direct and group messaging** — pairwise fan-out E2EE for groups (no shared group key)
- **File transfers** — offer/accept/decline flow; multi-file sends are auto-zipped
- **1:1 voice and video calls** — WebRTC over the local network
- **Identity verification** — QR code scan + safety phrase comparison
- **Disappearing messages** — configurable timers (30 s – 1 week, or custom)
- **App lock** — PIN or passphrase with optional biometric unlock
- **Background transport** — foreground service with `BootReceiver` for persistence across reboots

## Requirements

- Android 10+ (min SDK 29), targeting Android 16 (SDK 36)
- Android Studio Meerkat or later
- JDK 17

## Build

```bash
# Debug build
./gradlew :app:assembleDebug

# Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Signed release build

Create a `signing.properties` file (do **not** commit it) alongside `local.properties`:

```properties
KEYSTORE_PATH=/path/to/your.jks
KEYSTORE_PASS=yourKeystorePassword
KEY_ALIAS=yourKeyAlias
KEY_PASS=yourKeyPassword
```

Then build, passing those properties on the command line:

```bash
./gradlew assembleRelease \
  -PKEYSTORE_PATH=/path/to/your.jks \
  -PKEYSTORE_PASS=... \
  -PKEY_ALIAS=... \
  -PKEY_PASS=...
```

## Architecture

```
UI (AppRoot.kt / AppController.kt)
  ↓ events / state
Core (AppContainer.kt)  ←→  Security layer
  ↓ reads/writes             (IdentityManager, SignalSessionManager,
Data layer                    AppLockManager, PersistentSignalProtocolStore)
  (EncryptedChatDatabase,
   ChatRepository, PeerRepository)
  ↑ inbound envelopes
Transport layer
  (LanPeerDiscovery, LanMessageClient/Server,
   LanTransportService, WebRtcCallManager)
```

**`AppContainer.kt`** — central orchestration: startup wiring, inbound envelope routing, file transfer state machine, call manager integration.

**`AppController.kt`** — UI state model and event dispatcher.

**`AppRoot.kt`** — single top-level Compose tree.

**`EncryptedChatDatabase.kt`** — full SQLCipher schema.

**`LanTransportService`** — foreground service keeping the transport alive in the background.

## Tech stack

| Layer | Technology |
|---|---|
| UI | Kotlin + Jetpack Compose |
| Encryption | Signal Protocol (libsignal-android) |
| Storage | SQLCipher |
| Calls | WebRTC (io.github.webrtc-sdk:android) |
| Barcode | ZXing (zxing-android-embedded) |

## License

Copyright © 2026 Alsatian Consulting. All rights reserved.
