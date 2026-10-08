# DeviceLink

**Your other phone, one share away.** A native Android app for exchanging text,
links, photos and files between two nearby phones. Both phones install DeviceLink;
neither needs Fortress, an account, Google Play services, or an internet connection.

**Status:** initial implementation validated on KATIM X3M and Samsung Galaxy S24
for pairing, two-way text/files, sharing back, persistence and cancellation.
The clipboard integration and local chat sample pass focused automated checks
and copy/paste validation on both phones, including background receiver writes. See the [validation record](docs/validation.md)
for evidence and remaining limits.

DeviceLink uses Android Wi-Fi Direct and an authenticated, encrypted channel.
Start a temporary session, select the other phone, compare the pairing code once,
and send through Android's Share menu or DeviceLink's clipboard/file actions.

## Get started

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

## Clipboard integration sample

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

Without a connection, Copy remains local and is never queued for reconnection.
The editable composer/paste field keeps ordinary local clipboard behavior. There
is no clipboard polling or background capture. Existing Share/Send clipboard text
transfers still arrive in the tray and require the receiver to tap Copy.

See [Clipboard integration](docs/wiki/Clipboard.md) for the contract and failure
semantics. This is a nearby integration sample, not a server-backed messenger.

## Build and verify

JDK 17 and Android SDK platform 37 / build tools are required. The Gradle wrapper
pins the build; create a git-ignored `local.properties` containing your `sdk.dir`.

```sh
./gradlew :app:assembleDebug
./gradlew check
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

`check` runs protected contract/security/lifecycle tests, architecture checks,
Android lint and debug APK assembly. **There is no coverage percentage gate.**
Two-phone radio and UX checks are documented separately; a green JVM test suite
is not evidence of physical radio compatibility or battery life.

## Documentation

- [Architecture](docs/wiki/Architecture.md): modules, trust, data paths and lifecycle.
- [User workflow](docs/wiki/Workflow.md): first pairing, sharing, return path and recovery.
- [Clipboard integration](docs/wiki/Clipboard.md): explicit copy, local chat history and acknowledgements.
- [Optional relay proposal](docs/relay-proposal.md): encrypted remote delivery, wake-up options and battery tradeoffs; not implemented.
- [Battery and sessions](docs/wiki/Battery.md): resource policy and measurement procedure.
- [Security](docs/wiki/Security.md): authentication, bounds and threat-model limits.
- [Design system](docs/design-system.md): tokens, components and runtime customization.
- [Development](docs/wiki/Development.md): repository workflow, build and test ownership.
- [Validation](docs/validation.md): actual checks and device evidence for this revision.
- [ADR 0001](docs/adr/0001-architecture.md): architecture and scope decisions.

`docs/wiki/` is the versioned wiki source, following KPNS's repo-owned wiki pattern.
The pages can also be published to the repository's Git wiki with
`scripts/publish-wiki.sh` after its first page has been initialized on the server.

## Honest limits

- Android does not allow an ordinary background app to read every clipboard
  change. Clipboard transfer requires a deliberate foreground action.
- File paste is not universally supported by Android apps. Open/Save/Share is
  the dependable file handoff.
- The receiving phone must have an active session. No always-on discovery.
- Both phones need a version supporting the new `Clip` command for linked copy.
  Older peers reject that unknown message; ordinary `Text` encoding is unchanged.
- Wi-Fi Direct support and system prompts vary by manufacturer; active hotspot
  use can conflict with Wi-Fi Direct.
- Transfers are cancelled by process death or connection loss. Retry by selecting
  the source again; resumable cross-process transfers are not implemented.
- Received files stay in private app storage until removed. Transfer-tray text
  stays in memory; the separate local chat sample retains its last 50 messages
  in private storage.
- This initial protocol has focused adversarial tests and implementation review,
  but has not undergone an independent cryptographic security audit.
