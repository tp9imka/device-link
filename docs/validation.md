# Validation record

## Link v2 (2026-10-09)

Executed in a Linux container (no phones, no emulator):

- Relay: 48 pytest contract tests (32 original + 16 Link v2: pairing rendezvous, single use, expiry,
  QR enrollment without token, long-poll wake, lifetime cap, metadata validation, APNs payload and
  token cleanup with a fake transport, admin login/CSRF/throttle, dashboard data without content,
  block/unlink/purge, expiry events, setup link, landing-page CSP, TOML/env settings).
- Dashboard rendered with seeded synthetic data in headless Chromium, light and dark themes; no
  console errors.
- Kotlin `sdk/core`: 24 JVM tests, including the RFC 9180 A.2.1 HPKE vector (independent reference
  implementation cross-checked against Tink output both ways) and RFC 5869 HKDF case 3.
- Swift `DeviceLinkKit`: 7 XCTest cases on Linux (swift-crypto), including RFC 9180 A.2.1.
- Cross-language vectors: Swift opens Kotlin envelopes/pairing blobs/request signatures and vice
  versa (`docs/protocol/vectors`). This caught one real defect (omitted default fields) before release.
- Live: `scripts/interop_e2e.sh` against the real relay with an enrollment token — Kotlin shows a code,
  Swift CLI joins with no token; text both ways, 150 KB image (SHA-256 verified), receipts; then the
  Swift CLI shows a code and a second Kotlin device joins. Passed. A Kotlin-only live test also passed.
- Android: `sdk/android`, `apps/receiver`, `apps/sample` compiled (Kotlin against `android.jar` API 37
  with aapt2-generated R classes). Full Gradle/AGP `check` (lint, assembly) runs in CI only: Google's
  Maven repository is not reachable from the container.
- iOS app targets are compiled only in CI (`xcodebuild`, simulator, unsigned).

Pending on real devices (not claimed):

- Android receiver: background clipboard writes from the foreground service on Android 10–16 and
  OEM skins, Doze/idle delivery latency, battery cost of the 25 s long poll, notification Share/Open/Copy,
  text-selection *Send to device*, Quick Settings tile, boot restore.
- iPhone: clipboard writes while foreground and in the 25 s grace period, APNs alert end to end,
  *Get DeviceLink clip* from Back Tap with the app closed, share extension with photos/files, VisionKit
  scanning, camera-app QR → landing page → app hand-off.
- Cloudflare tunnel in front of the relay over cellular; 5-minute expiry seen from a real offline phone.

## Nearby app and v1 Internet Link (2026-10-08)

This record separates executed checks from pending device work.

## Executed

- Initial nearby-delivery baseline: `./gradlew check` passed locally, including
  17 model/security tests and 9 transfer
  tests, architecture import guard, Android lint and debug APK assembly.
- Debug APK installed and launched on KATIM X3M (Android 15/API 35, no Google Play
  services) and Samsung SM-S921B (Android 16/API 36). Installation used normal ADB;
  application behavior uses ordinary Android permissions, never root.
- Nearby-device and notification permission requests exercised on both phones.
- KATIM appearance: light/dark, Iris accent, square corners and persistence across
  force-stop/relaunch verified. Font scale 1.5 checked for wrapping; restored 1.0.
- Wi-Fi Direct DNS-SD discovery filtered out non-DeviceLink peers. Both phones
  discovered each other, including the advertised DeviceLink names.
- Samsung's active hotspot caused Android's Wi-Fi Direct conflict prompt. The user
  turned the hotspot off before further radio tests.
- Earlier system-only failures were inconclusive: hotspot changes and session
  resets affected those attempts. After the user disabled hotspot again, a clean
  Katim-initiated connection displayed Samsung's Android consent prompt, formed
  a group and showed matching six-digit verification codes on both phones.
  Both approvals completed authenticated pairing.
- Remembered reconnection initiated from Samsung succeeded without another
  DeviceLink code confirmation; trust survived app updates/restarts.
- Synthetic text delivered in both directions. Samsung copied received text and
  sent it back using Send clipboard; Katim received the same text.
- Samsung sent a 58,000-byte file to Katim; received bytes and the copy saved through
  Android's document picker to Downloads matched the source SHA-256.
- Katim sent a 20 MiB file to Samsung. Samsung shared that received file back using
  Android Share > More > DeviceLink; the original filename and exact bytes survived
  the round trip. Both copies matched the source SHA-256.
- A 256 MiB transfer completed before a manual cancellation attempt. A subsequent
  1 GiB transfer was cancelled while in progress: the tray showed Cancelled,
  private incoming staging was empty, and a new text arrived over the same link.
- Declining a file offer appeared as Declined on the sender. Removing a completed
  synthetic 256 MiB receipt from the tray removed its private stored file.
