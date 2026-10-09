# Hosting the relay

The relay is a single FastAPI process with SQLite. Start on your own machine behind a Cloudflare
tunnel, move to a server later; phones only need the public HTTPS URL. Keep real hostnames, tunnel
IDs, tokens and passwords in private configuration, never in this repository.

## 1. Configure

```sh
cd server
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m relay.passwd            # prints an admin password hash
cp config.example.toml ~/devicelink-relay.toml   # private location; edit it
```

Minimum settings: `public_url`, `enrollment_token` (long random string), `admin_password_hash`,
`session_secret` (`python3 -c "import secrets; print(secrets.token_hex(32))"`).

```sh
RELAY_CONFIG=~/devicelink-relay.toml .venv/bin/python -m relay   # http://127.0.0.1:8000
```

## 2. Expose it with a Cloudflare tunnel

**Quick test (random URL):** `cloudflared tunnel --url http://127.0.0.1:8000` prints an
`https://<random>.trycloudflare.com` URL. It changes every run, and linked devices remember the relay
URL, so use it only for a first try.

**Stable (recommended):** create a named tunnel in the Cloudflare dashboard (Zero Trust › Networks ›
Tunnels), add a public hostname on your domain pointing to `http://127.0.0.1:8000` (or
`http://relay:8000` with docker compose), and run it with its token:

```sh
cloudflared tunnel --no-autoupdate run --token "$TUNNEL_TOKEN"
# or: cp .env.example .env (fill in) && docker compose --profile tunnel up -d
```

Notes: Cloudflare closes idle requests after 100 s, so keep `max_poll_wait` ≤ 50 (default). Do not
enable Cloudflare caching or HTML rewriting for the relay host. The relay never needs inbound ports.

## 3. Admin dashboard

Open `https://<your relay>/admin` and sign in with the configured username/password.

- **Overview:** online/registered devices, linked pairs, items waiting, deliveries and expiries over
  24 h, median/p95 delivery time, storage, counters.
- **Devices:** platform, model, app version, last seen, online now, linked peers, traffic, waiting
  items, push. Label, block/unblock, remove.
- **Sandboxes:** each linked pair with its encrypted items (size, age, expiry countdown); inspect the
  envelope metadata and ciphertext fingerprint, purge an item, unlink the pair.
- **Activity:** open pairing codes and a metadata-only event log (registrations, links, uploads,
  deliveries with latency, expiries, admin actions). Retained 7 days.
- **Setup:** enrollment QR for builds without a built-in relay.

## 4. Later: a real server

Use `deploy/devicelink-relay.service` + `deploy/nginx.conf` (or the Docker image) behind your own
TLS. Point the same hostname at it to keep existing links working. One process per database.
