# 04 · APNs, iOS signing and on-device iOS checks

## Goal

Sign and run the iOS apps on a real iPhone with push wake-ups, and settle the iOS behaviors that
could only be compile-checked in CI.

## Needs (owner)

Apple Developer Program membership, an iPhone (iOS 17+), a Mac with Xcode 16+.

## Setup

1. `ios/Config/Private.xcconfig`: `DEVELOPMENT_TEAM`, `DL_BUNDLE_PREFIX` (reverse domain you own),
   `DEVICELINK_RELAY_HOST`. `cd ios && xcodegen`.
2. Developer portal (automatic signing usually creates these): App IDs for the app, `.share`,
   `.notify` and `.sample` extensions/targets; the App Group `group.<prefix>.ios`; Push Notifications
   capability on the main app. The shared keychain group `<prefix>.ios.shared` must be in the
   app, share and notification extensions' keychain-access-groups.
3. Keys → create an APNs key (.p8). On the relay (private config only): `apns_key_path`,
   `apns_key_id`, `apns_team_id`. Restart; admin Setup shows "Push: APNs on".
4. Development builds use the sandbox APNs environment (`aps-environment: development` in
   `ios/project.yml`); TestFlight/App Store builds need `production` (spec 06).

## Checks (record in docs/validation.md)

| Check | How | Notes |
| --- | --- | --- |
| Push registration | Admin Devices shows push ✓ for the iPhone | |
| NSE preview | Kill the app, send `T1` from Android | Expect "From <device>: …"; if the generic text shows, the extension failed to read keys (App Group / keychain group mismatch) |
| **Copy action (unverified)** | Long-press the notification → Copy, then paste elsewhere **without opening the app** | iOS may block pasteboard writes from a background action. Record the result exactly |
| Get clip intent | Back Tap → "Get DeviceLink clip" with the app killed | |
| Share extension | Photos → Share → DeviceLink (photo, video > 10 MB) | Chunked send |
| Camera QR | System Camera at an Android link QR | Landing page → app |
| Sensitive clip | Send `S1` | Local-only, expires after 2 min |
| Mac app | Run DeviceLinkMac, link via QR shown on the Mac, copy on the Mac | Password-manager copies must not send |

## If the Copy action cannot write the pasteboard in the background

Change the action in `ios/DeviceLink/DeviceLinkApp.swift` to `options: [.foreground]` (opens the app,
which applies the item), keep the NSE preview, and document it in `ios/README.md`. That is a small,
local change; run `swift test --package-path ios/DeviceLinkKit` and let CI build the apps.

## Done when

All checks have observed results on an iPhone, and the Copy-action question is settled in code/docs.
