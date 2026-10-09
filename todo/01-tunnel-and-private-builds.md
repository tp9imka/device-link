# 01 · Relay behind a named Cloudflare tunnel, then private app builds

## Goal

The relay runs on the owner's machine, reachable at a stable public HTTPS hostname through a
**named** Cloudflare tunnel, and Android/iOS/Mac builds have that URL and the enrollment token built
in, so the first device needs no setup and every other device links by scanning a QR code.

## Context

- Relay: `server/` (FastAPI + SQLite). Settings: `server/README.md`, `server/config.example.toml`.
- Hosting guide: `docs/wiki/Hosting.md` (quick tunnel, named tunnel, docker compose `tunnel` profile).
- Android private config: `local.properties` keys `devicelink.relayUrl`, `devicelink.enrollmentToken`
  (read by `apps/receiver/build.gradle.kts` and `apps/sample/build.gradle.kts`).
- iOS/Mac private config: `ios/Config/Private.xcconfig` (copy `Private.example.xcconfig`):
  `DL_BUNDLE_PREFIX`, `DEVELOPMENT_TEAM`, `DEVICELINK_RELAY_HOST`; then `cd ios && xcodegen`.

## Rules

- The hostname, tunnel token, enrollment token, admin password and APNs key **never** go into git,
  the wiki or PR text. Keep them in `local.properties`, `Private.xcconfig`, a private `relay.toml` or
  `.env` (all git-ignored; check with `git status` before every commit).
- Do not change protocol lifetimes (5 min client, 10 min relay cap).

## Steps

1. On the owner's machine: Python 3.11+ venv, `pip install -r server/requirements.txt`.
2. Create a private `relay.toml` from `config.example.toml`: `public_url`, a long random
   `enrollment_token`, `admin_password_hash` (`python -m relay.passwd`), `session_secret`
   (`openssl rand -hex 32`), optionally `metrics_token`.
3. Run `RELAY_CONFIG=/private/relay.toml python -m relay` (or docker compose). Check
   `curl http://127.0.0.1:8000/health`.
4. Cloudflare Zero Trust → Networks → Tunnels → create a named tunnel, public hostname → service
   `http://127.0.0.1:8000`. Run `cloudflared tunnel run --token …` as a login service (launchd on macOS)
   so it survives reboots. Do **not** enable Cloudflare caching or Access on `/v1/*` (the apps sign
   requests themselves); you may put Cloudflare Access in front of `/admin` only.
5. Verify from a phone on cellular: `https://<host>/health` → `{"status":"ok"}`, `https://<host>/admin`
   login works, and a long poll is not cut early (the receiver diagnostics "Last error" stays `none`
   over 10 minutes idle).
6. Fill `local.properties` and `ios/Config/Private.xcconfig`, build and install the receiver/sample APKs
   and the iOS/Mac apps. A fresh install must show its link code without asking for a relay URL.
7. Optional: verified app links — set `android_app_links` / `apple_app_ids` in `relay.toml` and the
   associated-domains entitlement (see the comment in `ios/project.yml`), so the camera opens the app.

## Done when

- Two fresh devices link by QR over the public hostname with no manual URL/token entry.
- The relay restarts automatically after a reboot of the owner's machine (tunnel + relay).
- `git status` is clean of private files; nothing operator-specific was committed.
- Record "public HTTPS via named tunnel: verified on <date>" (no hostname) in `docs/validation.md`.
