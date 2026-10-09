# DeviceLink

**Copy on one device, paste on the other.** Android, iPhone and desktop devices linked with one QR
scan through your own relay. Content is end-to-end encrypted, waits at most 5 minutes in the relay
"sandbox" and is deleted as soon as it is delivered.

```
 Android app with the SDK ──copy──▶  relay (sandbox, E2E-encrypted, 5 min)  ──▶ iPhone / Android receiver
   (or any linked device)   ◀─────────────── Share / Send to device ─────────────── (clipboard + notification)
```

| Part | Path | Status |
| --- | --- | --- |
| Relay + admin dashboard | [`server/`](server/README.md) | 52 contract tests; `/admin` with alerts and export, `/metrics`, backups, [VM recipe](server/deploy/README.md) |
| Protocol v2 | [`docs/protocol/link-v2.md`](docs/protocol/link-v2.md) | Kotlin and Swift exchange checked-in vectors |
| Kotlin SDK core (JVM/Android) | `sdk/core` | Unit tests incl. RFC 9180/5869 vectors, live-relay test |
| Android SDK | `sdk/android` | Keystore identity, foreground receiver, clipboard, share prompts, pairing UI |
| Android receiver app | `apps/receiver` | Thin clip list, Quick Settings tile, share-sheet and text-selection send, optional DeviceLink keyboard that sends every copy, diagnostics |
| Android SDK sample | `apps/sample` | Auto-sends every in-app copy; synthetic samples and test scenarios |
| Swift SDK + CLI | [`ios/DeviceLinkKit`](ios/README.md) | Builds/tests on Linux and macOS; `devicelink` CLI with two-way clipboard `sync` |
| iOS app, share extension, notification previews, Shortcuts, sample | [`ios/`](ios/README.md) | Compiled in CI (`xcodebuild`); needs on-device validation |
| macOS menu bar app | [`ios/Mac`](ios/README.md) | Mirrors the Mac clipboard both ways; compiled in CI |

### Quick start

1. Run the relay locally and expose it with a Cloudflare tunnel: [Hosting](docs/wiki/Hosting.md).
2. Build the apps with the relay URL in private config (`local.properties` /
   `ios/Config/Private.xcconfig`). The first device then needs no setup at all.
3. Device A: **Show my code**. Device B: **Scan a code** (or the camera). Linked both ways.
4. Copy in the sample app (or Share › *Send to device*, select text › *Send to device*) and paste on
   the other device. Details per platform: [Link guide](docs/wiki/Link.md).

```sh
./gradlew check                                   # Android + Kotlin SDK (needs Android SDK)
(cd server && python -m pytest -q)                # relay
swift test --package-path ios/DeviceLinkKit       # Swift SDK
scripts/interop_e2e.sh                            # live relay, Kotlin <-> Swift pairing and exchange
```

Honest limits: neither Android nor iOS lets an ordinary app read the clipboard in the background, so
sending from *other* apps is one explicit gesture (share sheet, text selection, tile, Back Tap
shortcut). Receiving into the clipboard is automatic on Android and while the iOS app runs; a closed
iPhone app relies on a push alert or the Shortcuts action within the 5-minute window. Automated tests
and CI builds are not two-device evidence; see [validation](docs/validation.md), the device
[validation kit](docs/validation-kit.md) and the [handoff specs](todo/README.md) for hardware work.
Design record: [ADR 0005](docs/adr/0005-link-v2-cross-platform.md).

## Nearby Android app (Wi-Fi Direct)

The original two-phone Android app remains in `app/`, `core/` and `feature/`.


**Your other phone, one share away.** A native Android app for exchanging text,
links, photos and files between two phones. Nearby sharing uses Wi-Fi Direct;
optional **Internet Link** uses a self-hosted encrypted mailbox. Both phones install
DeviceLink; neither needs Fortress, an account or Google Play services. Nearby
sharing works without an internet connection or server.

