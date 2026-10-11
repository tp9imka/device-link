# Release checklist

How the owner gets the Link v2 apps into the stores. Everything here is grounded in the repository;
items marked **unverified** were not confirmed from code or a real store submission.

Store apps: the Android receiver (`apps/receiver`, `dev.devicelink.receiver`) and the iOS app
(`ios/DeviceLink` with the share and notification extensions). `apps/sample` and `DeviceLinkSample` are
SDK samples; ship them only if you want to. The Mac menu-bar app (`DeviceLinkMac`) is distributed
outside the App Store with Developer ID.

## Before any store build: the built-in relay

A store build normally embeds the relay URL and enrollment token
(`devicelink.relayUrl` / `devicelink.enrollmentToken` in `apps/*/build.gradle.kts`, `DEVICELINK_RELAY_HOST` /
`DEVICELINK_ENROLLMENT_TOKEN` in `ios/Config/Private.xcconfig`). Both end up in plain text in the APK
manifest and the iOS `Info.plist`, so treat the embedded token as public: it only limits casual
enrollment. Rotate it on the relay if abuse shows up (already enrolled devices keep working, see
`server/README.md`). Builds without a built-in relay ask the user for one at setup.

## Android

### 1. Upload key (once)

Create it outside the repository and back it up; losing it means asking Play support for a key reset.

