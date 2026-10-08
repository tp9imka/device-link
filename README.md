# DeviceLink

**Your other phone, one share away.** A native Android app for exchanging text,
links, photos and files between two nearby phones. Both phones install DeviceLink;
neither needs Fortress, an account, Google Play services, or an internet connection.

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
- Wi-Fi Direct support and system prompts vary by manufacturer; active hotspot
  use can conflict with Wi-Fi Direct.
- Transfers are cancelled by process death or connection loss. Retry by selecting
  the source again; resumable cross-process transfers are not implemented.
- Received files stay in private app storage until removed; text stays in memory.
- This initial protocol has focused adversarial tests and implementation review,
  but has not undergone an independent cryptographic security audit.
