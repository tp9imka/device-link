# 0005 - Link v2: QR-paired, cross-platform clipboard over the relay

Status: accepted and implemented (`sdk/`, `apps/`, `ios/`, `server/`, `docs/protocol/link-v2.md`).

## Problem

The product goal changed from "two Android phones nearby" to "copy on one device, paste on the
other", including iPhones, with the relay as the default path. The v1 Internet Link could not serve
that: trust came only from a Wi-Fi Direct pairing (impossible with iOS), keys were Tink-proto keysets
(no iOS implementation), sessions lasted 15 minutes, and mailbox items could wait 24 hours.

## Decision

1. **Pair by QR through the relay.** The device that shows the code creates a one-time 32-byte
   secret; the QR is an `https://<relay>/pair#…` link carrying the secret and the inviter's device ID
   in the fragment (never sent to the server). The joiner and inviter exchange signed key bundles
   sealed with HKDF-derived AES-GCM keys through a single-use rendezvous on the relay. The joiner pins
   the inviter ID from the QR; the inviter accepts the one device that proves knowledge of the secret.
   Scanning a valid code also enrolls the joiner on a token-protected relay.
2. **No setup on the first device.** A relay URL (and optional enrollment token) is built into the
   apps from private build configuration. On first launch the device creates its identity and can show
   a pairing code immediately. The admin setup QR is only a fallback for unconfigured builds.
3. **Interoperable primitives.** P-256 ECDSA identities (Android Keystore, iOS Secure Enclave) and
   RFC 9180 HPKE with X25519/HKDF-SHA256/ChaCha20-Poly1305 using raw keys (Tink on Android/JVM,
   CryptoKit on iOS). Canonical length-prefixed byte strings are bound into signatures and the HPKE
   context. Kotlin and Swift exchange checked-in vectors and pair live in CI.
4. **Short-lived sandbox.** Clients give items 5 minutes; the relay caps lifetime at 10 minutes and
   deletes on acknowledgement or expiry (janitor every 10 seconds). Five minutes covers "copy now,
   paste on the other device within a few minutes", including opening an iPhone app after a push.
5. **Receiving.** Android runs a visible `remoteMessaging` foreground service that long-polls and
   writes clips to the clipboard; Off stops all polling. Items that cannot go to the clipboard (files,
   failed writes) produce a notification with **Share…** (system chooser), **Open** and **Copy**.
   iOS receives while the app is open (plus a 25 s grace period), on an optional content-free APNs
   alert, or through the *Get DeviceLink clip* App Intent (Back Tap, Action button, Siri).
6. **Sending from a receiver.** Background clipboard capture stays unavailable on both platforms, so
   sending is one explicit gesture: Android text-selection *Send to device* (`PROCESS_TEXT`), share
   sheet, Quick Settings tile and notification action (an invisible activity reads the clipboard once
   focused), in-app Paste & send; iOS share extension, `PasteButton`, and a Shortcuts action for Back Tap.
   SDK host apps auto-send every copy made inside them while in the foreground.
7. **Operator dashboard.** `/admin` on the relay, with credentials from configuration (PBKDF2 hash
   recommended), shows devices, linked pairs ("sandboxes") with pending encrypted items and expiry
   countdowns, delivery latency, activity and an enrollment QR. It can label, block, remove devices,
   unlink pairs and purge items. It cannot decrypt content: only metadata and ciphertext fingerprints.

## Consequences

- Possession of a QR code within its 5-minute lifetime is the pairing authorization; codes are single
  use and the inviter sees the joiner's name. A camera-visible code should be treated like a password.
- The relay sees routing metadata, sizes, timing, device platform/model/app version and push tokens.
  It never sees display names, clipboard content, file names or MIME types.
- Persisted HPKE keys mean no forward secrecy against later key compromise (same as v1).
- A first-use relay URL is baked into a build; switching relays requires unlinking first.
- iOS cannot guarantee background delivery without push; the 5-minute window plus App Intent is the
  pragmatic path. Android receive costs one long poll per ~25 s while on.
- The legacy Wi-Fi Direct app (`app/`, `core/`, `feature/`) remains for nearby use; its v1 relay
  envelopes still work, but the relay now caps lifetimes at 10 minutes.

## Verification

Relay contract tests (48), Kotlin core tests including RFC 9180/5869 vectors and an engine suite
against a fake relay, Swift tests including the RFC 9180 vector, cross-language vectors, and
`scripts/interop_e2e.sh` (live relay; Kotlin and Swift pair by link in both directions, exchange text,
a 150 KB image and receipts). Android modules are compiled in CI (`./gradlew check`) and iOS targets
with `xcodebuild`. None of this replaces two-device validation on real hardware (clipboard, Doze,
push, Back Tap), which is listed in `docs/validation.md`.
