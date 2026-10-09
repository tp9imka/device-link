# DeviceLink relay

A small, single-process FastAPI relay — the "sandbox" — for opaque encrypted envelopes. It stores public device identities, directional recipient allowlists, replay nonces, one-time QR pairing rendezvous, delivery metadata and ciphertext in SQLite. Items live at most `max_ttl_ms` (default 10 minutes; clients use 5) and are deleted on acknowledgement or expiry. An operator dashboard is served at `/admin` (see [Hosting](../docs/wiki/Hosting.md)). Link v2 endpoints and pairing are specified in [docs/protocol/link-v2.md](../docs/protocol/link-v2.md). It cannot decrypt envelope contents. Payload type, clipboard text, filenames and private keys are never submitted in the public envelope. Signing and decrypting the end-to-end envelope remain client responsibilities.

## Run locally

Requires Python 3.14. From this directory:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m relay
```

The default address is `127.0.0.1:8000`; `/health` is unauthenticated. The server creates `data/relay.sqlite3`. Stop with Ctrl-C. Keep the entire data directory across restarts. The normal runner disables access logs and sets a private filesystem umask. No application logs contain request URLs, payloads, device keys or peer names.

Tests use independent temporary databases and real P-256 signatures:

```sh
.venv/bin/pip install -r requirements-test.txt
.venv/bin/python -m pytest -q
```

Tests protect authentication and canonical request binding, durable replay rejection, enrollment, recipient ownership and consent, expiry, retry/ack durability, malformed envelopes, quotas, rate limiting and long-poll behavior. There is no coverage-percentage target. `requirements.in` records chosen direct dependencies; `requirements.txt` pins their runtime dependency closure. Update pins deliberately, then rerun the contract suite. Upstream references: [FastAPI](https://pypi.org/project/fastapi/), [Uvicorn](https://www.uvicorn.org/), [PyCA cryptography](https://pypi.org/project/cryptography/).

## HTTPS deployment

Use a dedicated service user and HTTPS reverse proxy. `deploy/devicelink-relay.service` and `deploy/nginx.conf` are templates for a Linux host; replace hostname, TLS certificate paths and installation paths before enabling them. Provision a valid certificate for the hostname used by Android. Do not expose plaintext port 8000 to the public network. The proxy forwards the original request target unchanged, disables request logs, limits request size/connections/rate and allows the 25-second poll to finish.

Set an enrollment token on public deployments. Provision that token into allowed clients through their relay settings. Keep it outside git in a root-readable environment file, for example `/etc/devicelink-relay/environment`; rotate it to stop new enrollments with a compromised token. Rotation does not revoke already enrolled device identities.

Configuration comes from an optional TOML file named by `RELAY_CONFIG` (see `config.example.toml`) and `RELAY_<KEY>` environment variables, which win. Unknown keys are rejected. Main settings:

| Key / variable | Default | Meaning |
| --- | --- | --- |
| `RELAY_BIND`, `RELAY_PORT` (env only) | `127.0.0.1`, `8000` | HTTP listen address and port |
| `database` | `data/relay.sqlite3` | Durable database path |
| `enrollment_token` | unset | Token for registering without a pairing code (first device / builds with a built-in relay). Scanning a valid pairing QR enrolls without it. |
| `public_url` | derived from request | Public base URL used in admin setup QR codes |
| `max_ttl_ms` | `600000` | Sandbox lifetime cap (clients default to 5 minutes) |
| `max_poll_wait` | `50` | Longest mailbox long poll in seconds; keep below proxy idle timeouts |
| `admin_username`, `admin_password_hash` / `admin_password` | `admin`, unset | Dashboard credentials; dashboard disabled until a password is set. Hash with `python -m relay.passwd`. |
| `session_secret` | random per start | HMAC key for admin sessions; set it to keep sessions across restarts |
| `metrics_token` | unset | Bearer token for Prometheus `GET /metrics`; unset disables it (signed-in admins use `/admin/api/metrics`) |
| `apns_key_path`, `apns_key_id`, `apns_team_id` | unset | Optional APNs token auth for content-free iPhone wake-ups |
| `android_app_links`, `apple_app_ids` | unset | Optional `/.well-known` app-link verification files |
| `mailbox_bytes`, `global_bytes`, `mailbox_count`, `max_peers`, `max_devices` | 64 MiB, 256 MiB, 20, 100, 10,000 | Quotas |
| `event_retention_ms`, `max_events` | 7 days, 50,000 | Metadata-only activity log retention |

Docker build and loopback-only publication:

```sh
docker build -t devicelink-relay .
docker volume create devicelink-relay-data
docker run --rm --name devicelink-relay \
  -p 127.0.0.1:8000:8000 \
  -e RELAY_BIND=0.0.0.0 \
  --env-file /secure/path/relay.env \
  -v devicelink-relay-data:/data devicelink-relay