```sh
keytool -genkeypair -v -storetype PKCS12 -keystore ~/keys/devicelink-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Use Play App Signing (the default for new apps): Google holds the app signing key, this is the upload key.

### 2. Signing properties

Both `apps/receiver/build.gradle.kts` and `apps/sample/build.gradle.kts` read these from
`local.properties` (git-ignored), falling back to environment variables:

| `local.properties` key | Environment variable |
| --- | --- |
| `devicelink.signing.storeFile` (absolute path) | `DEVICELINK_SIGNING_STORE_FILE` |
| `devicelink.signing.storePassword` | `DEVICELINK_SIGNING_STORE_PASSWORD` |
| `devicelink.signing.keyAlias` | `DEVICELINK_SIGNING_KEY_ALIAS` |
| `devicelink.signing.keyPassword` | `DEVICELINK_SIGNING_KEY_PASSWORD` |

Without a store file the release build is unsigned (`receiver-release-unsigned.apk`), which is what CI
and fresh clones get. Never commit the keystore or the passwords.

### 3. Build

```sh
./gradlew :apps:receiver:bundleRelease      # Play upload: apps/receiver/build/outputs/bundle/release/receiver-release.aab
./gradlew :apps:receiver:assembleRelease    # side-loaded testers: apps/receiver/build/outputs/apk/release/receiver-release.apk
```

Release builds run R8 (`isMinifyEnabled = true`). Before the first upload, install the release APK on a
real phone and run pairing, a text clip, an image and a file send (cases P1, D1, D7 in
`docs/validation-kit.md`). That has not been done yet: **unverified** that R8 keeps everything the SDK
needs at runtime (`sdk/android/consumer-rules.pro`).

### 4. Versions

Each upload needs a higher `versionCode`. Bump `versionCode` (integer, +1 per upload) and `versionName`
(user-visible, currently `2.0.0`) in `defaultConfig` of each app's `build.gradle.kts`.

### 5. Target API

Both apps set `targetSdk = 36` and `compileSdk = 37`. Google Play currently requires new apps and updates
to target API 36 ([Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)),
so this passes today. Recheck that page before each release; the bar rises every year.

## Play Console declarations

Ready-to-paste text. It describes the receiver; the sample has the same service and permissions
(they come from `sdk/android/src/main/AndroidManifest.xml`) but no keyboard.

### Foreground service: `remoteMessaging`

`dev.devicelink.sdk.ReceiverService` (`foregroundServiceType="remoteMessaging"`).

> DeviceLink lets a person copy something on one of their own devices and paste it on another. When the
> user turns on "Receive clips on this phone", a foreground service keeps a connection to the user's own
> relay server so that text, images and files the user sends from their other linked devices arrive
> immediately and are placed on this phone's clipboard. While it runs, a persistent notification titled
> "DeviceLink is on" (channel "Receiving") shows the connection state and has a "Turn off" button. The
> user stops it with that button or by turning off the Receive switch in the app; when Receive is off,
> the service is stopped and the phone does not poll the relay. If the user left Receive on, the service restarts after a
> reboot or app update. Delaying delivery breaks the feature: the user is waiting at the other device to
> paste what they just sent.

Demo video: show turning Receive on, sending a clip from a second device, the notification, and "Turn off".

### Input method (the optional DeviceLink keyboard)

`dev.devicelink.receiver.DeviceLinkKeyboard`, behaviour from `DeviceLinkKeyboard.kt` and
`keyboard/AutoSendSetting.kt`:

> The DeviceLink keyboard is an optional, ordinary QWERTY keyboard. It stores and sends nothing the user
> types: there is no dictionary, no learning and no typing history. Its bar above the keys has three
> controls: paste the last clip received from a linked device, send the current clipboard now (one tap,
> user-initiated), and an "Auto-send" switch. Auto-send is off by default. The first time the user turns
> it on, the keyboard shows a disclosure ("Send every copy automatically?") explaining that while
> DeviceLink is the active keyboard, whatever the user copies in any app (text, links, images) is sent,
> end-to-end encrypted, to their linked devices (listed by name), that copies password managers mark as
> sensitive are skipped, and how to stop. Auto-send turns on only if the user taps "Turn on";
> "Not now" or dismissing keeps it off and the next attempt asks again. While auto-send is off the
> keyboard does not read the clipboard. While it is on, it reads a clip only when the system reports a
> new copy, skips clips flagged sensitive, and sends nothing when no device is linked. The user turns it
> off with one tap on the same switch, or by switching to another keyboard (Android lets only the current
> keyboard see clipboard changes from other apps).

### Notifications (`POST_NOTIFICATIONS`)

> Used for the required foreground-service notification while receiving, a short "Copied from <device>"
> notice for each received clip, and a notification to open or share received images and files. The app
> explains this before showing the system permission prompt.

### Camera (`CAMERA`, declared by the embedded `com.journeyapps:zxing-android-embedded` scanner)

> Used only to scan the one-time pairing QR code shown on the user's other device, inside the app's
> scanner screen. Frames are decoded on the device and never stored or uploaded.

### Data safety form

Dependencies checked in `gradle/libs.versions.toml` and the module build files: the apps ship
kotlinx-coroutines, kotlinx-serialization, Google Tink (crypto), ZXing core and zxing-android-embedded
(QR). The receiver and sample use no AndroidX, Google Play services, Firebase, analytics, crash
reporting or advertising SDK. The apps talk only to the relay configured for them.

What leaves the device, from `sdk/core` and `server/relay/store.py`:

| Data | Where | Declare as |
| --- | --- | --- |
| Clip text, images, files, device names | End-to-end encrypted to the recipient device (HPKE, `docs/protocol/link-v2.md`), relay cannot read them | Not collected (Play's end-to-end encryption exemption) |
| Device public keys, device ID (SHA-256 of the key), which devices are linked | Relay, until the operator deletes the device | Device or other IDs: collected, not shared, required, app functionality |
| Platform, phone model, app version | Relay, sent at registration | App info and performance → Diagnostics: collected, not shared, required, app functionality |
| Item sizes, timestamps, send/receive counters, event log | Relay; event log 7 days by default | Covered by the rows above (**unverified** whether Play wants "App interactions" for counters; declaring it is the safe choice) |

Other answers:

- Is all data encrypted in transit? Yes. Release builds refuse plain `http://` relays
  (`android:usesCleartextTraffic="false"`; `allowInsecureRelay` is only on for debuggable builds).
- Data shared with third parties: none. The relay belongs to the person or organisation that runs it.
- Can users request deletion? Yes, through the relay operator, who removes a device in `/admin`.
  Resetting in the app (Android "Reset this phone…", iOS "Reset device") deletes local keys and links; it does **not** delete the device row on the
  relay (no client unregister endpoint exists).
- Privacy policy URL: publish `docs/wiki/Privacy.md` (wiki page "Privacy") with the placeholders filled in.

## iOS (App Store)

### Privacy nutrition label

Matches `ios/DeviceLink/PrivacyInfo.xcprivacy` (same content in `ios/ShareExtension` and `ios/Sample`):

- Tracking: No. No tracking domains.
- Data Not Linked to You: Identifiers → Device ID (app functionality); Diagnostics → Other Diagnostic
  Data (platform, model, app version; app functionality).
