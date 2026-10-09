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
Clipboard contract tests distinguish `Clip` from unchanged ordinary `Text` bytes,
validate UTF-8 and wire limits, and exercise malformed input. Transport tests
protect one-shot delivery, cancellation/session invalidation and bounded pending
capacity. Physical two-phone tests remain an explicit integration tier. There is no 100%
coverage target, mutation threshold or mandatory test for every rendering branch.

Relay setup/checks are separate from Gradle:

```sh
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements-test.txt
.venv/bin/python -m pytest -q
.venv/bin/python -m relay
```

Python 3.14 and fully pinned runtime/test requirements are supplied. The normal
runner binds `127.0.0.1:8000`; deployment requires HTTPS termination. See the
[server guide](https://git.oryxlabs.internal/ivan-antsimonau/device-link/blob/main/server/README.md)
for the API, Docker/systemd/nginx templates, SQLite backup and enrollment-token
configuration. The database, environment files, virtualenv and caches are ignored.
Do not commit live relay identity/mailbox data or tokens.

Relay model tests additionally protect HPKE bundle/envelope signature binding,
recipient identity, freshness, replay policy and size/schema validation. Transfer
tests cover the release-HTTPS/debug-loopback URL boundary, clipboard action order
across text/images, and cancellation cleanup after the incoming stream closes.
The final integrated JUnit reports contain 66 tests: 34 model, 29 transfer and
3 app, with zero failures/errors/skips. The independent server suite contains 32 passing
contract tests. Local startup, Docker build and container health have passed;
public TLS deployment, cellular/independent-network and sustained Doze/battery
remain separate validation work. Local-relay phone checks now include image/text
clipboard paste, explicit file acceptance and sender-offline mailbox delivery;
the native nearby image clipboard path was also exercised.

The repository Actions workflow has independent Android and relay jobs. The relay
job installs the pinned Python 3.14.6 dependencies, runs `pip check` and executes
the server contract suite; Gradle `check` remains Android-only. This follows the
[setup-python action](https://github.com/actions/setup-python) workflow pattern.
Adding the job does not establish a successful remote CI run.

The GitHub Enterprise repository needs a runner matching the workflow's
`ubuntu-latest` label. As of the initial validation, no runner is available and
Actions runs remain queued. Run the local gate until a runner is provisioned;
queued CI does not count as a passing check.

For a debug phone test, run a local relay, use
`adb -s SERIAL reverse tcp:8000 tcp:8000`, and configure
`http://127.0.0.1:8000` in the debug app. No other plaintext
HTTP host is accepted. Remove that ADB reverse after testing. Release verification
must use a valid HTTPS endpoint; do not bypass certificate validation.

## Optional manual two-phone relay probe

`app/src/androidTest/.../RelayDeviceProbe.kt` is explicit integration instrumentation,
not part of the unit-test or CI gate. Use two unlocked, already nearby-paired
phones with the debug app installed and permissions granted. Save the relay URL
on the receiving phone, enable its automatic clipboard setting for this test, and
start its nearby and internet sessions. Both phones need access to the test relay.
The sender probe configures an empty enrollment token and automatic clipboard on,
so use an isolated local relay without an enrollment token.
If the sender already knows several relay peers, select this receiver in the
sender's Internet Link first: `peerName` chooses the nearby connection, while sends
use the selected internet peer. Accept any Android Wi-Fi Direct invitation.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SENDER_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SENDER_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SENDER_SERIAL reverse tcp:8000 tcp:8000
adb -s RECEIVER_SERIAL reverse tcp:8000 tcp:8000
adb -s SENDER_SERIAL shell "am instrument -w \
  -e relayUrl http://127.0.0.1:8000 \
  -e peerName 'Receiver DeviceLink name' \
  dev.devicelink.test/dev.devicelink.RelayDeviceProbe"
```

`peerName` must exactly match the receiver's remembered DeviceLink display name.
The outer double quotes preserve the inner name quotes for the device shell, so
names containing spaces are passed as one instrumentation argument.
The probe waits for authenticated nearby connection and pinned relay-key exchange,
stops nearby, then sends synthetic text/image clipboard payloads and an ordinary
image file through the relay. It emits progress markers; explicitly tap Receive
on the other phone when the ordinary-file step appears. It requires remote
clipboard acknowledgements and file completion before reporting PASS. It does
not approve initial pairing or read any background clipboard.

Verify actual receiver paste and image URI consumption separately; a result label
alone is not proof that another app can paste it. The recorded run additionally
used a focused, ordinary-permission temporary external probe, removed afterward.
After a manual run, stop both sessions, remove ADB reverse mappings and restore
clipboard settings as appropriate. Public-release testing must use HTTPS instead.

```sh
adb -s SENDER_SERIAL reverse --remove tcp:8000
adb -s RECEIVER_SERIAL reverse --remove tcp:8000
adb -s SENDER_SERIAL uninstall dev.devicelink.test
```

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
