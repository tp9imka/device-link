# DeviceLink handoff (2026-10-09)

Migrated from `https://git.oryxlabs.internal/ivan-antsimonau/device-link` to
`https://github.com/tp9imka/device-link`. Local checkout: `~/Projects/device-link`.

## Where we stopped

**HEAD:** `a3c58de` on branch `feat/initial-android` (same tip as internal `origin/main`).
Working tree was clean at migration; no uncommitted or stash work to carry.

Last shipped commits (newest first):

- `a3c58de` chore: keep environment configuration local
- `d9eeb16` feat: add internet link sessions and image clipboard sharing
- `5e0530f` feat: add signed encrypted relay contracts and mailbox service
- `a290552` feat: add local chat sample with explicit linked clipboard
- …earlier nearby pairing / transfer hardening and docs

Shipped product surface at stop: nearby Wi-Fi Direct pairing + transfers, local
chat sample with explicit linked clipboard (text + image), optional Internet Link
encrypted mailbox (Python relay + Android session), ADRs 0001-0004, wiki under
`docs/wiki/`, validation record dated 2026-10-08 with Oct 9 follow-up notes.

## MemPalace recall (verbatim)

Knowledge graph had **no** facts for `device-link` / `DeviceLink`. Vector search is
degraded (`mempalace repair` advised). Useful drawers were BM25/session matches.

### Diary checkpoint (wing_obsidian)

```
CHECKPOINT:2026-10-08|session:f130bc5c-b650-4533-b156-f966063b4b5c|msgs:10|recent:<local-command-caveat>The command below was run directly in Claude Code, not sen|if I have a connection will I be able to support clipboard transfer passively? I|focus on android - android, is there a way to get the clipboard via broadcast re|okay, now I want to explore other ways, for example if I want it to happen passi|okay, now I want to explore other ways, for example if I want it to happen passi|one more thing, inside the device link add the sample screen with the data (to t|[Request interrupted by user]|regarding the spec, you can create it and drive the execution|sorry it was for another session, wrap up the conversation in one prompt which I|Base directory for this skill: /Users/aiva6306/.claude/skil
```

### Session ask that drove the clipboard sample (sessions / problems, `f130bc5c-…`)

```
> one more thing, inside the device link add the sample screen with the data (to test application integration) where I can copy the text, and it should automatically be transferred to another device, so I can paste it on both
```

(Source drawer also records the path discovery landing on `/Users/aiva6306/Projects/device-link`.)

### Wrap-up prompt written when that session stopped (at tip `1be8512`, before relay/image work)

Palace/session closed by producing a handoff prompt for another agent. Core settled
scope from that prompt (verbatim excerpts):

```
## Settled
1. Scope: the sample screen, automatic send for copies made on it, and an automatic
   clipboard write on the receiver for those clips only. Share > DeviceLink, Send
   clipboard and the tray Copy button stay as they are. If wrong, all incoming text
   silently replaces the clipboard.
2. No background clipboard capture. That product boundary stays.
3. Rewrite `dl_privacy_body` so it's accurate: the clipboard is replaced only by text
   copied on the sample screen of your paired phone.
4. Never log clipboard text. Sample data is synthetic.
```

```
## Background (orientation only, not assignments)
I worked through Android clipboard limits in another session. Conclusions:
- Stock Android has no broadcast for clipboard changes. An app that isn't focused gets no
  OnPrimaryClipChangedListener callbacks.
- READ_CLIPBOARD_IN_BACKGROUND can't be granted to a normal app (signature|role).
  Background capture is possible only through: a platform-signed or priv-app build on KATIM
  firmware (the only product-grade route), being the default keyboard, Shizuku (runs as
  the shell user, which holds the permission), the KDE Connect trick (READ_LOGS +
  SYSTEM_ALERT_WINDOW over adb, watch logcat for the denial line, flash a focus-grabbing
  activity; fragile), or root.
- An app can read the clipboard in the foreground once its window has focus. Read in
  onWindowFocusChanged(true), not onResume.
- Decision: copies made inside the app send automatically; anything else stays an explicit
  tap. This task is the first half. Background capture is OUT OF SCOPE.
- Future idea, not this task: a relay server after one-time pairing. Hardware-backed
  device keys, an end-to-end encrypted mailbox with a short expiry (not "announce, then
  pull from the sender" for text), push to wake the receiver, and a member list signed by
  an existing device so the server can't add a fake device.
```

**Status vs that wrap-up:** the sample/chat clipboard path and the “future” relay idea
were implemented afterward (`a290552` … `d9eeb16`). MemPalace was not re-checkpointed
with a later DeviceLink stop note; current pending work is the repo’s own remaining
lists below.

### Local reflog only (not on `main`)

Briefly committed then removed (intentionally superseded by `a3c58de` / AGENTS rule
to keep operator hosting out of the repo):

- `84980af` feat: add private macOS relay service and sandbox deployment tools
- `fd31be4` chore: keep operator deployment configuration local

Those objects still exist in the local reflog if recovery is needed. They were **not**
pushed to the public GitHub `main` tip because they contain operator-specific hosting
recipe content.

## Pending (from repo docs at HEAD)

### From `docs/relay-proposal.md` — Remaining work

- Deployment owner, public URL/certificate, operations and retention monitoring.
- Public HTTPS/cellular and independent-network validation, then matched
  screen-off/idle/transfer battery and delivery-latency measurements.
- FCM or a suitable vendor push adapter where available, with explicit lifecycle
  and consent design; no instant invisible-delivery promise.
- Key rotation/recovery, forward-secrecy improvements and independent security review.
- Chunk-level transfer resume and larger-file strategy. Image imports now prune
  unreferenced assets older than 24 hours while preserving current chat/clipboard
  references, the fixture and recent assets. New imports are refused once stored
  images reach the 512 MiB pre-copy admission threshold after cleanup.

### From `docs/validation.md` — Remaining checks

- End-to-end camera QR scan with physically aligned phones.
- Sustained both-screen-off/Doze transfers, older Android versions and more OEMs.
- Idle/transfer battery measurements. USB-powered testing cannot establish drain.
- Public HTTPS endpoint/certificate and cellular or independent-network delivery.
- Image retention under quota pressure, relay clipboard expiry races,
  and receiver restart/interrupted-network scenarios beyond the mailbox test above.
- The 30-second stalled socket-write watchdog is covered by implementation review
  and compilation; forced socket-stall behavior has not been measured on hardware.

Pending clipboard/chat checks (same file):

- Isolated no-replay-after-reconnect check, repeated background delivery under
  sustained Doze, and selection Copy from the composer remaining local.
- Forced OS write failure and 10-second receiver / 15-second sender timeout tests
  on hardware. These paths have contract tests/review, not device fault injection.
- History capacity eviction and storage-failure UX on hardware.

### Product boundary still out of scope

Background clipboard capture on stock Android (see MemPalace wrap-up). Firmware /
priv-app / keyboard / Shizuku / root routes only.

## Resume checklist

1. Clone or pull `https://github.com/tp9imka/device-link`.
2. Create git-ignored `local.properties` with `sdk.dir`.
3. `./gradlew check` and server tests per `server/README.md`.
4. Prefer next work from validation/relay remaining lists; do not claim two-phone
   evidence from JVM tests alone.
5. Keep operator domains, tunnels, enrollment tokens and `.env` out of git (see
   `AGENTS.md`).

## Migration notes

- Internal remote kept as `oryx` for reference; `origin` points at GitHub.
- Ignored build artifacts (`.gradle/`, `**/build/`, `artifacts/`, `local.properties`)
  were not migrated; they are disposable.
