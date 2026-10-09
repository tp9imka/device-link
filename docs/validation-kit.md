# Link v2 device validation kit

A scripted checklist for real hardware. Each case has an ID, steps and a pass condition. Record
results in `docs/validation.md` (device, OS version, build, date, pass/fail, notes) and screenshots in
the git-ignored `artifacts/device-tests/`. Use only synthetic content (the fixtures below). Never paste
clipboard text, keys, peer names or diagnostics device IDs into issues or docs.

Emulators, simulators and unit tests do not count as device evidence. Mark a case **pass** only when
it was observed on the named physical devices.

## Setup

| Item | Value |
| --- | --- |
| Relay | Reachable over public HTTPS (named tunnel or VM; see `docs/wiki/Hosting.md`) |
| Admin | Signed in to `/admin` on a laptop; keep the Overview open during tests |
| Android A | Receiver app `dev.devicelink.receiver`, built with the relay URL + enrollment token |
| Android B / iPhone | Receiver app (Android) or DeviceLink (iOS), same relay |
| SDK host | Sample app `dev.devicelink.sample` (Android) or DeviceLinkSample (iOS) |
| Desktop (optional) | DeviceLinkMac menu bar app, or `devicelink sync` CLI |
| Networks | At least one run with the two phones on **different** networks (Wi-Fi + cellular) |

Fixtures (create on the test devices, never use real data):

- `T1` short text: `DeviceLink test 1 — Привет 👋 مرحبا` (Unicode, RTL, emoji)
- `T2` URL: `https://example.org/devicelink-test?case=T2`
- `T3` 8,000-character text (repeat `0123456789` 800 times)
- `H1` rich text: copy a bold word from a web page or Notes
- `S1` sensitive text: copy a generated password from the system password manager
- `I1` screenshot (PNG), `I2` camera photo (JPEG, several MB)
- `F1` 5 MB PDF, `F2` 30 MB video or zip (exercises chunking), `F3` 45 MB file (must be refused)

Record the SHA-256 of every file fixture before sending (`shasum -a 256` on desktop, or a file-hash app).

## 1. Pairing (once per pair, never repeated)

| ID | Steps | Pass |
| --- | --- | --- |
| P1 | Fresh install on A. Open the app. | A creates its identity with no prompt and shows **Show link code** (QR). Admin: A registered. |
| P2 | On B (fresh install), tap **Scan link code** and scan A's QR. | Both show "Linked" and the **same 6-digit code** once. Admin: one mutual link, pairing consumed. |
| P3 | Scan the same QR again from a third device or B after unlinking. | Refused (code already used). |
| P4 | Wait 6 minutes with a QR shown, then scan it. | Refused (expired); a fresh code works. |
| P5 | iPhone: point the system **Camera** at A's QR. | Opens DeviceLink (or the `/pair` landing page, then the app) and links. |
| P6 | Force-stop/reboot both devices, then send `T1`. | Delivered with **no new code, scan or prompt**. Links survive restarts and app updates. |
| P7 | Link a third device (C) by scanning **B's** code. | C is linked to B. Sending from B can target A, C or all (peer chip menu). |

## 2. Delivery and clipboard

| ID | Steps | Pass |
| --- | --- | --- |
| D1 | A receiver on, screen on, other app in front. Copy `T1` in the sample app on B. | Within ~2 s A's clipboard holds `T1` exactly (paste into a notes app). Notification shown. |
| D2 | Same with A's **screen off for 10 min** (not charging). Send `T2`, wake A, paste. | `T2` pasted. Note the delay shown in admin (Median delivery / events). |
| D3 | Send `T3`. | Exact 8,000 characters pasted (compare length/end). |
| D4 | Send `H1` to a device and paste into a rich editor (Docs/Notes/Mail). | Formatting kept; plain-text editors get plain text. |
| D5 | Send `S1`. | Notification and history are **masked**. Android 13+: clipboard preview hidden. iOS: the clip is local-only and gone after 2 min. |
| D6 | Send `I1` and `I2`. | Image pasted as an image (not a URI string) in a chat or notes app. |
| D7 | Send `F1`. | Notification **Open / Share / Save**; Share shows the system chooser; saved bytes match SHA-256. |
| D8 | Send `F2` (> 10 MB). | Arrives as **one** item after reassembly; SHA-256 matches; admin shows several envelopes then none. |
| D9 | Try to send `F3`. | Refused locally with a clear message; nothing uploaded. |
| D10 | Turn A's receiver **off** (or close the iOS app), send `T1`, wait 6 minutes, turn it on. | Item never arrives (expired). Admin: an `expired` event, sandbox empty. |
| D11 | Turn A's receiver off, send `T2`, turn it on after 1 min. | `T2` arrives once. |
| D12 | Airplane mode on the sender mid-send of `F2`, then off within 1 min. | Either delivered once (retry) or a visible failure; never a duplicate or partial file. |
| D13 | SDK host built with `DeviceLinkOptions(autoCopy = false)`. Send `T1` to it. | Clipboard unchanged; notification offers **Copy**. |

