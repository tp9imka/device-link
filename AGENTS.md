# DeviceLink

Copy on one device, paste on another. Link v2 pairs Android, iOS and desktop devices by QR through a
self-hosted relay (`sdk/`, `apps/`, `ios/`, `server/`); the original nearby Wi-Fi Direct Android app
(`app/`, `core/`, `feature/`) remains. Kotlin, Swift, Python. Android 8+ (API 26), iOS 17+.
No root or privileged clipboard access.

## Commands
- Build: `./gradlew :app:assembleDebug`.
- Verify: `./gradlew check` (contract/logic tests, Android lint, architecture gate).
- Domain tests: `./gradlew :core:model:test`, Link v2 core: `./gradlew :sdk:core:test`.
- Relay: `cd server && python -m pytest -q`. Swift: `swift test --package-path ios/DeviceLinkKit`.
- Cross-platform live check: `scripts/interop_e2e.sh` (relay + Kotlin + Swift CLI).
- iOS apps: `cd ios && xcodegen` (private values in `ios/Config/Private.xcconfig`, git-ignored).
- SDK path: local.properties (ignored). JDK 17; Gradle wrapper is authoritative.

## Architecture
- `core/model`: platform-independent contracts and protected business logic.
- `core/transfer`: Android Wi-Fi Direct transport, persistence, trust and file adapters.
- `core/designsystem`: all visual tokens, themes and reusable Compose components.
- `feature/link`: state-driven Compose experience; depends on contracts and design system.
- `app`: composition root, activity, sharing, notification/service and tile integration.
- `sdk/core`: platform-independent Link v2 protocol and engine (pure JVM). Mirrored by `ios/DeviceLinkKit`;
  wire changes go to `docs/protocol/link-v2.md` and both implementations, then regenerate vectors.
- `sdk/android`: framework-only Android SDK (no AndroidX). `apps/receiver`, `apps/sample` use only its public API.
- `server/relay`: relay ("sandbox") and `/admin` dashboard; the dashboard never sees content.
- Constructor injection. StateFlow exposes immutable state. Android SDK dependencies stay out of core/model.
- UI does not import transport implementation. `scripts/check_architecture.py` enforces boundaries.

## Testing
- Protect wire contracts, authentication, session expiry and transfer lifecycle logic.
- No percentage coverage target, exhaustive UI-state coverage or mutation gate.
- Write meaningful behavior tests with fixes. Do not test constants or mirror implementation.
- Run focused tests during development and `./gradlew check` before completion.

## Product boundaries
- Background clipboard capture is unavailable on stock Android; access it only through explicit foreground interaction.
- Off means no app discovery, polling or persistent connections. Session timeout must not interrupt an active transfer.
- Link v2 receivers poll only while visibly on (Android foreground service, iOS app active + short grace).
- Sandbox items expire within 5 minutes (relay cap 10); never extend lifetimes or store plaintext on the relay.
- Pairing codes are single use and short lived; the QR fragment secret must never reach the server.
- Authenticate peers before application data; never trust endpoint names as identity.
- Do not log clipboard text, file names, URIs, keys or peer names.
- Visual values belong in core/designsystem (Compose app); Link v2 framework-view apps keep theirs in res/values themes/colors. Runtime appearance settings must work without editing screens.
- User strings belong in Android resources. Respect font scaling, RTL and system insets.
- Do not add secret files or claim two-phone validation from emulator/unit tests.
- Keep operator-specific hosting, tunnel setup, domains and deployment records outside the repository and wiki. Runtime endpoints belong in private configuration; shared deployment examples must stay generic.

## References
- `docs/adr/0001-architecture.md`: scope, decisions and verification plan.
- `docs/adr/0005-link-v2-cross-platform.md`, `docs/protocol/link-v2.md`: Link v2 design and wire contract.
- `agent_docs/testing.md`: tests and device validation.
- Adopted selectively from `/Users/aiva6306/Projects/agent-rules/stacks/kotlin-android`.
