# 0002 - Explicit clipboard commands and a local chat sample

Status: accepted for the requested integration sample.

## Decision

Keep ordinary Text delivery unchanged and introduce distinct Clip/ClipResult wire
messages. An explicit Copy in the sample's rendered message content copies locally
and attempts an immediate authenticated clipboard command. Disconnected copying
never queues. Chat Send stores only a local message; history is app-private,
limited to the last 50 messages and 8,192 UTF-8 bytes per message, and not synchronized.

The application owns OS clipboard access. Transport emits a one-shot session-bound
event; a Main-thread collector checks current session/expiry immediately before
writing, then reports the actual outcome. Clipboard success in the tray and peer
acknowledgement follows that callback, never merely receipt of bytes. Receiver
callback and sender result deadlines are 10 and 15 seconds respectively.

## Consequences

- This is explicit integration inside an ordinary Android app, with no background
  clipboard capture or system-wide automatic synchronization.
- The authenticated peer can replace the receiving clipboard through a current
  Clip command; ordinary Text remains tray-only until manually copied.
- Stop, cancellation and reconnect invalidate pending events. No stale clipboard
  command is replayed into a later session.
- The existing Text wire encoding is protected by a golden compatibility test.
  Older peers reject the new unknown Clip command; both phones must be updated.
- The sample introduces no chat transport or server. A possible future encrypted
  relay is a separate design and deployment decision.

## Validation boundary

Protocol and delivery-registry tests protect payload bounds, type separation,
one-shot consumption and session invalidation. OS clipboard behavior, selection
Copy, acknowledgement labels and local history require device verification.
See [the validation record](../validation.md) for executed evidence.