## 3. Sending from a device without the SDK

| ID | Steps | Pass |
| --- | --- | --- |
| S1a | Android: select text in any app → **Send to device** (text selection menu). | Delivered. |
| S2 | Android: any app → Share → DeviceLink (text, image, file). | Delivered. |
| S3 | Android: Quick Settings **DeviceLink** tile after copying something elsewhere. | The clipboard is sent (brief invisible activity, no app UI left behind). |
| S4 | Android: enable the **DeviceLink keyboard**, copy in any app while it is active. | Each copy is sent automatically; switching back to the previous keyboard works. |
| S5 | iPhone: Share sheet → DeviceLink (text, photo, file). | Delivered. |
| S6 | iPhone: a Shortcut *Get Clipboard → Send to DeviceLink*, bound to Back Tap (Settings → Accessibility → Touch → Back Tap). | Delivered without opening the app. |
| S7 | iPhone: Back Tap bound to **Get DeviceLink clip** with the app closed. | Newest waiting item lands on the clipboard. |
| S8 | Mac: copy text/image in any app with DeviceLinkMac running. | Delivered; copying from a password manager is **not** sent. |

## 4. iPhone specifics

| ID | Steps | Pass |
| --- | --- | --- |
| I1a | App open; send `T1` from Android. | Clipboard set immediately; history row added. |
| I2a | Background the app, send within 20 s. | Applied during the grace period. |
| I3 | App closed, APNs configured: send `T2`. | Notification shows "From <device>: <preview>" (decrypted on device); **Copy** action applies it without opening the app. Record whether the background pasteboard write actually happened (paste elsewhere). |
| I4 | App closed, APNs **not** configured. | Generic or no notification; item applied when the app is opened within 5 min. |

## 5. Robustness and privacy

| ID | Steps | Pass |
| --- | --- | --- |
| R1 | Restart the relay during an idle receiver period. | Receivers reconnect within ~1 min with no user action. |
| R2 | Admin: block A, then send from B. | Refused; unblocking restores delivery. |
| R3 | Unlink on B. | A shows the peer removed; sends between them stop. Admin: link gone. |
| R4 | **Reset device** on B (Info → Reset). | New identity; all peers unlinked; a new scan is needed for B only. |
| R5 | Inspect an item in admin Sandboxes. | Only sizes, timings, fingerprints; no content. |
| R6 | `adb logcat -d | grep -i devicelink` after a session. | No clipboard text, file names, URIs, keys or peer names. |
| R7 | Copy the **diagnostics report** (Info). | Contains versions, states and timestamps only. |
| R8 | Font size largest, RTL language, dark mode on both platforms. | Screens usable; no clipped controls. |

## Battery

Android, per scenario, **phone on battery** (USB disconnected), screen off, same network, ≥ 60 min,
three runs each. Scenarios: `baseline` (app installed, receiver off), `receiver-idle` (receiver on, no
traffic), `receiver-traffic` (one `T1` every 5 minutes from the peer).

```sh
scripts/android_battery.sh start <serial>
# disconnect USB, lock the phone, run the scenario for 60+ minutes, reconnect
scripts/android_battery.sh collect <serial> dev.devicelink.receiver receiver-idle
```

Report per run: duration, start/end level, estimated mAh for the app UID, wakelock time, network
bytes, and deviceidle state. Pass target: `receiver-idle` costs **≤ 2 % battery per 8 h** above
`baseline` on a modern phone. If it does not, record the numbers anyway; tuning options are the
long-poll wait (up to 50 s) and backoff, not reducing delivery guarantees.

iPhone: Settings → Battery → app list over 24 h with normal use, receiver used as intended (no
background polling exists; only APNs wake-ups). Report the DeviceLink percentage.

## Results template

```text
Date / build / relay version:
Devices (model, OS, network):
P1 pass | P2 pass (code matched) | …
Notes (latencies from admin, anomalies, screenshots in artifacts/device-tests/<date>/):
```