- Completed receipt and paired identity survived Samsung force-stop/relaunch.
  Its peer detected disconnection. Both devices reported no Wi-Fi Direct group,
  discovery or listening afterward, and no LinkSessionService remained.
- Katim Quick Settings tile starts a session, updates to Available, and turns it
  off again. Its initially stale Off label was fixed with lifecycle-scoped state
  observation. After tile stop, group/discovery/listening were all absent.
- The 20 MiB transfer completed after sleep commands; Katim was observed Dozing.
  Samsung was observed Awake afterward, so this does not establish both-screen-off
  or sustained Doze reliability. Transfer wake locks were released at completion.
- Both architecture diagrams pass Mermaid 10.9.5 parsing after removing a
  semicolon that the sequence parser treated as a statement separator.
- Repository Actions runs are queued. The repository runner API reports zero
  runners; remote CI has not executed. Local checks are the available evidence.

## Clipboard extension: executed checks

At the text-clipboard checkpoint, focused JUnit reports showed **20 model tests and 12 transfer tests,
zero failures**. This includes three new clipboard protocol tests protecting the
ordinary Text golden bytes, distinct Clip/ClipResult types, UTF-8 limits and
malformed messages. Three delivery-registry tests protect one-shot application,
stale/cancelled/session-changed events and the bounded pending queue.

`./gradlew check` also passed after final application wiring and composer byte-limit
validation, including lint, architecture checks and APK assembly. The final APK
was installed on both phones.

- A separate temporary ordinary-permission debug app wrote a unique synthetic
  clipboard value while its activity was backgrounded. Foreground reads after
  window focus confirmed the value on both phones. The probe was uninstalled.
- In DeviceLink, message Copy delivered Katim → Samsung with Samsung on Home.
  Pasting through the receiver composer verified the URL without a tray Copy tap;
  the sender composer could paste the same value locally.
- Samsung copied a locally added message to Katim while Katim was on Home.
  Katim displayed Already copied and pasted the exact synthetic message afterward.
- Katim's normal selection-toolbar Copy delivered the selected URL to Samsung,
  verified by pasting into its composer.
- Ordinary Share text arrived in Samsung's tray but left its clipboard unchanged,
  verified by another paste of the earlier clipboard value.
- Chat Send created a Samsung-only message. Katim's tray still contained only the
  earlier clipboard transfer until the new message was explicitly copied.
  Samsung's private history retained that message across force-stop/relaunch.
- With the session off, copying the RTL/emoji fixture still allowed local paste.
  The disconnected send path is non-queuing by implementation review; reconnect
  was exercised, but an isolated delayed-replay hardware test remains pending.
- Copy full message delivered exactly 8,192 UTF-8 bytes. Pasting and appending one
  ASCII character disabled composer Send and preserved the oversized draft.
- Receiver tray labels showed Already copied and sender labels showed Copied on
  your other phone. No outgoing echo appeared in the receiver's visible tray.

Pending clipboard/chat checks:

- Isolated no-replay-after-reconnect check, repeated background delivery under
  sustained Doze, and selection Copy from the composer remaining local.
- Forced OS write failure and 10-second receiver / 15-second sender timeout tests
  on hardware. These paths have contract tests/review, not device fault injection.
- History capacity eviction and storage-failure UX on hardware. Persistence and
  the composer byte boundary were exercised; no exhaustive coverage is claimed.

## Internet Link and image clipboard: automated checkpoint

The integrated Android check passed after relay/image application wiring. Current
JUnit XML reports contain **66 Android/JVM tests: 34 model, 29 transfer and 3 app,
with zero failures, errors or skips**. The final `./gradlew check` passed.
Added coverage protects HPKE key/envelope authentication,
recipient binding, expiry/replay, image wire bounds, URL security policy, newer
clipboard-action ordering and staged-image cancellation cleanup. The Gradle gate
also runs architecture checks, Android lint and APK assembly. The final combined
`./gradlew check :app:assembleRelease` run succeeded (332 tasks), including the
optimized unsigned release APK. This validates packaging, not release signing or
installation of the release build on hardware.

The separate Python relay suite passes **32 contract tests** using temporary
SQLite databases and real signed requests. Checks include tampering/replay,
enrollment, recipient consent, mailbox ownership, durable ack/retry across restart,
expiry, concurrent quota enforcement, malformed inputs, long-poll wakeup,
exclusion and response limits. Dependency consistency (`pip check`) passed.
The final server rerun passed all 32 tests; its only warning is the upstream
Starlette/httpx TestClient deprecation, not a test failure.

