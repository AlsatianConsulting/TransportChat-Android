# TransportChat (LAN-only encrypted chat for Android)

TransportChat is a lightweight, **local-network-only** chat and file transfer app for Android. It discovers peers on the same LAN using Android NSD (DNS-SD), establishes an encrypted session for each connection, and never relies on any cloud server.

- **Zero server**: peer-to-peer over your LAN (Wi-Fi / hotspot).
- **Encryption**: ephemeral key exchange and per-session encryption.
- **Discovery**: automatic via DNS-SD (`_lanonlychat._tcp.`), plus manual connect.
- **Clean notifications**: sanitized — show **who** sent a message, **never** the preview.
- **Blocking**: block a peer and their messages won’t be delivered (no silent “delivered” ack).
- **Renames**: rename chats/peers **for this session only** (not persisted).
- **Clear chats**: per-chat “Clear chat” action.
- **File transfer**: fast LAN transfers with inline progress.
- **Built with**: Kotlin, Jetpack Compose (Material 3).

> Package name: `dev.alsatianconsulting.transportchat`

---

## Contents
- [Features](#features)
- [Screens & Controls](#screens--controls)
- [Build & Run](#build--run)
- [Permissions](#permissions)
- [How It Works](#how-it-works)
- [Privacy & Security](#privacy--security)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [License](#license)

---

## Features
- **LAN-only** peer discovery via Android NSD/DNS-SD service type `_lanonlychat._tcp.`  
- **Manual connect** to `host:port` if discovery is unavailable.
- **Encrypted sessions** with ephemeral keys per connection.
- **Sanitized notifications**: only the sender name appears.
- **Blocklist (local)**: blocked peers are refused **before** handshake — their sender won’t see “Delivered”.
- **Session-only rename**: rename a peer/chat for the current run (labels reset after app restart).
- **Clear chat**: remove local chat history per peer on demand.
- **File transfers**: offer/accept with progress indicators; received files open via standard Android intents.
- **Read receipts**: show sending, delivered, and read indicators for messages you send.

---

## Screens & Controls

### Peers
- Shows discovered peers on your LAN and an unread badge.  
- **⋮ (more) per peer** → **Send Chat** (open conversation), **Rename Chat** (session-only label).
- **Refresh**: re-starts a discovery pass.
- **Manual Connect**: enter `Host/IP` and `Port` to connect to a peer directly.

### Chat
- Title reflects the (session-only) label and endpoint.
- **Send** messages and **Attach** files.
- **⋮ menu**: **Clear chat**, **Block/Unblock** this peer.
- Read status shows “Sending…”, “✓ Delivered”, or “✓✓ hh:mm”.

> **Blocked behavior**: When you block a peer, your server drops their connections immediately (no handshake, no ack). They won’t see “Delivered”.

---

## Build & Run

1. **Requirements**
   - Android Studio Ladybug+ (or later)
   - Android SDK 26+ (recommended target SDK 34+)
   - A physical device or emulator on the same Wi-Fi

2. **Open the project**
   - `File → Open…` and select the project root.

3. **Run**
   - Select a device and press **Run**.
   - On Android 13+, grant **Notifications** permission when prompted.

4. **Use**
   - Ensure both devices are on the **same network**.  
   - Open the app on both → peers appear automatically.  
   - Or use **Manual Connect** (enter the remote IP and listening port).

---

## Permissions

These are typically required:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<!-- Android 13+ (API 33): runtime permission -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- **INTERNET**: TCP connections on your LAN.
- **ACCESS_WIFI_STATE**: obtain IP and control multicast lock for discovery.
- **CHANGE_WIFI_MULTICAST_STATE**: receive multicast DNS packets for discovery.
- **POST_NOTIFICATIONS**: user-visible notifications on Android 13+.

> On Android 13+, the app will request notification permission at first launch.

---

## How It Works

- **Discovery**: peers advertise via Android NSD as `_lanonlychat._tcp.`; the app resolves services and filters out itself.
- **Handshake & Encryption**: when sending text or file offers, the client performs a handshake, exchanges ephemeral keys, and encrypts frames.
- **Delivery & Read**: the server replies with encrypted **DELIVERED** or **READ** acknowledgements as appropriate.  
- **Blocking**: server checks the blocklist at accept time and closes blocked sockets **before** handshake. No ack is sent.

---

## Privacy & Security

- **Local-only by design** — no central server.
- **Sanitized notifications** — show the sender name only (no message preview).
- **Blocklist enforced server-side** — blocked peers cannot deliver messages or file offers.
- **Ephemeral labels** — renamed titles are not persisted and vanish after app restart.

> Use on **trusted** LANs. While sessions are encrypted, network operators can observe connection metadata typical to LAN apps.

---

## Troubleshooting

- **Peers don’t show up**
  - Ensure devices are on the same subnet (e.g., both on 192.168.1.x).
  - Some APs block client-to-client traffic / multicast; try a different network or use **Manual Connect**.
  - Tap **Refresh** to restart discovery.

- **No notifications on Android 13+**
  - Grant permission in **System Settings → Apps → TransportChat → Notifications**.

- **Files won’t open after download**
  - The chosen app must support the MIME type; pick a different app or a generic viewer.

- **Sender still sees “Delivered” from a blocked peer**
  - Ensure both devices run this version; block enforcement happens server-side at accept time.

---

## FAQ

# Transport Chat — Quick FAQ

**What is Transport Chat?**  
A local-network, end-to-end encrypted chat and file-sharing app for phones and desktops. It works entirely on the **same LAN**—no server, no internet.

**Does it need the internet?**  
No. It runs over local Wi-Fi/Ethernet/hotspot. If the internet is slow or down, Transport Chat still works.

**How do peers find each other?**  
Automatic discovery via mDNS/NSD on the local subnet. If that’s blocked, you can manually add a peer by IP/port.

**What ports/protocols does it use?**  
TCP on port **7777** by default, plus multicast DNS for discovery.

**Is it encrypted?**  
Yes—messages and file transfers are end-to-end encrypted directly between devices.

**Does the app store my messages?**  
**No.** Messages are **not stored**. When you close the app, the entire chat history disappears.

**What about file transfers?**  
Files are sent directly over your LAN. Received files are saved to your device’s storage, but **messages themselves are not saved**.

**Do renamed peers persist?**  
No—renames are a local convenience and **reset when the app closes**.

**What platforms are supported?**  
Android and desktop (Windows/macOS/Linux). Cross-platform chats work as long as devices share a LAN.

**Will it work across different subnets or VLANs?**  
Auto-discovery usually won’t cross subnets. If routing allows, you can still connect by manually entering the peer’s IP.

**What about captive portals or guest Wi-Fi?**  
Some networks use “client isolation,” blocking device-to-device traffic. If that happens, create your own LAN (e.g., a travel router or personal hotspot) and have everyone join it.

**Use case: Cruises & flights**  
- **Cruises:** Ship internet is often pricey/slow. If your group is on the same LAN (or your own travel router’s LAN), you can chat and share files **without buying internet**. If the ship’s Wi-Fi isolates clients, a small travel router creates your own private LAN.  
- **Flights:** Many in-flight systems block passenger-to-passenger traffic, so peers won’t appear on the airline network. Some groups prep a **battery-powered travel router** before boarding; everyone joins that network in airplane mode with Wi-Fi on. Always follow airline rules—personal hotspots may be restricted.

**How far does it reach?**  
Whatever your LAN provides. A travel router often improves range and stability.

**Any setup tips?**  
- Put all devices on the same SSID (your own travel router is ideal).  
- If discovery fails, connect by IP.  
- Disable client isolation on *your* router (can’t change this on public/guest networks).  
- Keep screens awake during big file sends if your OS is aggressive about sleep.

**Is this legal to use everywhere?**  
It’s just local networking, but **follow venue policies** (airlines/ships may restrict hotspots) and local regulations.


---

## License

Copyright © Alsatian Consulting.

Licensed under the Apache 2.0 License. See `LICENSE` for details.
