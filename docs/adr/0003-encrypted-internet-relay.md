# 0003 - Optional encrypted Internet Link

Status: accepted and implemented for the requested internet transport extension.

## Problem

Wi-Fi Direct requires physical proximity and simultaneous radio availability.
Users also need to leave an encrypted item for an already trusted phone on another
network, without depending on Google Play services. The existing live-session
cipher cannot be turned into an asynchronous mailbox by retaining its session keys.

## Decision

Keep nearby transport independent. Add `InternetLinkController`, a separate
15-minute foreground session, and a self-hosted Python FastAPI/SQLite relay. While
enabled, the client performs signed HTTPS requests and waits up to 25 seconds per
mailbox poll. It requests one envelope at a time to bound Android memory, excludes
offered files awaiting consent, and backs off after errors. Off cancels requests
and polling. There is no FCM/vendor push or process-death session restart.

Tink 1.23.0 HPKE uses X25519/HKDF-SHA256/AES-256-GCM for complete encrypted
payloads. The HPKE keyset is wrapped using Android Keystore through
`AndroidKeysetManager`; initialization fails if Keystore protection is unavailable.
The existing Android Keystore P-256 identity signs both encryption-key bundles and
envelopes. Bundles are exchanged over the authenticated nearby `RelayKeys` channel
after both phones save relay configuration, verified against that connected peer's
identity, and pinned locally. A replacement encryption key is not silently accepted.

Envelope version, ID, sender, recipient, creation/expiry and sequence are bound into
both HPKE context and the identity signature. Payload kind, text, filename, MIME,
file bytes and receipts are encrypted. The relay sees routing, sizes and timing,
but cannot decrypt content. HTTPS protects transport metadata in transit to the
relay; it does not hide that metadata from the operator.

Every HTTP request is independently signed, includes a fresh UUID nonce and a
timestamp, and hashes the exact body. The server persists nonce replay protection,
recipient-owned directional allowlists, mailbox data and idempotency records in
SQLite transactions. Exact accepted-envelope retries cannot re-deliver an
acknowledged item before expiry. The optional enrollment token controls enrollment;
it is not the content-encryption key or proof of peer trust.

Clipboard payloads and receipts expire after 60 seconds; ordinary text/files
after at most 24 hours. Relay content is limited to 8 MiB and sent as a complete
encrypted envelope, with no chunk resume. Receiver durable ID deduplication applies
to every kind; persisted per-sender sequence tracking additionally rejects stale
clipboard actions without blocking older ordinary files. Incoming automatic
clipboard application is an explicit setting, off by default. Local sample chat
Send never invokes this transport.

The 15-minute session deadline blocks new work. Active operations and their
clipboard/receipt callbacks have a bounded 60-second finishing period; this does
not extend payload expiry. Explicit Stop closes the session immediately.

## Consequences

Server acceptance means durable upload, not receipt or clipboard application.
Encrypted return receipts represent the receiving outcome; a lost receipt can
leave an uncertain sender result even when local processing succeeded. A sender
does not need to remain online after accepted upload, but the receiver must start
a session before expiry. Ordinary file offers persist encrypted locally and remain
unacknowledged until acceptance/rejection. Partial upload/download bytes are not
resumed. A failed outgoing send is not a durable automatic retry queue.

The persisted HPKE recipient key means this design does **not** provide forward
secrecy against later compromise of that key: retained ciphertext may become
readable. There is no ratchet, key-recovery service or automatic key rotation.
Android backups are disabled. The implementation has focused cryptographic and
protocol tests, not an independent security audit.

Release clients require HTTPS with normal platform certificate validation. Only
debug builds allow HTTP to exactly `127.0.0.1` for an ADB-reversed local relay;
there is no trust-all certificate mode. Docker, systemd and nginx templates ship
with the server, which binds loopback by default and supports one worker per
SQLite database. Public TLS provisioning and operations remain deployment work.

## Verification

Model tests cover signed bundle/envelope tampering, recipient binding, expiry,
replay and payload bounds. Transfer tests cover URL policy and lifecycle helpers.
The server's 32 contract tests cover authentication, nonce durability, consent,
acknowledgement/restart, expiry, quotas, concurrency and long polling. Local Python
startup and Docker build/container health passed. Physical internet/image tests
are recorded separately in `docs/validation.md`; automated success is not proof
of end-to-end mobile delivery or battery cost.
