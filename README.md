# Secure Audio Intercom (Transmitter + Receiver)

Two Android apps that let one phone (**Transmitter**) stream its microphone,
in real time, to a second phone the user has explicitly paired
(**Receiver**). Built entirely on free/open-source components. No paid
APIs, no hidden recording — the transmitter only ever streams while a
persistent, visible foreground-service notification is showing, and only to
a peer the user paired via QR code / short code.

```
audio-stream-project/
├── signaling-server/        Node.js WebSocket relay (internet mode only)
├── transmitter-app/         Android app: capture + send
└── receiver-app/            Android app: receive + play
```

## 1. How it fits together

### Transport & codec
- **WebRTC** (audio-only `PeerConnection`) carries the media.
- Opus codec is negotiated automatically by WebRTC (it's the default,
  royalty-free codec for the `audio` m-line) — no extra encoder code needed;
  `AudioRecord` feeds WebRTC's built-in audio device module, WebRTC encodes
  Opus and decodes it back to `AudioTrack`/speaker on the far end.
- **Encryption is mandatory and automatic**: WebRTC media is always
  SRTP/DTLS-encrypted, you cannot turn this off. The signaling channel
  is additionally encrypted (`wss://` in internet mode; see §4 for local
  mode).

### Two connection modes
| Mode | Signaling | Media path | When to use |
|---|---|---|---|
| **Same Wi-Fi (no server)** | A tiny WebSocket server embedded *inside the Transmitter app itself*, discovered via Android `NsdManager` (mDNS) | Direct peer-to-peer, LAN host ICE candidates only | Both phones on the same router/hotspot |
| **Internet** | Self-hosted Node.js signaling relay (`signaling-server/`) | WebRTC over STUN, relayed by your own **coturn** TURN server if either side is behind strict NAT | Phones on different networks |

Both modes speak the *same* JSON signaling protocol
(`offer` / `answer` / `candidate` / `bye`), so the app-side WebRTC code
doesn't care which transport is underneath.

### Pairing
1. Transmitter generates a random 6-digit code + session id and (in LAN mode)
   its local IP:port, or (internet mode) the relay URL + room code.
2. That payload is rendered as a QR code (ZXing, open source) and also shown
   as plain digits for manual entry.
3. Receiver scans the QR (CameraX + ZXing) or types the code.
4. Only a peer holding that exact code can join the WebRTC session, and the
   code is single-use / time-limited (5 minutes).

### Consent & transparency (baked in, not optional)
- Mic capture only starts after the runtime `RECORD_AUDIO` permission
  prompt is accepted **and** the user taps **START** on the Transmitter.
- A `Foreground Service` with `foregroundServiceType="microphone"` is
  required to run `AudioRecord` in the background on Android 14+, which
  forces a permanent, un-dismissable notification ("Streaming microphone
  audio to <peer>") while capture is active — there is no code path that
  captures audio without that notification.
- STOP immediately tears down the `PeerConnection` and stops the service.

---

## 2. Prerequisites

- **Android Studio** (Ladybird/Koala or newer), Kotlin 1.9+, min SDK 26,
  target SDK 34/35.
- Two physical Android phones for real testing (emulators fight with mic
  and NSD).
- Node.js 18+ only if you want internet mode (`signaling-server/`).
- (Optional, internet mode across strict NATs) a small VPS to run
  **coturn** (open source TURN server, free).

---

---

## 3. Getting an installable APK without Android Studio (GitHub Actions)

This repo includes `.github/workflows/build-apks.yml`, which builds both
apps on GitHub's own servers (free for public repos, and within the free
monthly minutes for private ones) and hands you back ready-to-install
debug APKs — no local Android SDK needed.

1. Create a new GitHub repo and push this whole `audio-stream-project/`
   folder to it:
   ```bash
   cd audio-stream-project
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/audio-intercom.git
   git push -u origin main
   ```
2. On GitHub, open the repo -> **Actions** tab. The "Build APKs" workflow
   runs automatically on push (or click **Run workflow** to trigger it
   manually).
3. Wait for the run to go green (a few minutes - it's compiling two
   Android apps from scratch).
4. Open the finished run -> scroll to **Artifacts** -> download
   `transmitter-debug-apk` and `receiver-debug-apk` (each is a zip
   containing one `app-debug.apk`).
5. Copy each `app-debug.apk` to the corresponding phone (email it to
   yourself, use a USB cable, Google Drive, etc.) and tap it to install.
   Since it's not from the Play Store, Android will ask you to allow
   "install unknown apps" for whichever app you opened it from (Files,
   Gmail, Chrome...) - that's expected for any APK installed outside the
   Play Store, debug or not.
