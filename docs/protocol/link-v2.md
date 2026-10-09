# DeviceLink Link protocol v2

Status: implemented by `sdk/core` (Kotlin/JVM + Android), `ios/DeviceLinkKit`
(Swift) and `server/relay` (Python). Cross-language vectors live in
`docs/protocol/vectors/`. This document is the contract; when an implementation
and this page disagree, the implementation is wrong.

Link v2 connects devices of any platform through the relay ("sandbox") with a
one-time QR scan. The relay stores only end-to-end encrypted, short-lived
envelopes and public routing metadata. It never sees clipboard text, file names,
MIME types, payload kinds or device display names.

## Primitives

| Purpose | Algorithm | Why |
| --- | --- | --- |
| Device identity | ECDSA P-256 / SHA-256, DER signatures | Android Keystore and iOS Secure Enclave both hold P-256 signing keys natively |
| Device ID | lowercase hex SHA-256 of the identity SubjectPublicKeyInfo DER | Stable, server-verifiable, no registry needed |
| Content encryption | HPKE RFC 9180 base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305 | CryptoKit `Curve25519_SHA256_ChachaPoly` suite (iOS 17+), Tink on Android/JVM, `cryptography` in Python |
| Pairing secrets | HKDF-SHA256 + AES-256-GCM | Available on Android API 26, iOS, JVM and Python |

Byte encodings: Base64 is RFC 4648 standard alphabet **with** padding, unless a
field says `b64url` (URL-safe alphabet, no padding). Integers in canonical byte
strings are big-endian. `field(x)` means a 4-byte big-endian length followed by
the bytes of `x`; strings are UTF-8.

HPKE ciphertext layout is `enc (32 bytes) || AEAD ciphertext || tag`, sealed in a
single shot with sequence 0, the canonical envelope context as HPKE `info`, and an
empty AAD.

## Key bundle

Every device publishes one signed key bundle to its peers (never to the relay):

```json
{
  "v": 2,
  "identityKey": "<Base64 SPKI DER, P-256>",
  "encryptionKey": "<Base64 raw 32-byte X25519 public key>",
  "name": "Pixel 9",
  "platform": "android",
  "signature": "<Base64 DER ECDSA over bundleSignedBytes>"
}
```

```
bundleSignedBytes = field("DeviceLink/key-bundle/v2") || field(identityKey DER)
                 || field(encryptionKey raw) || field(name) || field(platform)
```

`name` is 1–48 UTF-16 code units, not blank, without control (Cc) or format (Cf) characters. `platform`
matches `[a-z]{1,16}` (`android`, `ios`, `desktop`, …). Bundle `id` is the device
ID derived from `identityKey`. Receivers verify the signature before use.

## Envelope (public, seen by the relay)

```json
{
  "version": 2,
  "id": "<canonical lowercase UUID>",
  "senderId": "<64 hex>", "recipientId": "<64 hex>",
  "createdAt": 1800000000000, "expiresAt": 1800000300000,
  "sequence": 7,
  "ciphertext": "<Base64 HPKE output>",
  "signature": "<Base64 DER ECDSA by sender identity>"
}
```

```
context     = field("DeviceLink/envelope-context/v2") || u32(version) || field(id)
           || field(senderId) || field(recipientId) || i64(createdAt) || i64(expiresAt) || i64(sequence)
signedBytes = field("DeviceLink/envelope/v2") || field(context) || field(ciphertext raw bytes)
```

Rules: `sequence` is a positive per-sender counter persisted by the sender.
`0 < expiresAt - createdAt <= 3,600,000`. The relay additionally enforces its own
configured maximum lifetime (default 10 minutes). Receivers reject envelopes whose
`expiresAt` has passed or whose `createdAt` is more than 60 s in the future, check
the signature against the **pinned** sender bundle, then open the HPKE ciphertext
with `info = context`.

## Payload (encrypted)

```json
{ "kind": "text", "text": "hello", "sentAt": 1800000000000 }
{ "kind": "image", "name": "clip.png", "mime": "image/png", "data": "<Base64>", "sentAt": … }
{ "kind": "file",  "name": "report.pdf", "mime": "application/pdf", "data": "<Base64>", "sentAt": … }
{ "kind": "receipt", "receiptFor": "<envelope id>", "status": "copied" }
{ "kind": "unlink" }
```

- `text`: 1 byte – 64 KiB UTF-8. Receivers place it on the clipboard.
- `image`: PNG, JPEG, WebP, GIF or HEIC, ≤ 10 MiB. Receivers place the image on the
  clipboard when the platform can, otherwise treat it like `file`.
- `file`: ≤ 10 MiB. Receivers notify and offer the platform share sheet.
- `receipt.status`: `copied` (written to the clipboard), `delivered` (stored /
  notified, clipboard not possible), `failed`.
- `unlink`: the sender removed this link; the receiver forgets the sender.

Optional fields (all encrypted, all ignorable by older decoders):

- `html` (text only, ≤ 256 KiB): rich-text alternative; `text` stays the plain fallback.
- `sensitive: true`: receivers hide previews in notifications and history, and expire the clip from
  the clipboard where the platform supports it (iOS: 2 minutes).
- Chunked files: content over 10 MiB is split into up to 10 chunks of ≤ 4 MiB (`MAX_FILE_BYTES` 40 MiB).
  Each chunk is its own envelope with the same `name`/`mime` plus `group` (UUID), `part` (0-based),
  `parts` (2–10) and `size` (total bytes). Receivers acknowledge each chunk, reassemble when all parts
  are present and the total equals `size`, and send one receipt for `group`. Senders retry the same
  sealed envelope on network errors and wait (up to 90 s) on `429 mailbox_full` while the receiver drains.

