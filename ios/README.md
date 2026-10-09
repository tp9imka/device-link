# DeviceLink for iOS

| Path | What it is |
| --- | --- |
| `DeviceLinkKit/` | Swift package: protocol v2 core (identities, HPKE envelopes, QR pairing, relay client, `DeviceLinkClient` actor) and the `devicelink` command-line client. Builds and tests on macOS and Linux. |
| `DeviceLink/` | Receiver app: link devices, clip list, copies incoming clips to the clipboard, sends back with Paste, photos and files. Includes the **Get DeviceLink clip** and **Send to DeviceLink** Shortcuts actions. |
| `ShareExtension/` | **Send to device** in the iOS share sheet. |
| `NotificationService/` | Notification service extension: decrypts the waiting item on device to show "From <device>: …" and offers a **Copy** action. |
| `Mac/` | DeviceLinkMac menu bar app (macOS 14+): mirrors the Mac clipboard to linked devices and back, skipping password-manager (concealed/transient) copies; shows or pastes link codes. |
| `Sample/` | SDK integration sample: everything copied inside the app is sent automatically; synthetic sample data and test scenarios. |
| `Shared/` | Code shared by the app targets: Secure Enclave identity, keychain, App Group store, clipboard writes, pairing UI. |

Requirements: Xcode 16+, iOS 17+ (CryptoKit HPKE), [XcodeGen](https://github.com/yonaskolb/XcodeGen).

## Build

```sh
cd ios
cp Config/Private.example.xcconfig Config/Private.xcconfig   # git-ignored: team, bundle prefix, relay host, token
xcodegen
open DeviceLink.xcodeproj
```

`DEVICELINK_RELAY_HOST` builds the relay into the app, so the first device needs no setup: it creates
its identity on first launch and can show a pairing code right away. Without it, scan a code from a
linked device (the relay comes from the code) or an admin **Setup** code from the relay dashboard.

App Groups and the shared keychain group let the share extension and Shortcuts use the same device
identity and links as the app. Push notifications are optional: set the relay's `apns_*` settings
and keep the `aps-environment` entitlement; otherwise remove it.

## How receiving works on iOS

iOS does not let apps run continuously in the background or read the clipboard silently, so:

- **App open (or just left):** clips arrive within a second and go straight to the clipboard.
- **App closed:** the relay holds the item for 5 minutes. With APNs configured the relay sends a
  content-free alert; the notification service extension decrypts a preview on the phone, and the
  **Copy** action (long-press) applies the item. Tapping it opens DeviceLink, which copies the item. Without push, opening the
  app or running **Get DeviceLink clip** (Shortcuts, Back Tap, Action button, Siri) fetches and copies it.
- **Sending from iPhone:** Share › *Send to device* from any app, the in-app Paste button (no paste
  prompt), or a Shortcut *Get Clipboard → Send to DeviceLink* bound to Back Tap.

Recommended Back Tap setup: Settings › Accessibility › Touch › Back Tap › Double Tap → *Get DeviceLink
clip*; Triple Tap → a shortcut *Get Clipboard → Send to DeviceLink*.

## Desktop

- **macOS:** the `DeviceLinkMac` target (same `xcodegen` project). Copy on the Mac, paste on the phone and back.
- **Linux/macOS terminal:** `swift build -c release --package-path DeviceLinkKit`, then
  `devicelink sync` keeps the local clipboard (pbpaste / wl-paste / xclip) and linked devices in step.
  Run `devicelink` with no arguments for `setup`, `invite`, `join`, `send`, `watch` and the rest.

## Test

```sh
swift test --package-path DeviceLinkKit          # protocol, RFC 9180 vector, Kotlin interop vectors
../scripts/interop_e2e.sh                       # live relay: Kotlin <-> Swift pairing and exchange
```

The app targets are compiled in CI (`xcodebuild`, simulator, signing disabled). Device behaviour
(clipboard, push, Back Tap) still needs a manual check on real hardware; see `docs/validation.md`.
