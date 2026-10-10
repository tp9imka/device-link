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
| Send from any other app | Automatic with the **DeviceLink keyboard** (below); otherwise select text → **Send to device**, Share → **Send to device**, or the Quick Settings tile **Send clipboard** | Share → **Send to device**; Back Tap shortcut *Get Clipboard → Send to DeviceLink*; **Paste** in the app |
| Handle a file | Notification → **Share…** / **Open** | Clip list → Share… |

Neither platform lets an ordinary app read the clipboard in the background, so sending from other
apps is one explicit gesture, with one exception: Android lets the **current keyboard** see clipboard
changes. Receiving into the clipboard is automatic on Android and whenever the iPhone app runs.

### DeviceLink keyboard (Android, optional)

Enable it in Settings › System › Keyboard › On-screen keyboard › **DeviceLink keyboard**, then pick it
with the keyboard switcher. It is an everyday QWERTY keyboard, and while it is your keyboard every
copy in any app is sent to your linked devices: copy and forget.

- Bar above the keys: **Auto-send on/off** (tap to toggle), **Paste: …** inserts the last received
  clip, **Send ↑** sends the clipboard now.
- Typing: tap shift for one capital, double-tap or hold it for caps lock; sentences start capitalised
  where the app asks for it; double space types ". "; hold a key for the small character in its
  corner (numbers on the top row, symbols on the others); hold delete to repeat; slide on the space
  bar to move the cursor; hold the comma for emoji; **?123** for numbers and symbols, **=\<** for more.
- Enter shows the app's action (Go, Search, Send, Next, Done). Number and phone fields open a number pad.
- 🌐 (or holding space) switches to another keyboard.
- Clips that apps mark as sensitive (password managers) are never sent automatically.
- Nothing you type is stored or sent: there is no dictionary, prediction or learning. If you want
  autocorrect, keep your usual keyboard and switch to DeviceLink's when copying between devices.

## Privacy

The relay stores only sealed envelopes: routing IDs, sizes and timestamps are visible to it, content
is not (HPKE to the recipient's device key, signed by the sender's device key). The admin dashboard
shows the same metadata. See [protocol v2](../protocol/link-v2.md) and [ADR 0005](../adr/0005-link-v2-cross-platform.md).
