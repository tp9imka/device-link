# Optional internet relay — implementation record

Status: implemented as Internet Link. This page supersedes the original proposal;
setup and behavior are documented in [Internet Link](wiki/Internet-Link.md), the
[server guide](../server/README.md) and [ADR 0003](adr/0003-encrypted-internet-relay.md).

## Decisions implemented

- Optional self-hosted FastAPI/SQLite encrypted mailbox; nearby sharing remains
  server-independent and neither transport requires Google Play services.
- Tink 1.23.0 HPKE (X25519/HKDF-SHA256/AES-256-GCM), identity-signed key bundles
  exchanged over the verified nearby channel, pinned locally. Keystore protects
  the signing key and wraps the persisted HPKE keyset.
- Complete encrypted text/file/image/receipt payloads. Identity signatures bind
  ciphertext and envelope routing, IDs, times and sequence. The relay sees public
  routing metadata but not payload kind, text or file metadata/content.
- Signed HTTPS requests with timestamp and durable nonce replay protection,
  recipient-owned directional allowlists, optional enrollment token, byte/count
  quotas, acknowledgement deletion and expiry cleanup.
- Explicit 15-minute foreground Internet Link sessions, 25-second long polling,
  bounded client responses and retries with backoff. Off cancels network work.
- Receiver automatic clipboard application is opt-in and off by default. Fresh
  clipboard actions and encrypted receipts expire after 60 seconds. Ordinary
  text/files expire after at most 24 hours; files/images contain at most 8 MiB.
- Durable local ID deduplication and per-sender clipboard sequence state. Pending
  file offers remain encrypted locally and can be restored before expiry.
- Ordinary relay text persists privately with its 24-hour envelope expiry and
  load/save filtering; no timed secure-erasure promise. Clipboard text is
  ephemeral. Sample chat Send/history remains local-only.

## Changes from the proposal

There is no membership or device-recovery service; first trust uses the existing
verified nearby pairing. There is no push adapter or periodic off-session fetch.
Remote availability requires an active foreground session; a server cannot wake
an off phone. Files use bounded complete envelopes, not resumable blob uploads.
Release URLs require HTTPS, with a debug-only loopback HTTP path for ADB tests.

A persisted HPKE key is suitable for asynchronous receipt, but does not provide
forward secrecy against later compromise of that key. The implementation does
not claim a ratchet or independently audited protocol. The app cannot inspect
other applications' background clipboard changes; explicit integrated Copy and
foreground Share remain the supported send triggers.

## Remaining work

- Deployment owner, public URL/certificate, operations and retention monitoring.
- Public HTTPS/cellular and independent-network validation, then matched
  screen-off/idle/transfer battery and delivery-latency measurements.
- FCM or a suitable vendor push adapter where available, with explicit lifecycle
  and consent design; no instant invisible-delivery promise.
- Key rotation/recovery, forward-secrecy improvements and independent security review.
- Chunk-level transfer resume and larger-file strategy. Image imports now prune
  unreferenced assets older than 24 hours while preserving current chat/clipboard
  references, the fixture and recent assets. New imports are refused once stored
  images reach the 512 MiB pre-copy admission threshold after cleanup.

Android checks and 32 relay contract tests pass; local Python startup and Docker
build/container health are verified. Two-phone text/image clipboard delivery,
explicit file acceptance and sender-offline mailbox delivery also passed against
the local debug relay through USB tunnels. These are not public HTTPS/cellular,
sustained Doze or battery measurements. The [validation record](validation.md)
tracks the exact evidence and remaining checks.