- Not collected: clipboard content, photos and files the user sends (end-to-end encrypted, relay cannot
  read them), contacts, location, usage data.
- When push is enabled, the app also sends its APNs device token to the relay
  (`server/relay/store.py`, `set_push`). The manifest does not list it separately; **unverified**
  whether App Review expects it under Identifiers. The push payload Apple carries is fixed text
  ("New item from a linked device") plus a message ID (`server/relay/push.py`); the notification
  extension decrypts the preview on the device.

Required-reason API: `UserDefaults` with reason `CA92.1`, declared. Camera usage string:
"Scan the pairing code shown on your other device." (`ios/project.yml`).

### Signing and APNs environment

1. Copy `ios/Config/Private.example.xcconfig` to `Private.xcconfig` and set `DEVELOPMENT_TEAM`,
   `DL_BUNDLE_PREFIX` and the relay values. `cd ios && xcodegen`.
2. In the developer portal (or let automatic signing do it): App IDs for the app and its `.share` and
   `.notify` extensions, the App Group `group.<prefix>.ios` on all three, Push Notifications on the app ID.
3. `aps-environment` comes from `DL_APS_ENVIRONMENT` in `ios/project.yml`: `development` for Debug,
   `production` for Release. Archives use Release, so TestFlight and App Store builds register
   production tokens, and the app reports the matching environment to the relay. Nothing to switch by hand.
4. On the relay set `apns_key_path`, `apns_key_id`, `apns_team_id` for a real `.p8` key (keep it outside git).

### TestFlight

1. Bump `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `ios/project.yml` (build number +1 per upload), `xcodegen`.
2. Xcode → scheme DeviceLink → Any iOS Device → Product → Archive. The archive includes DeviceLinkShare and DeviceLinkNotify.
3. Organizer → Distribute App → App Store Connect → Upload. Xcode's privacy report should show the manifests.
4. In App Store Connect add the build to an internal group, then an external group (needs beta review).
5. Run P1, D1, D7 on two real devices against the production relay, including a push wake-up.

### Mac (Developer ID, notarized)

`DeviceLinkMac` has the hardened runtime on (`ENABLE_HARDENED_RUNTIME: YES`, `ios/project.yml`) and needs no
runtime exceptions. It reads the pasteboard on its own for two-way sync and skips concealed/transient
items (`ios/Mac/MacApp.swift`).

```sh
xcodebuild -project ios/DeviceLink.xcodeproj -scheme DeviceLinkMac -configuration Release \
  -archivePath build/DeviceLinkMac.xcarchive archive
# Organizer → Distribute App → Developer ID → Upload (notarizes), or export and:
xcrun notarytool submit DeviceLinkMac.zip --keychain-profile <profile> --wait
xcrun stapler staple DeviceLink.app
```

**Unverified**: no Developer ID build has been notarized yet; ad-hoc builds cannot show the runtime flag.

## Relay operator obligations

Whoever runs the relay controls the metadata, so the store listings point users at them. From
`server/README.md`, `server/relay/config.py` and `server/relay/store.py`:

- HTTPS only, behind a reverse proxy or tunnel; never expose port 8000. Templates: `server/deploy/`.
- Set `enrollment_token` on any public relay; keep it in a root-readable environment file.
- Admin dashboard: set `admin_password_hash` (`python -m relay.passwd`) and `session_secret`. The dashboard shows metadata, never content.
- Backups: `python -m relay.backup DEST --keep N` (timer in `server/deploy/`). Backups hold metadata and
  short-lived ciphertext; keep them private and encrypted at rest.
- Access logs are off in the normal runner; keep proxy request logs off too (`deploy/nginx.conf` does).

What the relay keeps:

| Data | Lifetime |
| --- | --- |
| Encrypted items | Until delivered and acknowledged, else expiry: clients ask 5 minutes, relay cap `max_ttl_ms` 10 minutes; the janitor runs every 10 s |
| Pairing rendezvous (sealed) | Until confirmed or expired |
| Replay nonces | Until the signed timestamp can no longer be accepted (about ±60 s) |
| Event log (kind, device IDs, size, time) | `event_retention_ms` 7 days, at most `max_events` 50,000 |
| Devices (public key, platform, model, app version, label, push token, counters, created/last seen) and links | Until the operator deletes the device in `/admin` (no automatic expiry) |

Deleting a row is not forensic erasure (SQLite reuses pages).