Local Python startup and `/health` passed. The Docker image built successfully on
Linux ARM64 with pinned dependencies; its non-root container started and answered
`/health` through a loopback-only published test port. Smoke processes/containers
were stopped afterward. This does not validate a public TLS certificate, deployed
reverse proxy, systemd host setup or mobile-to-server delivery.

The October 9 follow-up adds protected lifecycle tests for the natural-deadline
finishing period, clipboard acknowledgement continuity, immediate explicit Stop,
and isolation from a restarted session. The UI and notification expose finishing
status and disable new work. Neither phone was attached for that final follow-up;
these deadline changes have automated validation, not a new hardware run.

## Internet Link and image clipboard: executed phone checks

The local Python relay ran on the development host. Each debug phone reached
`http://127.0.0.1:8000` through its own USB `adb reverse tcp:8000 tcp:8000` mapping.
Devices were KATIM X3M `DG2LHD1450610066` (Android 15) and Samsung Galaxy S24
`RFCX70AL6FL` (Android 16). These are ordinary app-permission tests; locked-device
access required the user to unlock the phone, never a lock bypass.

- Image clipboard delivery worked in both directions with the receiving phone
  on Home/backgrounded. Opening the receiver's local sample and using image Paste
  displayed the actual received image, rather than pasting URI text.
- KATIM → Samsung text Copy with Samsung backgrounded was verified by an actual
  paste of the exact synthetic text on Samsung, not only a Copied tray label.
- A temporary separate, unprivileged foreground probe on Samsung obtained the
  clipboard's `image/png` URI, opened it through the granted content permission,
  and decoded a 640 × 400 image. It confirmed the clipboard item was image content,
  with no text URI payload. The external probe was removed afterward.
- The manual `RelayDeviceProbe` instrumentation on KATIM authenticated the
  remembered nearby peer and waited for verified pinned relay-key exchange, then
  stopped nearby before sending. Relay text and image clipboard operations both
  received `COPIED` results. The ordinary image-file send completed only after an
  explicit Samsung **Receive** tap. Thus those transfers did not depend on an
  active nearby data channel.
- SHA-256 of the KATIM synthetic image fixture matched both Samsung received
  files: the clipboard image and the separately accepted ordinary file.
- Sender-offline mailbox delivery was exercised: KATIM sent ordinary text while
  Samsung's internet session was off; KATIM's session was then stopped; starting
  Samsung's internet session produced the received text from the relay mailbox.
- Chat-sample Send remained local-only and did not produce a server message.
- Receiver opt-out was exercised on Samsung: automatic clipboard application was
  saved off and its preference confirmed false. KATIM sent a new synthetic text
  Copy; Samsung showed **Received · use Copy to put it on your clipboard**. Actual
  Paste in Samsung's composer still produced the prior image attachment, proving
  that incoming text did not overwrite its clipboard while opted out.
- The native nearby image path was separately verified with both phones connected
  over authenticated Wi-Fi Direct. Samsung image Copy delivered while KATIM was
  on Home/backgrounded. Samsung's nearby tray showed **Copied on your other phone**;
  reopening KATIM's sample and invoking actual Paste produced an image attachment
  with the Remove image action. This exercises `ClipImageOffer`, independently of
  the relay checks above.

The evidence establishes these paths through the local debug relay and USB
tunnels. It does **not** establish public HTTPS/cellular deployment, two independent
internet networks, sustained Doze reliability or battery drain. Screen-background
tests are not sustained screen-off/Doze tests. The external probe's image read
occurred while that probe was focused, not as a background clipboard reader.
Forced clipboard-write failures, interrupted-network recovery and long-lived idle
behavior remain separate validation scenarios.

## Remaining checks

- End-to-end camera QR scan with physically aligned phones.
- Sustained both-screen-off/Doze transfers, older Android versions and more OEMs.
- Idle/transfer battery measurements. USB-powered testing cannot establish drain.
- Public HTTPS endpoint/certificate and cellular or independent-network delivery.
- Image retention under quota pressure, relay clipboard expiry races,
  and receiver restart/interrupted-network scenarios beyond the mailbox test above.
- The 30-second stalled socket-write watchdog is covered by implementation review
  and compilation; forced socket-stall behavior has not been measured on hardware.

## Payload integrity

| Synthetic payload | Bytes | SHA-256 (source and received) |
| --- | ---: | --- |
| device-link-smoke.txt | 58,000 | `e067d7b0ed115f5fa59022aee68b8e04f949ff500c3531eeb80da77434ac4d25` |
| device-link-large.bin | 20,971,520 | `3568217a72eed5450d704907de96e14c75cc1b18661f38e0c9f458e462b38def` |

## Evidence location

Ignored `artifacts/device-tests/` contains app screenshots from this session.
Build logs are local temporary artifacts. No personal payloads are used for tests.
This document will be updated as the remaining checks complete.