6. These are **debug builds** - fine for testing on your own two phones.
   For anything wider, build a signed release APK instead (Android
   Studio: **Build > Generate Signed App Bundle / APK**, or add a signing
   step to the workflow using a keystore stored in GitHub Secrets).

## 4. Build & run — step by step

### Step 1 — Open the two app projects
`transmitter-app/` and `receiver-app/` are independent Gradle projects.
Open each one in its own Android Studio window
(`File > Open > transmitter-app`, then again for `receiver-app`).

### Step 2 — Add the WebRTC dependency
Both apps already declare, in `app/build.gradle.kts`:
```kotlin
implementation("io.github.webrtc-sdk:android:125.6422.07.1")
```
This is the actively-maintained, free, open-source build of Google's
WebRTC published to Maven Central by the `webrtc-sdk` project
(mirrors upstream `org.webrtc`, no license fee). If you prefer, you can
swap in your own self-built `libwebrtc.aar` — the Kotlin code only touches
the standard `org.webrtc.*` API surface, so nothing else changes.

### Step 3 — Same-Wi-Fi mode (no server needed)
1. Install both APKs, connect both phones to the same Wi-Fi/hotspot.
2. On the Transmitter: tap **Generate Pairing Code** → it starts an
   embedded local signaling server (`LocalSignalingServer.kt`) on a random
   free port and advertises it over NSD as `_audiotx._tcp.local.`.
3. On the Receiver: tap **Scan QR** (or **Discover on Wi-Fi**, which lists
   NSD-discovered transmitters) → pick the transmitter → it connects.
4. Tap **START** on the Transmitter, **PLAY** on the Receiver.

### Step 4 — Internet mode
1. Deploy the signaling server:
   ```bash
   cd signaling-server
   npm install
   node server.js          # listens on ws://0.0.0.0:8080 by default
   ```
   For real internet use, put it behind TLS (e.g. Caddy/nginx reverse proxy
   with a free Let's Encrypt cert) so it's `wss://yourdomain:443`.
2. (Optional but recommended for strict NATs) install **coturn** on the
   same or another VPS — see `signaling-server/coturn.conf.sample`.
3. In both apps' Settings screen, set the **Signaling URL**
   (`wss://yourdomain/ws`) and, if using coturn, the **TURN URL /
   username / password**.
4. Transmitter: **Generate Pairing Code** (internet mode) → shows a 6-digit
   room code + QR containing the signaling URL + code.
5. Receiver: enter/scan the code → connects through your relay.
6. START / PLAY as above.

### Step 5 - Test
- Confirm the persistent notification appears on the Transmitter the
  instant audio starts, and disappears the instant you tap STOP.
- Kill the Receiver app to home screen — audio should keep playing
  (media-type foreground service) until you tap STOP/DISCONNECT.
- Turn off Wi-Fi on the Transmitter mid-call in internet mode — it should
  reconnect over cellular; in LAN mode it will simply disconnect (by
  design — LAN mode never leaves the local network).

---

## 5. Security notes
- WebRTC media: DTLS-SRTP, always on, keys negotiated per-session — cannot
  be disabled.
- Signaling in internet mode: use `wss://` (TLS) in production; the sample
  server also supports plain `ws://` for local dev only — don't expose that
  to the internet.
- Signaling in LAN mode: traffic never leaves the local subnet; if you want
  belt-and-braces encryption there too, the embedded local server can be
  switched to `wss://` with a self-signed cert (see comment in
  `LocalSignalingServer.kt`) — the Receiver would need "trust this
  certificate" logic since it's self-signed.
- Pairing codes expire after 5 minutes and are single-use; a new code
  invalidates any old one.
- Neither app requests any permission beyond `RECORD_AUDIO`,
  `POST_NOTIFICATIONS`, `CAMERA` (Receiver, QR scan only),
  `ACCESS_WIFI_STATE`/`CHANGE_WIFI_MULTICAST_STATE` (NSD), and internet.
  Neither app has a code path to write audio to disk, upload it anywhere
  other than the paired peer, or start capture without the visible
  notification.

## 6. File map
See inline comments in each `.kt` file — the important ones:
- `TransmitterService.kt` / `ReceiverService.kt` — the foreground services.
- `WebRtcClient.kt` (in each app) — PeerConnection setup, SDP/ICE handling.
- `SignalingClient.kt` — WebSocket signaling, identical protocol both modes.
- `PairingManager.kt` (transmitter) / `NsdDiscovery.kt` (receiver) — code/QR
  and Wi-Fi discovery.
