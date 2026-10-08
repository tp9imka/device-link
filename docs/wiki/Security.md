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
- Chat-sample Send stores a local message only. Its private history retains at
  most 50 messages, each bounded to 8,192 UTF-8 bytes; it is not synchronized.
- Files are accessible through user-provided URI grants and the system picker;
  no all-files permission is requested.
- Incoming file bytes require a matching accepted offer and declared size.
- Record sizes, text length, metadata and offered file size are bounded in code.
- Partial files are removed on cancellation/failure; completed files are private
  and shared onward through scoped FileProvider grants.
- Identity and tray backups are disabled; private keys are not exported.
- Unpairing revokes remembered trust and disconnects that current peer.

## Limits

This is an initial implementation with focused adversarial tests and a code
review, not an independently audited cryptographic product. Endpoint compromise,
malicious source/receiving apps, users accepting mismatched codes, and content
already saved outside DeviceLink are outside its protection. The app does not
inspect file contents for malware. Removing a received item cannot revoke a copy
already saved or shared elsewhere. Radio-level denial of service remains possible.
