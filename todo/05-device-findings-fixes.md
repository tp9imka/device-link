# 05 · Fixes driven by device findings

## Goal

Turn failures recorded by specs 02–04 into tested fixes, without weakening product boundaries.

## How to work

1. Take one finding at a time. Reproduce it on the device that showed it; capture the diagnostics
   report and admin events (no content).
2. Find the layer: wire/engine (`sdk/core` + `ios/DeviceLinkKit`, must stay in sync, update
   `docs/protocol/link-v2.md` and regenerate vectors with `DEVICELINK_WRITE_VECTORS=1`), platform
   SDK (`sdk/android`, `ios/Shared`), apps, or relay (`server/relay`).
3. Add a behavior test where the logic is testable off-device (engine, relay), then fix.
4. Run: `./gradlew check`, `./gradlew :sdk:core:test`, `cd server && python -m pytest -q`,
   `swift test --package-path ios/DeviceLinkKit`, `scripts/interop_e2e.sh`.
5. Re-run the failing kit case on the device and update `docs/validation.md`.

## Known candidates (decide from evidence, not in advance)

| Possible finding | Likely direction |
| --- | --- |
| OEM blocks clipboard writes from the foreground service while another app is in front (Android 10+) | Keep the item and show the notification **Copy** action (already there); make the notification say "Tap to copy" for that OEM, detected by a failed write |
| Long polls cut by a proxy/carrier before 25 s | Lower `pollWaitSeconds` adaptively after `SocketTimeoutException`, record in diagnostics |
| Battery above target (spec 03) | See spec 03 options |
| iOS Copy action can't write in background (spec 04) | `.foreground` action |
| Duplicate or partial file after network loss (kit D12) | Engine retry/assembler bug: add a FakeRelay test in `DeviceLinkClientTest` / `ChunkTests` |
| Linking asks again after an app update (kit P6) | Must never happen: check Keystore/keychain persistence and `links.json` location; add a regression test where possible |

## Rules

Never: background clipboard capture on stock Android, polling while "off", longer item lifetimes,
plaintext on the relay, logging content/names/URIs/keys, claiming device validation from emulators.
