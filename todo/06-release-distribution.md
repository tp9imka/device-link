# 06 · Release builds and distribution

## Goal

Installable signed builds for testers and the stores: Android AAB/APK, iOS TestFlight, macOS notarized app.
The step-by-step checklist, store declaration texts and Data safety / nutrition label answers are in
[docs/release.md](../docs/release.md); the privacy policy page is [docs/wiki/Privacy.md](../docs/wiki/Privacy.md).

## Done in the repo

- Android release signing hook in `apps/receiver` and `apps/sample` (`devicelink.signing.*` in
  `local.properties` or `DEVICELINK_SIGNING_*`); unsigned release builds keep working without it.
- `aps-environment` is `development` for Debug and `production` for Release (`DL_APS_ENVIRONMENT`).
- Hardened runtime on `DeviceLinkMac`.
- Privacy manifests for DeviceLink, DeviceLinkShare and DeviceLinkSample.
- Play Console texts (remoteMessaging service, keyboard, notifications, camera), Data safety answers,
  App Store privacy label answers, relay operator obligations: `docs/release.md`.
- Privacy policy draft with placeholders: `docs/wiki/Privacy.md`, linked from the wiki sidebar.
- `targetSdk = 36` meets the current Play requirement.

## Needs the owner

1. Android upload keystore, created and backed up outside git; signing properties set locally.
2. Google Play Console account; app entry for `dev.devicelink.receiver`; paste the declarations and
   the Data safety answers; publish the privacy page and fill its placeholders.
3. Apple Developer Program team: `ios/Config/Private.xcconfig`, App IDs, App Group, push capability,
   Developer ID certificate and a `notarytool` keychain profile.
4. Real APNs `.p8` key on the production relay (`apns_key_path`, `apns_key_id`, `apns_team_id`).
5. A production relay over HTTPS with enrollment token, admin password hash, session secret and backups.

## Still to verify

- R8 release build at runtime on a real phone: pairing, text, image, file (cases P1, D1, D7 in `docs/validation-kit.md`).
- TestFlight build on two real devices including a push wake-up.
- A notarized, stapled Developer ID build of DeviceLinkMac.

## Done when

Testers can install signed builds of every app, and the release builds pass kit cases P1, D1, D7.
