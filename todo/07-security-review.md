# 07 · Security review and key lifecycle

## Goal

An independent review of Link v2 before wider use, plus the key-lifecycle features the design defers.

## Review scope (give the reviewer these)

- `docs/protocol/link-v2.md`, `docs/adr/0005-link-v2-cross-platform.md`.
- Engines: `sdk/core/src/main/kotlin/dev/devicelink/sdk/core/` and `ios/DeviceLinkKit/Sources/DeviceLinkKit/`
  (HPKE use, signature coverage, canonical encoding, replay/expiry checks, chunk reassembly limits).
- Pairing: QR secret handling (fragment never sent to the server), HKDF labels, sealed join/confirm,
  single use and expiry, the display-only confirmation code.
- Relay: `server/relay/auth.py` (request signatures, nonce replay), `store.py` quotas, `admin.py`
  (session cookie, CSRF, CSP, login throttle), `push.py` (content-free payloads).
- Platform storage: Android Keystore + wrapped X25519 key (`sdk/android/.../KeystoreKeys.kt`), iOS
  Secure Enclave/keychain and App Group sharing (`ios/Shared/LinkEnvironment.swift`).

## Questions to answer

1. Can the relay operator (or a stolen relay database) read, alter, replay or redirect items? Expected: no.
2. What can a photographed QR code do, and for how long? Expected: one link within 5 minutes.
3. What does a lost/stolen unlocked phone allow, and does Reset/Unlink contain it?
4. Is the dashboard safe on the public internet (auth, CSRF, XSS through device labels/models)?

## Deferred features (implement only after the review agrees)

- Forward secrecy: rotate the X25519 encryption key periodically and announce it in a signed
  link-control message; both engines + spec + vectors.
- Remote unlink when a device is lost: "unlink device X everywhere" signed by another linked device.
- Optional relay-side retention metrics only (no content), already present; consider making the
  events log opt-out.

## Done when

The review report is stored (outside git if it contains sensitive findings), and each finding is
fixed or explicitly accepted in `docs/adr/`.