**Status:** initial implementation validated on KATIM X3M and Samsung Galaxy S24
for pairing, two-way text/files, sharing back, persistence and cancellation.
The clipboard integration and local chat sample pass focused automated checks
and copy/paste validation on both phones, including background receiver writes. See the [validation record](docs/validation.md)
for evidence and remaining limits. Internet Link text/image clipboard delivery,
explicit file acceptance and sender-offline mailbox delivery were verified using
a local debug relay through USB tunnels. Both phones pasted actual received
images with the receiver backgrounded during delivery. The relay passes 32 contract
tests plus local/Docker startup checks. Public HTTPS/cellular deployment, sustained
Doze and battery measurements remain unverified.

DeviceLink uses Android Wi-Fi Direct and an authenticated, encrypted channel.
Start a temporary session, select the other phone, compare the pairing code once,
and send through Android's Share menu or DeviceLink's clipboard/file actions.

### Get started

Requirements: Android 8 or newer, Wi-Fi Direct support, Wi-Fi enabled on both
phones. Some devices also require Location enabled for Wi-Fi Direct discovery.
Android may ask to disable an active mobile hotspot or accept a Wi-Fi Direct
invitation. DeviceLink does not silently change those settings.

1. Install the same APK on both phones.
2. Open DeviceLink and start a session on each. Grant Nearby devices permission
   (Location permission on Android 12 and earlier); notifications are recommended.
3. Select the other phone under Nearby phones. Accept any Android connection prompt.
4. Compare all six digits on both phones and confirm on both. The phone identities
   are remembered; later sessions still authenticate possession of their keys.
5. Use **Share > DeviceLink** in another app, **Send clipboard**, or **Choose files**.
6. Accept incoming files. Use **Copy** for text, or **Open / Save / Share** for files.
7. Turn the session off, or let it expire. Pairing remains; radio work stops.

Add the **DeviceLink Quick Settings tile** through Android's tile editor to open
or stop a session quickly. Settings lets you rename the device, forget pairings,
choose 5/15/30-minute sessions, and change the appearance without rebuilding.

### Clipboard integration sample

Open the local chat sample to try explicit copy between paired phones. **Send**
adds a message to this phone's private history; it does not send a chat message
or change either clipboard. The last 50 messages persist locally, with a limit
of 8,192 UTF-8 bytes per message. History is not synchronized.

Tap a message's **Copy** button, or select message text and choose **Copy**.
This copies locally and, while a current authenticated connection is available,
sends a distinct clipboard command to the other phone. The receiving app checks
that the session is still current before writing its clipboard, then acknowledges
the actual write result. The tray distinguishes pending, copied and not-copied
outcomes; dispatch alone is not a clipboard-success acknowledgement.

With both transports off, Copy remains local and is never queued for reconnection.
The editable composer/paste field keeps ordinary local clipboard behavior. There
is no clipboard polling or background capture. Existing Share/Send clipboard text
transfers still arrive in the tray and require the receiver to tap Copy.

Image Copy uses actual image bytes and a scoped content URI, with MIME validation;
it never pastes a URI as text. The sample can paste an image preview into its local
history. Supported image clipboard formats are PNG, JPEG, WebP and GIF, up to
16 MiB nearby or 8 MiB through Internet Link. Image paste depends on the receiving
app supporting image clipboard content. A newer received copy supersedes an older
image still transferring.

See [Clipboard integration](docs/wiki/Clipboard.md) for the contract and failure
semantics. Local chat Send remains local with either transport.

### Internet Link

Deploy the [relay server](server/README.md) behind HTTPS, then save the same relay
URL and any enrollment token on both phones. Connect the phones nearby once after
saving those settings: the verified channel exchanges identity-signed encryption
keys. Select the trusted peer and start Internet Link on each phone when remote
delivery is wanted. It is a separate, visible 15-minute session.

The relay holds complete end-to-end encrypted envelopes: text/files expire after
10 minutes (relay cap), clipboard actions after 60 seconds. Relay files are limited to 8 MiB;
receivers explicitly accept ordinary files. **Allow clipboard updates** is off by
default. With it off, incoming content stays available for manual Copy. Chat Copy
uses an active nearby connection first, otherwise an enabled Internet Link; a copy
made with both off remains local.

