# Privacy policy

> Placeholders to fill in before publishing: `<operator name>`, `<operator contact>`, `<effective date>`.
> This page describes the DeviceLink apps (Android, iOS, Mac) and the DeviceLink relay software. It is
> not legal advice; check it against the law that applies to you.

Effective: `<effective date>`

## Who runs the service

DeviceLink is self-hosted. Your devices talk only to one relay server, chosen when the app was set up.
That relay is operated by `<operator name>` (you, or your organisation), not by the authors of the
software. The operator is responsible for the relay and for answering privacy requests:
`<operator contact>`.

The apps contain no analytics, advertising, crash-reporting or tracking code, and do not contact any
other server. On iPhone, notifications are delivered through Apple's push service (see below).

## What the relay can and cannot see

Everything you send (text, links, images, files, and the names of your devices) is end-to-end
encrypted on the sending device to the receiving device's own key (HPKE, RFC 9180; see
[docs/protocol/link-v2.md](../protocol/link-v2.md)). Only your linked devices hold the keys. The relay
operator cannot read your content, file names or device names.

Linking uses a one-time QR code. The secret in the code is never sent to the relay.

To route items, the relay stores:

- each device's public keys and an ID derived from them, and which of your devices are linked;
- the device's platform, model and app version, sent when it registers;
- an optional label the operator gives a device in the admin dashboard;
- on iPhone, if notifications are on, the Apple push token;
- per item: sender, recipient, size and times; per device: created and last-seen times and send/receive counts.

## How long data is kept

| Data | Kept for |
| --- | --- |
| Encrypted items on the relay | Deleted as soon as the receiving device confirms delivery, otherwise when they expire: 5 minutes by default, never more than 10 minutes |
| Activity log on the relay (event type, device IDs, size, time; no content) | 7 days by default, at most 50,000 entries |
| Device records and links on the relay | Until you reset the device in the app, or the operator removes it |
| Clip history on your device | Entries older than 24 hours are dropped (at most 50 entries) |
| Received files on Android | Deleted once older than 24 hours, checked each time the app starts |
| Received files on iPhone | Kept in the app until you remove the item or clear the list |

Relay backups, if the operator makes them, contain the same metadata and short-lived encrypted items.
The operator decides how long backups are kept: `<backup retention>`.

## What stays on your device

Your private keys never leave the device. Clip history and received files are kept in the app's private
storage (on Android excluded from device backups) for the times listed above. Resetting the device
in the app (Android: Info › **Reset this phone…**; iPhone: ⓘ › **Reset device**) first asks the relay to
delete that device's record, its push token, its links, the items waiting for it and its open pairing
codes, then deletes the keys, links and clip history on the device. The unlink notices it sends your other
devices wait on the relay until delivered or expired (at most 10 minutes). Activity-log entries already
written stay for their normal retention, and relay backups keep what they captured. If the relay cannot
be reached within a few seconds, the reset happens on the device only and the app tells you; then ask the
operator to remove the device record.

## Permissions and why

- **Network**: to reach your relay over HTTPS.
- **Notifications**: the Android "DeviceLink is on" notification while Receive is on, short "Copied
  from …" notices, and notices to open or share received files. On iPhone, "New item from a linked
  device" alerts; the text Apple delivers is fixed, and the preview is decrypted on your phone.
- **Camera**: only to scan the pairing QR code. Images are processed on the device and not stored.
- **Clipboard**: received items are placed on your clipboard. Your clipboard is read only when you act:
  tapping Send or Paste, sharing to DeviceLink, or, on Android, with the optional DeviceLink
  keyboard's Auto-send, which stays off until you accept an on-screen explanation. (The SDK sample
  apps also send what you copy while they are open.)
  The Mac app syncs the clipboard while it runs and skips items marked concealed or transient.
- **Run in background (Android)**: a foreground service receives items only while Receive is on and
  shows a notification; turning Receive off stops it. It resumes after a restart if you left it on.

The DeviceLink keyboard does not store or send what you type.

## Your choices and rights

You can turn Receive off, unlink devices, reset a device, or uninstall the app at any time. For access
to or deletion of the metadata held on the relay, contact `<operator contact>`.

Children: the apps are not directed at children `<adjust if needed>`.

## Changes

Changes to this policy are published on this page with a new effective date.
