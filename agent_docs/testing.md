# Testing

Tests protect the wire protocol, authenticated channel, session policy and file
frame semantics. They do not chase a coverage percentage. Architecture boundaries
are guarded by scripts/check_architecture.py, invoked from ./gradlew check.

Run ./gradlew :core:model:test for fast protocol/security checks. Run
./gradlew check for contract tests, adapter tests, lint and APK assembly.

Use two real phones for discovery, first pairing, remembered reconnection,
Sharesheet text in both directions, file acceptance/cancellation/integrity,
process-death behavior and service shutdown. Use only synthetic payloads.
Record evidence in docs/validation.md. Screen-off behavior must be tested on-device;
USB-powered tests cannot establish real battery drain.
