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
app of your choice. Ordinary text transfers never automatically replace an existing local clipboard.

## Local chat and linked Copy

Open the chat sample. Type or paste a message and press Send to add it to the
local conversation. Up to 50 messages persist on this phone; Send does not
transmit a message, change a clipboard or synchronize history.

With the phones connected, tap Copy on a message, or select part of a message
and choose Copy. The chosen text is copied locally and sent as a clipboard
command. The peer writes its clipboard only while that authenticated session
and event remain current. A clipboard result appears in the transfer tray after
the write attempt. Paste into the other phone's composer or another app to use it.

When neither transport is available, Copy remains local. It is never saved for
upload on a later reconnect. An Internet Link upload already accepted by the relay
may wait for the receiver within the separate 60-second clipboard lifetime.
Copy inside the editable composer/paste field is also local; the integration
is scoped to the sample's displayed message content. Both phones need the new
clipboard-capable build. See [[Clipboard]] for acknowledgement and timeout rules.

## Photos and files

Share one or several items from Gallery/Files/another app, or use Choose files.
Before connecting, shared items are queued visibly; they send after authentication.
The receiver explicitly accepts each file offer. Progress appears on both phones.
Completed files offer Open, Save and Share. A returned edit is simply a new file
sent in the opposite direction; originals are not overwritten.

For clipboard images, Copy an image message in the local sample. The clipboard
contains a scoped image URI with real image data, not URI text. Use image Paste in
the sample to see a preview, then Send to add it to local history. PNG, JPEG, WebP
and GIF are supported; nearby clipboard images are limited to 16 MiB. The selected
receiving app must support image paste. General files still use Receive/Open/Save/Share.

## On different networks

Open Internet Link and save the same deployed HTTPS relay URL on both phones,
plus its enrollment token if required. Connect the phones nearby after saving;
their verified channel exchanges pinned encryption keys. Select the trusted phone
in Internet Link, then start a 15-minute internet session on each phone.

With no authenticated nearby connection, an enabled Internet Link carries Share,
Send clipboard and linked sample Copy to that selected peer. Chat Send still only
saves local history. Ordinary internet files require Receive/Reject and contain
at most 8 MiB. **Allow clipboard updates** is off by default: enable it on the
receiver only when fresh incoming Copy actions should write automatically.
Otherwise use the received item's Copy action.

An uploaded file/text can wait in the encrypted mailbox for up to 24 hours;
clipboard actions expire after 60 seconds. The receiving phone must start an
internet session before expiry. Upload accepted does not mean received/copied;
the sender waits for an encrypted result. No push service wakes an off phone.
See [[Internet-Link]] for setup, persistence and recovery details.

## Reconnect and turn off

Paired identities persist. Start sessions and select the peer again; verified
remembered identities do not need another code comparison. Session expiry stops
new transfers, lets in-progress work finish, then disconnects. Turn off cancels
immediately. A phone that is off is not silently listening for a remote wake-up.
Internet Link has a separate Stop action and notification. Its deadline blocks new
work and gives active operations up to 60 seconds to finish. Explicit Stop closes
requests immediately; already accepted envelopes remain on the relay until
acknowledged or expired. Stopping nearby does not stop a separately enabled
internet session, and vice versa.

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
- Clipboard not copied: confirm the link is still active, then use the explicit
  Copy action again. Reconnecting never replays an earlier clipboard command.
- Internet peer list empty: save relay settings on both phones, connect nearby
  and complete initial verification so encryption keys can be exchanged.
- Internet configuration error: check the HTTPS URL, enrollment token and phone
  clocks. Release builds do not accept plaintext HTTP or invalid certificates.
- Internet waiting/retrying: verify server reachability, active receiver session
  and mailbox capacity. A failed send needs an explicit retry; late clipboard
  events are discarded, not applied after their freshness window.
