# 0001 - Native Android DeviceLink

Status: accepted for initial implementation by the user's end-to-end build instruction.

## Scope
Two ordinary Android phones running DeviceLink exchange text and files locally.
Pair once through verified encrypted-channel authentication and persistent cryptographic
identity; QR may bootstrap peer selection. Explicit 15 minute sessions, a
Quick Settings tile, Android Sharesheet input, receive tray, foreground clipboard
actions, transfer consent/progress/cancellation, expiry and unpairing.
No server, account, background clipboard scraping, root or system permission.
Native Wi-Fi Direct is the transport. Google Play services are not required.
The connected KATIM X3M has no Play services, so the initial Nearby proposal
was replaced before the first delivery.

## Shape
app => feature/link => core/model + core/designsystem.
app => core/transfer => core/model.
Manual constructor injection avoids a DI code-generation dependency for this graph.
Transport publishes immutable StateFlow snapshots; UI issues commands through LinkController.
The design system owns tokens and components. Runtime appearance settings supply
validated theme overrides without editing feature code.

## Correctness and trust
Remembered identity must prove private-key possession on each new connection.
New pairing requires matching channel authentication code on both phones, or an
authenticated QR binding. No user payload before identity verification.
File offers precede payloads; receiver acceptance authorizes a bounded transfer.
Persist received files privately, expose them through scoped content URIs.
Never overwrite local clipboard automatically. Process death closes sessions.
Off performs no discovery or polling. Deadline prevents new work, allows existing
transfers to finish, then tears down transport; explicit stop cancels immediately.

## Tests to protect
- Wire validation, limits, malformed input and file-name sanitization.
- Trust proof binding and replay rejection.
- Session expiry with active transfers and transfer state transitions.
- Architecture imports and module dependency direction.
- Android lint and app assembly; on-device sharing, permissions, appearance and tile smoke tests.
- Two physical phones required to validate radio transfers and battery cost.

## Deferred
True cross-app automatic clipboard synchronization is unavailable to ordinary apps.
Always-ready background discovery, cloud relay, multi-peer broadcasting and full
cross-process transfer resumption are outside the initial version. Interrupted
transfers offer retry; no silent promise of resume.

## Rule adoption
The agent-rules templates are guidance, not copied wholesale. User explicitly
rejects 100% coverage. Retained: boundaries, constructor injection, unidirectional
state, local privacy-conscious diagnostics, targeted tests and one check command.
