# Validation record

Date: 2026-10-08. This record separates executed checks from pending device work.

## Executed

- `./gradlew check`: passed locally. Includes 17 model/security tests and 9 transfer
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

## Pending at this checkpoint

- End-to-end camera QR scan with physically aligned phones.
- Sustained both-screen-off/Doze transfers, older Android versions and more OEMs.
- Idle/transfer battery measurements. USB-powered testing cannot establish drain.
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
