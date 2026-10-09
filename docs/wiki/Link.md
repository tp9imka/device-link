# DeviceLink Link (v2): copy here, paste there

Link connects your devices (Android, iPhone, Mac/Linux terminal) through your own relay. Copy on one
device, paste on the other. Items are end-to-end encrypted, wait at most 5 minutes in the relay
"sandbox" and are deleted as soon as they are delivered.

## The pieces

| Piece | Where | Role |
| --- | --- | --- |
| Relay (sandbox) | `server/` | Holds encrypted items for up to 5 min (cap 10), QR pairing rendezvous, admin dashboard at `/admin` |
| SDK | `sdk/core` (Kotlin/JVM), `sdk/android`, `ios/DeviceLinkKit` | Identity, pairing, send, receive, clipboard and share integration |
| DeviceLink app | `apps/receiver` (Android), `ios/DeviceLink` | Thin router: receives into the clipboard, lists clips, sends back |
| Sample apps | `apps/sample`, `ios/Sample` | SDK integration: everything copied in the app is sent |
| CLI | `ios/DeviceLinkKit` → `devicelink` | Desktop/terminal client and interop harness |

## Set up once

1. Run the relay (see [[Hosting]]) and note its public `https://` URL.
2. Put the URL (and the enrollment token, if set) in the apps' private build config:
   Android `local.properties` → `devicelink.relayUrl`, `devicelink.enrollmentToken`;
   iOS `ios/Config/Private.xcconfig` → `DEVICELINK_RELAY_HOST`, `DEVICELINK_ENROLLMENT_TOKEN`.
3. Install the apps. Nothing else to configure: each install creates its own device identity
   (Android Keystore / Secure Enclave) on first launch.

Builds without a built-in relay can scan the admin dashboard's **Setup** code instead.

## Link two devices

On device A tap **Show my code**. On device B tap **Scan a code** (or point the camera at it: the link
opens DeviceLink). That's it — both devices are linked in both directions. The code is valid for
5 minutes and works once; A shows the name of the device that joined. More devices: show the code again.

## Every day

| You want to… | Android | iPhone |
| --- | --- | --- |
| Receive into the clipboard | Automatic while **Receive** is on (status-bar notification) | Automatic while DeviceLink is open; otherwise tap the push alert, open the app, or run *Get DeviceLink clip* (Back Tap) within 5 min |
| Send what you copied in an SDK app | Automatic | Automatic |
| Send from any other app | Select text → **Send to device**; Share → **Send to device**; Quick Settings tile **Send clipboard** | Share → **Send to device**; Back Tap shortcut *Get Clipboard → Send to DeviceLink*; **Paste** in the app |
| Handle a file | Notification → **Share…** / **Open** | Clip list → Share… |

Neither platform lets an ordinary app read the clipboard in the background, so sending from other
apps is always one explicit gesture. Receiving into the clipboard is automatic on Android and
whenever the iPhone app runs.

## Privacy

The relay stores only sealed envelopes: routing IDs, sizes and timestamps are visible to it, content
is not (HPKE to the recipient's device key, signed by the sender's device key). The admin dashboard
shows the same metadata. See [protocol v2](../protocol/link-v2.md) and [ADR 0005](../adr/0005-link-v2-cross-platform.md).