Decoders ignore unknown fields so future versions can add optional data.
`name` follows the bundle name character rules but may be 120 characters and
must not contain `/`, `\`, or be `.`/`..`.

Default lifetimes chosen by senders: 5 minutes for everything. This gives a
receiver that was offline (for example an iPhone without push) a comfortable
window to open the app and still get the item, while keeping the sandbox empty
most of the time. Items are deleted on acknowledgement or at expiry, whichever
comes first.

## Replay and ordering

Receivers keep a persisted ID ledger until each envelope's expiry and ignore
duplicates. For clipboard kinds they also keep the highest applied sequence per
sender; an older clip never overwrites a newer one (it is still listed in
history). Acknowledge (`DELETE /v1/messages/{id}`) only after local processing.

## Pairing by QR

The inviter (any linked or new device) creates a one-time 32-byte secret `s`
and shows a QR code. The secret never reaches the relay.

```
https://<relay-host>[:port]/pair#v2.<b64url(s)>.<b64url(inviter device ID bytes)>
devicelink://pair?relay=<url-encoded relay base URL>#v2.<b64url(s)>.<b64url(id)>
```

Both forms are accepted; the QR uses the `https` form so phone cameras open the
relay's tiny landing page (which forwards to the app without sending the fragment
to the server) when the app is not registered for the link.

Derived values (HKDF-SHA256, empty salt, `ikm = s`):

| Name | info | length |
| --- | --- | --- |
| `pairingId` | `DeviceLink/pairing-id/v2` | 16 bytes, sent as 32 lowercase hex |
| `joinKey` | `DeviceLink/pairing-join/v2` | 32 bytes |
| `confirmKey` | `DeviceLink/pairing-confirm/v2` | 32 bytes |

`seal(key, label, bundleJson) = nonce(12) || AES-256-GCM(key, nonce, bundleJson, aad = "DeviceLink/pairing/v2|" + pairingId + "|" + label)`

Flow:

1. Inviter `POST /v1/pairings {"id": pairingId, "expiresAt": now+5min}`.
2. Joiner scans, `POST /v1/register` with `X-Pairing-Id: pairingId` (open
   pairings admit the joiner without an enrollment token), then
   `POST /v1/pairings/{id}/join {"sealed": Base64(seal(joinKey, "join", joinerBundle))}`.
   A pairing accepts exactly one joiner.
3. Inviter long-polls `GET /v1/pairings/{id}?wait=25`, opens the joiner bundle,
   verifies its signature and that its ID equals the authenticated `joinerId`,
   pins it, allows it (`PUT /v1/peers/{joiner}`) and confirms:
   `POST /v1/pairings/{id}/confirm {"sealed": Base64(seal(confirmKey, "confirm", inviterBundle))}`.
4. Joiner long-polls the same pairing, opens the inviter bundle, checks that its
   ID equals the ID in the QR, pins it and allows it. The relay records the link
   for the admin dashboard and deletes the pairing.

Confirmation code (display only, first setup only): both devices show
`HKDF(s, "DeviceLink/pairing-code/v2|" + inviterId + "|" + joinerId, 4 bytes)` as a big-endian integer
mod 1,000,000, zero-padded to six digits, on the "Linked" confirmation. Matching codes show that the
two screens belong to the same pairing; it is never asked for again after linking.

Possession of the QR is the authorization: anyone who can read the code within
its 5-minute lifetime can link once. Inviters display the joiner's name after
linking and can unlink immediately.

## Relay HTTP API

Request signing is unchanged from v1 (`X-Device-Key`, `X-Device-Time`,
`X-Device-Nonce`, `X-Device-Signature` over
`DeviceLink relay request v1\nMETHOD\nPATH?QUERY\nTIME\nNONCE\nSHA256HEX(body)`).

| Endpoint | Purpose |
| --- | --- |
| `GET /v1/info` | Unauthenticated: relay version, maximum lifetime, enrollment mode, push providers |
| `POST /v1/register` | Body `{}` or `{"platform","model","appVersion"}` (optional, for the admin dashboard) |
| `POST /v1/pairings` | Create pairing (registered caller) |
| `POST /v1/pairings/{id}/join` | Joiner submits sealed bundle |
| `GET /v1/pairings/{id}?wait=0..25` | Creator/joiner poll: `{"state","joinerId","sealed"}` |
| `POST /v1/pairings/{id}/confirm` | Creator submits sealed bundle, link recorded |
| `DELETE /v1/pairings/{id}` | Cancel |
| `PUT/DELETE /v1/peers/{id}` | Directional allowlist (recipient-owned) |
| `POST /v1/messages` | Upload envelope (version 1 or 2) |
| `GET /v1/messages?wait=0..50&limit=1..20&exclude=…` | Long-poll mailbox |
| `DELETE /v1/messages/{id}` | Acknowledge |
| `PUT /v1/push` / `DELETE /v1/push` | Register / remove an APNs device token (`{"provider":"apns","token":"<hex>","environment":"sandbox"\|"production","topic":"<bundle id>"}`) |

When APNs is configured, the relay sends an alert push without content
(`"New item from a linked device"`, `mutable-content: 1`, `m: <envelope id>`) so
the iPhone can fetch and decrypt it. Android receivers keep a visible foreground
service with a long poll instead of using Google push.
