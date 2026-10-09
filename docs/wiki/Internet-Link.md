# Internet Link

Internet Link extends DeviceLink to two trusted phones on different networks. It
uses a self-hosted, end-to-end encrypted mailbox and a temporary foreground
session. Nearby sharing remains independent and works without the relay.

## Set up once

1. Deploy the relay behind valid HTTPS using the [server guide](https://git.oryxlabs.internal/ivan-antsimonau/device-link/blob/main/server/README.md).
2. On both phones, open Internet Link and save the same server URL. Add the
   enrollment token if the deployment requires it. URLs must use HTTPS in release
   builds and cannot include a path, query, fragment or embedded credentials.
3. Start nearby sessions and connect the phones. Compare the pairing codes if
   this is their first connection. With relay settings saved, the verified nearby
   channel exchanges signed encryption-key bundles automatically.
4. Check the trusted peer list in Internet Link and select the intended phone.
5. Start Internet Link on each phone when remote delivery is wanted. Each session
   lasts 15 minutes and has its own notification and Stop action.

A server URL alone does not pair two phones. A display name, enrollment token or
server-supplied public key is not accepted as peer identity. Keys are bound to
locally verified nearby identities. If an encryption key changes, explicitly
forget and re-establish trust; it is not replaced silently. Both phones must allow
each other for bidirectional messages and encrypted receipts.
After forgetting an internet peer, let an active internet session deliver its
queued server revocation before reconnecting nearby to exchange keys again.
Re-enrollment for that relay is blocked while the revocation is still pending.

## Send, receive and copy

While a nearby connection is authenticated, shared content and the sample's
linked Copy use that direct connection. Otherwise an enabled Internet Link sends
to its selected peer. Starting Internet Link does not synchronize sample history
or turn chat Send into a network action.

| Content | Sender action | Receiving behavior | Lifetime |
| --- | --- | --- | --- |
| Ordinary text/link | Share or Send clipboard | Tray item; manual Copy | Up to 24 hours |
| Ordinary file | Share or Choose files | Explicit Receive/Reject offer | Up to 24 hours |
| Clipboard text | Copy displayed sample message/selection | Automatic only when receiver opted in; otherwise manual Copy | 60 seconds |
| Clipboard image | Explicit image Copy | Real content URI; automatic only when opted in | 60 seconds |
| Delivery result | Automatic encrypted receipt | Updates sender outcome | 60 seconds |

**Allow clipboard updates** is off by default. Enabling it permits fresh explicit
copy actions from trusted peers to replace the local clipboard while Internet
Link is active. It does not grant background clipboard reading. Incoming content
can still be copied manually with automatic updates off. A copy made with both
transports off stays local and is never uploaded on a later reconnect.

Relay text is limited to 8,192 UTF-8 bytes. Files/images are limited to 8 MiB of
content and uploaded as complete encrypted envelopes. There is no chunk-level
resume. Image paste requires a compatible receiving app; a content URI is not
converted into pasted text. Open/Save/Share remains available for general files.

## Delivery is separate from upload

An accepted upload is durably stored on the relay. The sender can disconnect;
the receiver can fetch it in a later session before expiry. An upload response
does not prove receipt or clipboard application. The receiving client sends an
encrypted result only after processing or after its clipboard write callback.
If that result is lost, the sender may time out even though processing succeeded.

Ordinary incoming text persists privately and expired records are filtered when
history loads or new text is saved. Its envelope lasts at most 24 hours; this is
not a background timed-erasure guarantee. Clipboard text remains ephemeral. Accepted files use the common durable
private-file index. File offers awaiting a decision persist as encrypted local
envelopes; polling excludes those IDs without acknowledging them, and offers can
be restored after restart while still valid. Network partials and unsent outgoing
operations are not a durable retry queue.

Every payload has an ID deduplication record. Clipboard sequence state is persisted
per sender, so older or replayed clipboard actions cannot replace a newer accepted
action. Ordinary files use ID deduplication rather than a clipboard sequence high
watermark, allowing an older offered file to be accepted later. Stop and expiry
invalidate pending automatic writes. DeviceLink cannot detect a background copy
made in an unrelated application.

## Server and privacy

The relay sees device public keys/fingerprints, sender/recipient routing, sizes,
sequences and timestamps. It cannot decrypt payload kind, text, filenames, MIME,
file bytes or receipts. Tink HPKE encrypts complete payloads, while Android
Keystore P-256 signatures authenticate keys, envelopes and HTTP requests. See
[[Security]] for the persisted-key forward-secrecy limitation.

Defaults: 20 pending envelopes per recipient, 64 MiB per mailbox, 256 MiB globally,
24 MiB maximum request body and 16 MiB decoded ciphertext. Persistent nonces reject
HTTP replay. Recipient-owned allowlists reject unsolicited senders. Exact-body
retries are idempotent until expiry, including after acknowledgement. Removal of
a peer rejects future local processing immediately. Server revocation/deletion is
queued durably per relay URL and retried on the next active poll or session start.
Grant/delete writes are serialized so an older grant cannot overtake revocation.
The server must become reachable for remote cleanup; Off does not run a retry job.

The service runs one worker per SQLite database, binds loopback by default, and
ships Docker/systemd/nginx templates. Release Android clients use platform TLS
validation. A debug-only `http://127.0.0.1` URL supports ADB reverse to a local test
server; it is not a production HTTP exception.

## Availability and limits

A 25-second long poll sleeps on a server condition and returns on available data;
Android requests one envelope at a time and backs off after failures. There is no
FCM/vendor push, scheduled background fetch, boot restart or permanent connection
while off. At the 15-minute deadline, no new operations start. Active requests and
their processing/receipt callbacks get up to 60 seconds to finish before teardown;
explicit Stop still cancels immediately. Payload expiry is never extended by this
grace period. Accepted relay data remains until acknowledgement or expiry. See [[Battery]].

A public server/certificate, operational monitoring, recovery, key rotation,
ratcheting forward secrecy, chunk resume and measured idle battery targets are
separate work. Automated Android/server checks and Docker health have passed.

## Physical validation boundary

KATIM X3M (Android 15) and Samsung S24 (Android 16) used the local debug relay
through USB ADB reverse mappings. Both directions delivered image clipboard data
while the receiver was on Home; actual image Paste displayed it. KATIM-to-Samsung
text paste matched the synthetic source. The manual KATIM instrumentation confirmed
verified key exchange, nearby off, clipboard result acknowledgements and ordinary
file completion only after Samsung Receive. Both received image files matched
the source SHA-256. Sending ordinary text with the receiver's session off, stopping
the sender, then starting the receiver demonstrated mailbox delivery without the
sender remaining online. Local chat Send created no relay message. Receiver opt-out
was also verified: Samsung received a new text item while automatic copying was
off, and actual Paste still produced its prior image clipboard content.

These tests validate local-relay application behavior, not public HTTPS/cellular,
independent-network reachability, sustained Doze or battery cost. See the repository
validation record for evidence and pending scenarios.
