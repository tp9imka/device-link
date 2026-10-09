# Agent handoff specs

Work that needs hardware, accounts or the owner's machine, so it could not be done in the cloud
container that built Link v2. Each spec is self-contained: give one file to an agent (or person) as
its task. Read `AGENTS.md` first; its rules (no secrets in git, no operator hostnames, no content in
logs, no device claims from emulators) apply to every spec.

| # | Spec | Needs | Blocks |
| --- | --- | --- | --- |
| 01 | [Relay behind a named Cloudflare tunnel + private builds](01-tunnel-and-private-builds.md) | Owner's Mac, Cloudflare account, domain | 02–04 |
| 02 | [Two-device validation run](02-device-validation.md) | 2+ phones (Android and iPhone), optional Mac | 05 |
| 03 | [Battery measurement](03-battery.md) | Android phone(s) on battery, adb | 05 |
| 04 | [APNs, iOS signing and on-device iOS checks](04-apns-and-ios-signing.md) | Apple developer account, iPhone | 02 (iPhone cases) |
| 05 | [Fixes driven by device findings](05-device-findings-fixes.md) | Results of 02–04 | — |
| 06 | [Release builds and distribution](06-release-distribution.md) | Play Console / App Store Connect / Developer ID | — |
| 07 | [Security review and key lifecycle](07-security-review.md) | An independent reviewer | — |

`HANDOFF.md` records where the Link v2 implementation stopped and what is verified.
