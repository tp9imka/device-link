# Optional internet relay — proposal

Status: design direction only. No server or push transport is implemented.

## Experience

Keep the same linked Copy action and local-only sample chat. Nearby delivery uses
the existing direct channel. An optional Internet sharing setting allows delivery
when the phones are on different networks. A user can choose whether incoming
fresh clipboard messages may replace their clipboard; otherwise show a received
item with an explicit Copy action. Disabling internet sharing stops relay access.

Pair once by verifying device keys, then show a manageable trusted-device list
with revoke controls. Account-free pairing is possible, but recovery after losing
all trusted devices must be designed explicitly rather than trusting the server
to restore membership.

## Encrypted mailbox

The sender encrypts a complete text envelope for the verified recipient and
uploads it to a bounded mailbox. It can go offline after the upload; the receiver
does not need to pull the text from the sender. Bind destination, message ID,
content kind, creation time and expiry into the authenticated envelope. Validate
freshness and replay protection locally before applying the clipboard.

Use established reviewed cryptographic protocols/libraries when implementing the
asynchronous encrypted channel. The existing live session cipher is not an
offline mailbox protocol and must not be reused by extending its lifetime.
Device private keys should use Android Keystore with hardware protection where
available. Newly added device keys require approval signed by an already trusted
device and locally verified membership state, with rollback/revocation handling.

The server cannot decrypt content but sees routing, size and timing metadata.
Delete envelopes after acknowledgement or short expiry, enforce quotas, and keep
payloads out of application/access logs. Ciphertext deletion is a server retention
policy, not a guarantee against a malicious server keeping ciphertext.

Clipboard messages should expire quickly and supersede older pending clipboard
messages from the same sender. A delayed or out-of-order message must not silently
overwrite newer received state. Offline deliveries beyond the freshness window
are discarded. Exact expiry and handling of a receiver's intervening local copy
require a product decision; background clipboard reads cannot reliably establish
that local copy history on stock Android.

Files use encrypted temporary blobs, bounded size/expiry, integrity checks and
resumable download. Large downloads remain explicit. Chat Send and chat history
do not use either mailbox or blob storage.

## Background delivery and battery

Push is a wake-up hint; the receiver fetches encrypted data and acknowledges the
actual result. FCM is one adapter, not a requirement for the core relay protocol.
Android normal-priority delivery can wait during Doze, and silent high-priority
messages can be downgraded. Do not promise instant invisible synchronization.
See [FCM Android priority guidance](https://firebase.google.com/docs/cloud-messaging/android-message-priority).

KATIM without Google Play services needs an available vendor push adapter, or an
explicit foreground Live link session with a persistent connection. Without such
a wake-up mechanism, fetch when DeviceLink opens or through deferred scheduled
work. Measure idle battery consumption before selecting Live link defaults;
timeout/manual off should remain visible user controls.

A server does not enable background clipboard capture. Automatic sends come from
explicit copy actions in an integrated app; other applications use Share or a
foreground Send clipboard action. See [Android clipboard restrictions](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data).

## Implementation decisions still needed

- Deployment owner, service URL and operational retention/quotas.
- Push availability on KATIM firmware and expected delivery latency without it.
- Explicit receiver consent, clipboard expiry and treatment of delayed messages.
- Reviewed asynchronous E2EE protocol, membership/recovery/revocation and replay state.
- Measured battery/latency targets and failure UX for offline/unreachable phones.

The proposal is a transport extension, not a replacement for the nearby feature.
