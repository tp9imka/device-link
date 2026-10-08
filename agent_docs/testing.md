# Testing

Tests protect the wire protocol, authenticated channel, session policy and file
frame semantics, explicit clipboard commands and one-shot delivery lifetimes. They do not chase a coverage percentage. Architecture boundaries
are guarded by scripts/check_architecture.py, invoked from ./gradlew check.

Run ./gradlew :core:model:test for fast protocol/security checks. Run
./gradlew check for contract tests, adapter tests, lint and APK assembly.

Use two real phones for discovery, first pairing, remembered reconnection,
Sharesheet text in both directions, file acceptance/cancellation/integrity,
process-death behavior and service shutdown. Use only synthetic payloads.
Record evidence in docs/validation.md. Screen-off behavior must be tested on-device;
USB-powered tests cannot establish real battery drain.

For the clipboard sample, verify actual paste contents on the receiver independently
of the Copied tray label. Exercise message Copy, selected-text Copy, disconnected
local-only copying, expiry/stop during delivery, receiver write failure and missing
acknowledgement. Ordinary Text must remain a tray-only operation. Chat Send must
persist locally without sending a Clip; test restart and last-50/8,192-UTF-8-byte
limits. Unit tests do not establish OS clipboard permissions or OEM behavior.
