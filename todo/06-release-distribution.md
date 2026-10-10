# 06 · Release builds and distribution

## Goal

Installable signed builds for testers: Android APK/AAB, iOS TestFlight, macOS notarized app.

## Needs (owner)

Android upload keystore (kept outside git), Google Play Console (optional, internal testing),
Apple Developer Program (TestFlight, Developer ID for the Mac app).

## Android

1. Create a keystore outside the repo. Add a `signingConfigs.release` that reads path/passwords from
   `local.properties` or environment variables only; never commit the keystore or passwords.
2. `./gradlew :apps:receiver:assembleRelease :apps:sample:assembleRelease` (R8 is enabled; verify the
   SDK keeps its manifest components and kotlinx-serialization classes — add keep rules if a release
   build fails at runtime, and test pairing + a file send on the release build).
3. Play Console: the receiver uses a `remoteMessaging` foreground service; the declaration form must
   describe "receiving items the user sent from their own linked devices while the receiver is on".
   The optional keyboard (input method) needs the IME declaration; its clipboard use is user-visible
   and opt-in.

## iOS / macOS

1. Switch `aps-environment` to `production` for distribution builds (or use a separate config).
2. Archive DeviceLink (with share + notification extensions) → TestFlight. Privacy manifest: the app
   uses the pasteboard on user action and on receipt; declare `UserDefaults` and file timestamp
   API reasons if Xcode's privacy report requires them.
3. DeviceLinkMac: archive, sign with Developer ID, notarize (`xcrun notarytool`), staple.

## Done when

Testers can install signed builds of every app, and the release builds pass kit cases P1, D1, D7.
