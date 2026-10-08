# Development

## Build

Use JDK 17, Android SDK 37 and the checked-in Gradle wrapper. The build versions
are pinned in `gradle/libs.versions.toml`. `local.properties` supplies `sdk.dir`
and is never committed.

```sh
./gradlew :core:model:test
./gradlew :app:assembleDebug
./gradlew check
```

The root check command runs the same core gate locally and in CI: protocol,
cryptographic and session tests, architecture guard, Android lint and app assembly.
Physical two-phone tests remain an explicit integration tier. There is no 100%
coverage target, mutation threshold or mandatory test for every rendering branch.

## Changes

Branch from main; use conventional commits; update architecture/workflow docs in
the same change as behavior. Keep UI depending on LinkController and immutable
LinkState. Android transport adapters implement those contracts. Constructor
wiring belongs in app. Run focused tests first, then the full check before push.

Sensitive content, file paths, keys and phone names do not belong in logs or CI
artifacts. Test with synthetic text and files. Local device screenshots and raw
reports live in ignored `artifacts/`; publish only selected app-only screenshots.

## Appearance

Settings changes theme, accent, corners and Android dynamic colors at runtime.
The design system owns visual tokens and common components. Read the repository's
`docs/design-system.md` before extending a screen or appearance option.

## Wiki maintenance

Edit `docs/wiki/*.md` in the code repository. After the server's wiki has its first
page, run `scripts/publish-wiki.sh`; the script copies only Markdown pages and
pushes the separate wiki repository. Main-repository docs stay the source of truth.
