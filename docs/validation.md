# Validation record

Date: 2026-10-08. This record separates executed checks from pending device work.

## Executed

- `./gradlew check`: passed locally. Includes model/security tests, transfer frame
  tests, architecture import guard, Android lint and debug APK assembly.
- Debug APK installed and launched on KATIM X3M (Android 15/API 35, no Google Play
  services) and Samsung SM-S921B (Android 16/API 36). Installation used normal ADB;
  application behavior uses ordinary Android permissions, never root.
- Nearby-device and notification permission requests exercised on both phones.
- KATIM appearance: light/dark, Iris accent, square corners and persistence across
  force-stop/relaunch verified. Font scale 1.5 checked for wrapping; restored1.0.
- Wi-Fi Direct DNS-SD discovery filtered out non-DeviceLink peers. Both phones
  discovered each other, including the advertised DeviceLink names. Connection
  attempts progressed to native group negotiation, but have not formed a group.
  Cryptographic pairing and payload transfer are not yet validated.
- Samsung's active hotspot caused Android's Wi-Fi Direct conflict prompt. The user
  turned the hotspot off before further radio tests.
- A later system-only test, with DeviceLink force-stopped on both phones, failed
  with native negotiation status 11 and no visible invitation on KATIM. Samsung
  subsequently showed the hotspot conflict prompt again. Further phone interaction
  is paused pending confirmation that the phones are free and hotspot may be
  disabled; the failure is not attributed conclusively to one cause.
- Both architecture diagrams pass Mermaid 10.9.5 parsing after removing a
  semicolon that the sequence parser treated as a statement separator.
- Repository Actions runs are queued. The repository runner API reports zero
  runners; remote CI has not executed. Local checks are the available evidence.

## Pending at this checkpoint

- First cryptographic pairing and remembered reconnection between the two phones.
- Text and files in both directions; acceptance, rejection, cancellation and exact
  received-byte comparison.
- Screen-off transfer, service teardown, Quick Settings tile and QR scan workflow.
- Idle/transfer battery measurements. USB-powered testing cannot establish drain.

## Evidence location

Ignored `artifacts/device-tests/` contains app screenshots from this session.
Build logs are local temporary artifacts. No personal payloads are used for tests.
This document will be updated as the remaining checks complete.
