# DeviceLink - Android

Native Android app for explicit nearby text, image and file exchange. Kotlin,
Compose Material 3, Android 8+ (API 26). No root or privileged clipboard access.

## Commands
- Build: `./gradlew :app:assembleDebug`.
- Verify: `./gradlew check` (contract/logic tests, Android lint, architecture gate).
- Domain tests: `./gradlew :core:model:test`.
- SDK path: local.properties (ignored). JDK 17; Gradle wrapper is authoritative.

## Architecture
- `core/model`: platform-independent contracts and protected business logic.
- `core/transfer`: Android Wi-Fi Direct transport, persistence, trust and file adapters.
- `core/designsystem`: all visual tokens, themes and reusable Compose components.
- `feature/link`: state-driven Compose experience; depends on contracts and design system.
- `app`: composition root, activity, sharing, notification/service and tile integration.
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
- Authenticate peers before application data; never trust endpoint names as identity.
- Do not log clipboard text, file names, URIs, keys or peer names.
- Visual values belong in core/designsystem. Runtime appearance settings must work without editing screens.
- User strings belong in Android resources. Respect font scaling, RTL and system insets.
- Do not add secret files or claim two-phone validation from emulator/unit tests.

## References
- `docs/adr/0001-architecture.md`: scope, decisions and verification plan.
- `agent_docs/testing.md`: tests and device validation.
- Adopted selectively from `/Users/aiva6306/Projects/agent-rules/stacks/kotlin-android`.
