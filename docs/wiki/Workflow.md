# Workflow

## First connection

Install DeviceLink on both phones. Enable Wi-Fi, open the app, and start a session
on both. Grant Android's nearby-device permission; Android 12 and earlier use
Location permission for discovery. Some OEMs also require Location enabled.

Select the other phone in the nearby list. Android may show a Wi-Fi Direct
invitation on the receiving phone. Compare the six-digit DeviceLink code on both
screens and approve on both. A mismatch means reject and start again.

Alternatively, start sessions and use Pair with QR: display one phone's QR and
scan it with the other. Camera permission is requested by the bundled offline
scanner. QR selects/pins the peer; the initial verification flow still applies.
If an OEM hides the local discovery address, select the phone from the list after
scanning. No Google services or external QR app are required.

## Text and links

From another app select Share > DeviceLink, or copy text, open DeviceLink and tap
Send clipboard. Android permits clipboard access while DeviceLink is foreground.
The receiving phone shows the text in its tray. Tap Copy there and paste into the
app of your choice. Receiving never silently replaces an existing local clipboard.

## Photos and files

Share one or several items from Gallery/Files/another app, or use Choose files.
Before connecting, shared items are queued visibly; they send after authentication.
The receiver explicitly accepts each file offer. Progress appears on both phones.
Completed files offer Open, Save and Share. A returned edit is simply a new file
sent in the opposite direction; originals are not overwritten.

## Reconnect and turn off

Paired identities persist. Start sessions and select the peer again; verified
remembered identities do not need another code comparison. Session expiry stops
new transfers, lets in-progress work finish, then disconnects. Turn off cancels
immediately. A phone that is off is not silently listening for a remote wake-up.

## Recovery

- Nothing appears: check both sessions, Wi-Fi, Nearby permission and OEM Location
  requirements. Restart discovery after its timeout.
- Android asks to stop hotspot: Wi-Fi Direct and hotspot conflict on that phone.
  Stop hotspot only if disconnecting its clients is acceptable.
- Connection fails: accept the system invitation, bring devices closer and retry.
- File rejected/unavailable: use a fresh Share action; the source app may have
  withdrawn its read grant, or the size may be unknown/over the limit.
- Interrupted transfer: select the source again. Partial files are discarded.
- Lost trust/key changed: forget the pairing and compare a fresh code.
- No app can open a file: use Save or Share to choose a suitable destination.