An accepted upload can wait for the receiver to come online before expiry. There
is no FCM/vendor push, no automatic session restart, and no background clipboard
capture. Internet Link polls while enabled and stops on timeout/manual off. See
[Internet Link](docs/wiki/Internet-Link.md) for setup, trust, receipts and limits.

### Build and verify

JDK 17 and Android SDK platform 37 / build tools are required. The Gradle wrapper
pins the build; create a git-ignored `local.properties` containing your `sdk.dir`.

```sh
./gradlew :app:assembleDebug
./gradlew check
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

The Python relay has its own dependency pins and test command in
[server/README.md](server/README.md); Android `check` does not run server tests.

`check` runs protected contract/security/lifecycle tests, architecture checks,
Android lint and debug APK assembly. **There is no coverage percentage gate.**
The final integrated run passed 66 Android/JVM tests; the separate relay suite
passed 32 contract tests.
Two-phone radio and UX checks are documented separately; a green JVM test suite
is not evidence of physical radio compatibility or battery life.

### Documentation

- [Architecture](docs/wiki/Architecture.md): modules, trust, data paths and lifecycle.
- [User workflow](docs/wiki/Workflow.md): first pairing, sharing, return path and recovery.
- [Clipboard integration](docs/wiki/Clipboard.md): explicit copy, local chat history and acknowledgements.
- [Internet Link](docs/wiki/Internet-Link.md): implemented encrypted mailbox, setup, limits and deployment.
- [Relay implementation record](docs/relay-proposal.md): decisions adopted from the original proposal and deferred work.
- [Battery and sessions](docs/wiki/Battery.md): resource policy and measurement procedure.
- [Security](docs/wiki/Security.md): authentication, bounds and threat-model limits.
- [Design system](docs/design-system.md): tokens, components and runtime customization.
- [Development](docs/wiki/Development.md): repository workflow, build and test ownership.
- [Validation](docs/validation.md): actual checks and device evidence for this revision.
- [ADR 0001](docs/adr/0001-architecture.md): architecture and scope decisions.
- [ADR 0003](docs/adr/0003-encrypted-internet-relay.md): asynchronous end-to-end encryption and relay sessions.
- [ADR 0004](docs/adr/0004-image-clipboard.md): real image clipboard data and stale-write protection.

`docs/wiki/` is the versioned wiki source, following KPNS's repo-owned wiki pattern.
The pages can also be published to the repository's Git wiki with
`scripts/publish-wiki.sh` after its first page has been initialized on the server.

### Honest limits

- Android does not allow an ordinary background app to read every clipboard
  change. Clipboard transfer requires a deliberate foreground action.
- File paste is not universally supported by Android apps. Open/Save/Share is
  the dependable file handoff.
- Clipboard application requires an active receiving session. Relay envelopes can
  wait for a later session within their expiry; there is no always-on discovery.
- Both phones need a version supporting the new `Clip` command for linked copy.
  Older peers reject that unknown message; ordinary `Text` encoding is unchanged.
- Wi-Fi Direct support and system prompts vary by manufacturer; active hotspot
  use can conflict with Wi-Fi Direct.
- Nearby transfers are cancelled by process death or connection loss. Relay
  uploads accepted by the server and locally stored encrypted file offers can
  survive restarts within their expiry. Partial network transfers do not resume;
  failed sends need an explicit retry.
- Received files stay in private app storage until removed. Transfer-tray text
  from nearby stays in memory. Ordinary relay text persists locally with a
  24-hour envelope lifetime and expiry filtering on history load/save; clipboard
  text is ephemeral. This is not a timed secure-erasure guarantee. The separate
  local chat sample retains its last 50 messages in private storage. Private
  image copies use additional storage and are subject to an import quota.
- This initial protocol has focused adversarial tests and implementation review,
  but has not undergone an independent cryptographic security audit.
