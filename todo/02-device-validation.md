# 02 · Two-device validation run

## Goal

Execute `docs/validation-kit.md` on real hardware and record the results in `docs/validation.md`
under "Link v2", replacing items in its "Pending on real devices" list with observed outcomes.

## Prerequisites

- Spec 01 done (public relay + private builds installed). Admin dashboard open on a laptop.
- Minimum matrix: Android ↔ Android, Android ↔ iPhone. Better: add a second Android OEM (Samsung or
  Xiaomi skin), an Android 10–12 phone, and the Mac app.
- One run with the phones on different networks (Wi-Fi + cellular).

## Rules

- Only the fixtures in the kit. No personal data in screenshots; store them under the git-ignored
  `artifacts/device-tests/<date>/`.
- A case passes only when observed on a physical device. Note OS version, OEM and build for each.
- Failing cases are findings, not things to hide: record them precisely (steps, expected, observed,
  diagnostics report, admin event timeline) and file them for spec 05.
- Do not change code during the run; collect findings first.

## Steps

1. Record build versions (`Info` / diagnostics report on each device) and relay version (admin).
2. Run sections 1–5 of the kit in order. Pairing cases P1–P7 first; P6 is the "never asks again"
   guarantee and must be checked after a reboot **and** after an app update (install a newer build).
3. For each delivery case, note latency from the admin Activity log (`delivered` latency).
4. Export the admin events as CSV after the run (Overview → Export) and keep it with the artifacts.
5. Update `docs/validation.md`: a dated "Link v2 device run" section with a table of case → result.

## Done when

- Every kit case has pass / fail / not-applicable with device names.
- Failures have a reproducible description ready for spec 05.
