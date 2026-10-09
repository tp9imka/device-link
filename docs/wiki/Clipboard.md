# Clipboard integration and local chat

DeviceLink offers explicit linked Copy through an authenticated nearby connection
or an enabled Internet Link to a verified peer. It does not observe arbitrary
clipboard changes. Both phones run DeviceLink; automatic receiving writes require
an active session. Internet clipboard application additionally requires the
receiver's opt-in setting, which is off by default.

## What each action does

| Action | This phone | Other phone |
| --- | --- | --- |
| Chat sample Send | Persists a local message | Nothing is transmitted |
| Message Copy button | Copies message text | Receives Clip if currently connected |
| Select message text > Copy | Copies the selected text | Receives Clip if currently connected |
| Copy with both transports off | Copies locally | No queue and no later replay |
| Copy in the editable composer/paste field | Normal local clipboard action | Nothing is transmitted |
| Existing Share or Send clipboard text action | Ordinary Text transfer | Tray item; manual Copy still required |
| Copy image message | Copies a validated image URI/data | Nearby image transfer or encrypted internet image |
| Paste image into local sample | Shows a local preview | Nothing is transmitted |

The local chat sample keeps the last 50 messages in app-private storage, each at
most 8,192 UTF-8 bytes. History survives an app restart but never synchronizes.
Incoming clipboard delivery does not insert a chat message; paste and Send are
separate user actions. Message selection integration is scoped to the rendered
message content, not the editable field or other applications.
Image history uses private files and survives restart. Before importing new image
bytes, cleanup removes unreferenced image/staging files older than 24 hours. Images
referenced by current chat history, the latest image DeviceLink wrote to the
clipboard, and the sample fixture are protected. Recent files are retained for
in-flight reuse. New imports are refused once stored images reach 512 MiB after
cleanup; this pre-copy admission threshold is not a strict transactional disk cap.
Protected/recent images can fill it. This is import-triggered cleanup, not periodic secure erasure, and it
never reads the system clipboard to decide what to retain.

## Nearby delivery and acknowledgement

`LinkController.sendClip(text)` is a suspending, non-queuing API. Its result is
`SENT`, `NOT_CONNECTED`, `EXPIRED`, `INVALID` or `FAILED`. `SENT` means the encrypted
command was dispatched; it does not claim that the remote clipboard was written.
Clip text is bounded to 8,192 UTF-8 bytes and the encoded control frame must also
fit the 32,768-byte wire limit.

The receiver emits a one-shot `IncomingClip(id, text, sessionToken)` through the
controller's event flow. The application's Main-thread collector calls
`isClipCurrent(event)` immediately before writing the clipboard, then reports
the actual attempt with `clipboardApplied(event, success)`. The check and OS write
must be synchronous with respect to other Main-thread session actions: a delayed
or asynchronous consumer must recheck just before writing.

An expired, cancelled, disconnected or replaced session cannot authorize a stale
write. Successful OS application produces `ClipResult(id, copied=true)`; a failed
attempt produces `copied=false`. The receiver callback has 10 seconds; the sender
allows 15 seconds for the result. Pending application events are bounded to 20.
An absent result is not treated as clipboard success.

Clipboard transfers use `TransferMode.CLIPBOARD` and separately track `PENDING`,
`COPIED` or `NOT_COPIED`. An ordinary transport receipt cannot change a clipboard
operation into a copied result. Files and ordinary Text keep their existing paths.

## Image data and ordering

Image Copy carries actual content rather than a URI rendered as text. PNG, JPEG,
WebP and GIF imports are byte-bounded, checked against decoder MIME/dimensions and
sample-decoded. Images over 40 million pixels are rejected. Nearby clipboard
images may contain up to 16 MiB; internet payloads allow 8 MiB.

A nearby `ClipImageOffer` uses the accepted file-byte path, but marks a distinct
clipboard operation. It is automatically accepted only within the authenticated
active session and pending-image bounds. After durable receive, the app imports
a stable private image URI, rechecks the session and writes MIME-tagged ClipData.
`ClipResult` reflects that OS write, not merely receiving the file. The receiving
application must support pasting image URIs; general file Open/Save/Share remains
the fallback. Importing an image never installs a clipboard-change listener.

Text and image events share nearby clipboard-action ordering. A newer accepted
copy supersedes an older image still in flight: the old image can remain a saved
file but cannot overwrite the newer clipboard. The application also checks its
own clipboard revision after asynchronous image import. This tracks explicit
DeviceLink copy actions; it cannot detect background copies made by unrelated
apps. Stop/reconnect/expiry checks still apply just before writing.

## Internet delivery

If no authenticated nearby connection is available, an enabled Internet Link
routes linked Copy to its selected trusted peer. The receiver's **Allow clipboard
updates** setting starts off. With automatic application disabled, incoming
content stays in the tray for manual Copy. Clipboard text/image and encrypted
receipts expire after 60 seconds; late commands are not applied. Persistent
per-sender sequence tracking rejects replayed/older clipboard envelopes, while
ordinary file acceptance uses separate ID deduplication.

Accepted uploads may wait on the relay until expiry, but a copy made while both
transports are off is never queued for reconnection. Upload success is not
clipboard success. The server cannot read payload kind, clipboard text or image
bytes. Clipboard text is ephemeral locally; ordinary relay text persists privately
with a 24-hour envelope lifetime and expiry filtering on history load/save. This
does not promise timed secure erasure of stored bytes. See [[Internet-Link]].

## Compatibility and boundaries

The wire distinguishes `clip` and `clip_result` from `text`; existing Text bytes
are unchanged. A peer predating these commands rejects an unknown Clip message,
so install the clipboard-capable version on both phones before using linked Copy.

The receiving clipboard can be replaced by an explicit Clip from its current
trusted peer. This does not grant background reading access to either phone's
clipboard. No polling listener, keyboard replacement, root or special OS build
is required. Background clipboard writes and linked Copy/paste were verified on KATIM X3M
and Samsung S24; see the repository validation record for remaining scenarios.
A tray label alone is not sufficient test evidence.

Internet Link and image clipboard have passing automated checks and local-relay
phone validation. Actual image paste worked on KATIM X3M and Samsung S24 in both
directions after delivery with the receiver backgrounded. A focused, unprivileged
external probe on Samsung decoded the MIME-tagged 640 × 400 PNG clipboard URI
without a text URI payload. It was removed afterward. The native Wi-Fi Direct
image path was also verified: Samsung Copy image delivered to backgrounded KATIM,
and actual Paste in KATIM's sample produced an image attachment. Internet checks
used debug HTTP loopback via USB tunnels; they do not establish public TLS/cellular
or sustained Doze behavior.

Receiver internet opt-out was verified separately: with Samsung automatic copying
off, a new incoming text item offered manual Copy while actual Paste still yielded
the previously copied image. This checks clipboard preservation, not merely a
settings toggle or tray label.
See [[Architecture]], [[Security]] and [[Workflow]] for the existing system.
