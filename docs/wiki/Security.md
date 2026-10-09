# Security

## Intended trust model

Two users physically compare the first-pairing code, or one user holds both
phones. Network peers are untrusted until cryptographic identity and bilateral
approval complete. Device display names and Wi-Fi addresses are never identities.

An Android Keystore P-256 signing key identifies each installation. Each session
uses new random challenges and ephemeral P-256 ECDH keys. Hello commitments are
exchanged before revealing their contents to prevent choosing a nonce after
seeing the peer's material and grinding a matching short authentication code.
Signed transcripts bind both identities, nonces, ephemeral keys and device names.

HKDF-SHA256 derives separate AES-256-GCM keys for each direction. Record sequence
numbers are authenticated and must be strictly ordered, rejecting replay,
reordering and reflection. Keys are fresh per session. The six-digit comparison
is a human authentication step, not the encryption key or a password.

## Data permissions and bounds

- Clipboard content leaves only after an explicit user action. Ordinary incoming
  `Text` remains in DeviceLink until the user taps Copy.
- An explicit `Clip` from the authenticated peer requests replacement of the
  receiver's clipboard during the active session. The application checks its
  one-shot event and unexpired session immediately before the synchronous write.
  Only an actual successful write is reported as Copied; stop/reconnect/cancel
  invalidate pending delivery. No background clipboard monitoring is installed.
- Image clipboard actions carry bounded validated image bytes through scoped
  content URIs. A newer accepted copy supersedes an older image still transferring.
  The app rechecks session and its own clipboard revision after image import;
  it cannot observe intervening background copies made in unrelated apps.
- Chat-sample Send stores a local message only. Its private history retains at
  most 50 messages, each bounded to 8,192 UTF-8 bytes; it is not synchronized.
- Files are accessible through user-provided URI grants and the system picker;
  no all-files permission is requested.
- Nearby file bytes require a matching accepted offer and declared size. Relay
  files arrive as complete encrypted envelopes before the user's Receive choice;
  explicit acceptance authorizes saving the ordinary received file.
- Record sizes, text length, metadata and offered file size are bounded in code.
- Partial files are removed on cancellation/failure; completed files are private
  and shared onward through scoped FileProvider grants.
- Identity and tray backups are disabled; private keys are not exported.
- Unpairing revokes remembered trust and disconnects that current peer.

## Internet trust and encryption

Internet Link requires an explicitly configured relay and previously verified
nearby identity. An identity-signed encryption-key bundle is exchanged through
the authenticated nearby channel and pinned locally. Server registration and an
enrollment token are not peer trust. Replacement encryption keys are not accepted
silently; there is no account-based key recovery.

Tink 1.23.0 HPKE uses X25519/HKDF-SHA256/AES-256-GCM. The persisted private HPKE
keyset is wrapped by Android Keystore, with initialization refusing unprotected
fallback. The existing Keystore P-256 identity signs key bundles and complete
envelopes. Version, routing identities, message ID, creation/expiry and sequence
are authenticated context and signed along with ciphertext. Kind, text, file
metadata/content and delivery receipts remain encrypted.

The relay has device public keys and routing/size/timing metadata. Its HTTPS API
checks signed method, raw request target, timestamp, fresh nonce and body digest;
persistent nonce records reject replay across restart. Recipient allowlists,
strict byte/count limits, atomic exact-body deduplication, acknowledgement and
expiry protect mailbox access. Public deployment needs valid TLS, restrictive
filesystem permissions and the provided proxy limits. Logs exclude request paths,
keys and payloads. Server ciphertext deletion is not a guarantee against an
operator retaining copies or filesystem recovery.

Client ID deduplication is durable; clipboard sequence state rejects older actions
per sender. Encrypted clipboard/receipt payloads expire after 60 seconds, ordinary
text/files after 10 minutes (relay cap). Automatic internet clipboard writes require the
receiver's explicit setting (default off), active session and a final freshness
check. File offers require explicit Receive. Release URLs require platform-validated
HTTPS; debug allows plaintext only to exactly `127.0.0.1` for ADB-reversed tests.

The persisted recipient encryption key provides no forward secrecy against later
compromise of that key: retained envelopes may become decryptable. No ratchet,
automated rotation or server recovery protocol is claimed. Local peer revocation
takes effect immediately. Pending server ACL deletions persist per relay URL and
retry on the next active poll/start; serialized grant/delete operations prevent an
older in-flight grant from reversing a later revocation. Remote mailbox cleanup
still requires a reachable server, and no network retry runs while Internet Link
is off.
See [[Internet-Link]] for the implementation and deployment limits.

## Limits

This is an initial implementation with focused adversarial tests and a code
review, not an independently audited cryptographic product. Endpoint compromise,
malicious source/receiving apps, users accepting mismatched codes, and content
already saved outside DeviceLink are outside its protection. The app does not
inspect file contents for malware. Removing a received item cannot revoke a copy
already saved or shared elsewhere. Radio-level denial of service remains possible.
