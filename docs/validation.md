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
- Wi-Fi Direct DNS-SD discovery filtered out non-DeviceLink peers. Samsung found
  the KATIM advertisement; first connection still stalled before group formation.
  Cryptographic pairing and payload transfer are not yet validated.
- Samsung's active hotspot caused Android's Wi-Fi Direct conflict prompt. The user
  turned the hotspot off before further radio tests.
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