```

The container binds loopback unless `RELAY_BIND` is explicitly changed. The command above binds inside the container but publishes only host loopback for the host TLS proxy. The process runs as UID 10001; a bind-mounted data directory must be writable by that UID. The image and configuration are provided for deployment; local Python tests do not establish that Docker, systemd, a public certificate or Android access through the deployed proxy have been validated.

Run **one worker / one replica per database**. SQLite transactions protect consistency, while in-process condition notifications implement prompt long polling. Multiple workers would retain data correctly but cannot reliably wake another worker's poll. The normal runner bounds simultaneous connections to 32; this is a small deployment, not a horizontally scaled messaging service.

SQLite uses WAL and synchronous FULL. Use `python -m relay.backup DEST [--keep N]` (SQLite's online backup API, verified, pruned; see `deploy/README.md`) for live backups, or stop the server before copying the database and its sidecars; do not copy only the main file while running. Restrict permissions, encrypt the host volume/backups if metadata sensitivity requires it, and monitor disk availability. The byte quotas limit live envelope data, not the database/WAL filesystem high-water mark or backups. Leave disk headroom for WAL/checkpoints, indices, replay records and temporary HTTP buffers. Deleted pages are reused by SQLite; deleting a row is not forensic erasure.

## Signed HTTP contract

Every endpoint except `GET /health` requires exactly one of each header:

* `X-Device-Key`: standard padded Base64 DER SubjectPublicKeyInfo, ECDSA P-256 only.
* `X-Device-Time`: decimal Unix epoch milliseconds, within ±60 seconds of server time.
* `X-Device-Nonce`: canonical lowercase UUID, unique for every HTTP attempt.
* `X-Device-Signature`: standard padded Base64 DER ECDSA-SHA256 signature of the canonical bytes below.

Device ID is lowercase hexadecimal SHA-256 of the exact canonical DER public key. The server verifies possession of its private key on every request. The signed UTF-8 bytes are exactly these six lines, with **no trailing newline**:

```text
DeviceLink relay request v1
METHOD
PATH_WITH_QUERY
TIME
NONCE
SHA256_HEX_BODY
```

`METHOD` is uppercase. `PATH_WITH_QUERY` is the raw HTTP path and query (including original percent escaping and ordering), such as `/v1/messages?wait=25&exclude=uuid1,uuid2`; it excludes scheme and hostname. The body digest covers the exact transmitted bytes, including whitespace. Bodyless GET/DELETE requests hash zero bytes. Nonces are consumed after authentication even if later validation fails. Retries must use a fresh nonce/signature/time with the same message body. A nonce remains persisted until that signed timestamp can no longer be accepted; future-dated requests cannot bypass replay protection after a restart.

| Endpoint | Body / result |
| --- | --- |
| `POST /v1/register` | Body `{}`. Optional `X-Enrollment-Token` when configured. Returns `200 {"deviceId":"…"}`; repeated registration of the same identity is safe. |
| `PUT /v1/peers/{senderId}` | Recipient-signed body `{}`. Allows that sender to place envelopes in the caller's mailbox; returns 204. The sender can enroll later. |
| `DELETE /v1/peers/{senderId}` | Recipient-signed. Removes allowlist entry and all pending envelopes from that sender atomically; returns 204. |
| `POST /v1/messages` | Sender-signed envelope JSON. Returns `201 {"id":"…"}` for a new item, 200 for an exact-body retry. |
| `GET /v1/messages?wait=25` | Recipient-signed. Returns an array immediately if any envelopes are available, otherwise waits up to `wait` seconds (maximum `max_poll_wait`, default 50) and returns `[]`. Omit `wait` for immediate read. |
| `GET /v1/messages?wait=25&exclude=id1,id2` | Ignores up to 20 distinct canonical UUIDs while polling; useful for locally offered files awaiting explicit user acceptance. Exclusion does not acknowledge, extend expiry or free quota. |
| `GET /v1/messages?wait=25&limit=1` | Optional `limit=1..20` (default 20), applied after exclusions. Android requests one item to bound peak decode/decryption memory. |
| `DELETE /v1/messages/{id}` | Recipient acknowledgement. Deletes only the caller's item; always 204 for a valid UUID, without exposing another mailbox's item existence. |
| `GET /v1/info` | Unauthenticated: version, `maxLifetimeSeconds`, enrollment mode, push providers. |
| `POST /v1/pairings` | Body `{"id":"<32 hex>","expiresAt":ms}`; at most 5 open per device. |
| `POST /v1/pairings/{id}/join` | Body `{"sealed":"<Base64>"}`; exactly one joiner. `X-Pairing-Id` on `/v1/register` admits a joiner without the enrollment token while the pairing is open. |
| `GET /v1/pairings/{id}?wait=0..25` | Creator or joiner only: `{"state","joinerId","sealed"}`; long-polls for the next state. |
| `POST /v1/pairings/{id}/confirm` | Creator; records the link and allows the joiner into the creator's mailbox. |
| `DELETE /v1/pairings/{id}` | Cancel. |
| `PUT /v1/push`, `DELETE /v1/push` | Register/remove an APNs token (`provider`, `token`, `environment`, `topic`). |

The allowlist is directional; both phones allow each other for bidirectional messages/receipts. Registering is independent of pairing. A client must establish a trusted peer identity and encryption key before allowing or sending to it. There is no public device directory, server-side key replacement or identity recovery endpoint.

The envelope has exactly these fields (field ordering is not significant on initial submission):

```json
{
  "version": 1,
  "id": "65f10868-ab40-4811-9604-3f2d16d7c043",
  "senderId": "<64 lowercase hex characters>",
  "recipientId": "<64 lowercase hex characters>",
  "createdAt": 1800000000000,
  "expiresAt": 1800000060000,
  "sequence": 1,
  "ciphertext": "<standard padded Base64>",
  "signature": "<standard padded Base64 end-to-end signature>"
}
```

The HTTP caller must match `senderId`; recipient must be a different, registered identity that has allowed the sender. IDs are canonical lowercase UUIDs. Sequence is a positive signed 64-bit integer. Timestamps are nonnegative signed 64-bit millisecond values. `version` is 1 (legacy Android Internet Link) or 2 (Link v2). Creation may be at most 60 seconds in the future; expiry must be in the future and at most `max_ttl_ms` (default 10 minutes) after creation. The server does not inspect encrypted payload type and cannot enforce the client's shorter clipboard lifetime itself.

Envelope signatures are opaque to the relay, but must be Base64 of 8–80 bytes. Ciphertext must decode to 16 bytes–16 MiB. The full request limit is 24 MiB. Duplicate JSON keys, unknown fields, wrong scalar types and malformed Base64 are rejected with a generic error containing no submitted value. Receivers must independently validate end-to-end signatures, decrypt, enforce payload limits/expiry and apply their replay/sequence policy. Listing does not acknowledge messages. Results sort by sender ID, then descending sender sequence, then message ID; there is no cross-sender semantic ordering.

An accepted ID is bound to the SHA-256 digest of its exact original body until expiry, including after acknowledgement or peer revocation. Retrying that same body returns 200 without re-delivery after acknowledgement. Reusing the ID with different bytes returns 409. This means callers retain the exact serialized envelope for retries instead of reserializing/re-encrypting it.

## Bounds and failure behavior

Default limits are 20 pending envelopes / recipient, 64 MiB recipient data, 256 MiB global data, 100 allowed peers / recipient, 10,000 enrolled devices and 100,000 unexpired deduplication records. Successfully authenticated attempts are limited to 120/device/minute and 10,000 total/minute using persistent sliding-window records. Invalid signatures never gain mailbox access; the HTTPS proxy also rate-limits unauthenticated traffic by IP. There is at most one active long poll per identity. Empty polls sleep on a condition; there is no tight database polling loop. Expiry cleanup runs on startup, every 30 seconds and on mailbox/write operations.

Errors use `{"error":"machine_code"}`. Relevant statuses: 400 invalid metadata/body, 401 invalid signature or timestamp, 403 not registered/not allowed/enrollment required, 409 replayed request or conflicting message ID, 413 body too large, 429 rate/mailbox/peer/poll limit, 503 database unavailable, 507 global capacity exhausted. A 429 response includes `Retry-After: 60`; clients should back off and jitter network/5xx retries. Preserve unsent local work if the relay is full. An HTTP enqueue success confirms durable relay acceptance, not delivery or clipboard application; clients use encrypted receipts for those outcomes.

Acknowledgement belongs after durable local processing. A revoked peer's deleted queued messages do not return if the peer is later allowed again. Expired/deleted messages are not recoverable through this API. Off/session-stop clients must cancel their long poll and must not upload queued clipboard actions after reconnect.
