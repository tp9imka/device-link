# Clipboard integration and local chat

DeviceLink offers explicit linked Copy between two nearby, authenticated phones.
It does not observe arbitrary clipboard changes or provide a cloud messenger.
Both phones run DeviceLink and have an active session.

## What each action does

| Action | This phone | Other phone |
| --- | --- | --- |
| Chat sample Send | Persists a local message | Nothing is transmitted |
| Message Copy button | Copies message text | Receives Clip if currently connected |
| Select message text > Copy | Copies the selected text | Receives Clip if currently connected |
| Copy while disconnected | Copies locally | No queue and no later replay |
| Copy in the editable composer/paste field | Normal local clipboard action | Nothing is transmitted |
| Existing Share or Send clipboard text action | Ordinary Text transfer | Tray item; manual Copy still required |

The local chat sample keeps the last 50 messages in app-private storage, each at
most 8,192 UTF-8 bytes. History survives an app restart but never synchronizes.
Incoming clipboard delivery does not insert a chat message; paste and Send are
separate user actions. Message selection integration is scoped to the rendered
message content, not the editable field or other applications.

## Delivery and acknowledgement

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

There is no implemented server. A future optional end-to-end encrypted relay
could address remote reachability, but would need separate identity, delivery,
expiry, retention and deployment decisions. It is outside this nearby sample.
See [[Architecture]], [[Security]] and [[Workflow]] for the existing system.
