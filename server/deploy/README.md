# Deploying the relay on a Linux VM

A generic recipe for one small VM (Debian/Ubuntu shown; any systemd distribution works). Replace
`relay.example.org` with your hostname. Keep your real hostname, tokens and keys out of git.

## 1. Install

```sh
sudo useradd --system --home /opt/devicelink-relay --shell /usr/sbin/nologin devicelink-relay
sudo apt install -y python3 python3-venv nginx certbot python3-certbot-nginx
sudo git clone <this repository> /opt/devicelink-src
sudo cp -r /opt/devicelink-src/server /opt/devicelink-relay
sudo python3 -m venv /opt/devicelink-relay/.venv
sudo /opt/devicelink-relay/.venv/bin/pip install -r /opt/devicelink-relay/requirements.txt
```

The relay needs Python 3.11+ (`tomllib`); the Docker image pins the tested version.

## 2. Configure

```sh
sudo install -d -m 700 /etc/devicelink-relay
sudo /opt/devicelink-relay/.venv/bin/python -m relay.passwd     # prints the admin hash
sudo tee /etc/devicelink-relay/environment >/dev/null <<'CONF'
RELAY_PUBLIC_URL=https://relay.example.org
RELAY_ENROLLMENT_TOKEN=<long random string>
RELAY_ADMIN_PASSWORD_HASH=<hash from relay.passwd>
RELAY_SESSION_SECRET=<openssl rand -hex 32>
RELAY_METRICS_TOKEN=<optional; enables /metrics>
CONF
sudo chmod 600 /etc/devicelink-relay/environment
```

Every key in `../config.example.toml` can be set as `RELAY_<KEY>` here.

## 3. Service, TLS and proxy

```sh
sudo cp /opt/devicelink-relay/deploy/devicelink-relay.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now devicelink-relay
curl -s http://127.0.0.1:8000/health                       # {"status":"ok"}

sudo cp /opt/devicelink-relay/deploy/nginx.conf /etc/nginx/conf.d/devicelink-relay.conf
# edit server_name and certificate paths, then:
sudo certbot certonly --nginx -d relay.example.org
sudo nginx -t && sudo systemctl reload nginx
```

Open only 443 (and 80 for certificate renewal) in the firewall. Never publish port 8000.
Proxy idle timeouts must exceed `max_poll_wait` (50 s); the template uses 75 s.

## 4. Backups

```sh
sudo install -d -o devicelink-relay -g devicelink-relay -m 700 /var/backups/devicelink-relay
sudo cp /opt/devicelink-relay/deploy/devicelink-relay-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now devicelink-relay-backup.timer
```

`python -m relay.backup DEST [--keep N]` uses SQLite's online backup API, so the relay keeps running,
verifies the copy and prunes old ones. Restore: stop the service, copy a backup over
`/var/lib/devicelink-relay/relay.sqlite3`, delete the `-wal`/`-shm` sidecars, start the service.
Backups hold device public keys, link metadata and at most minutes-old ciphertext; encrypt them at rest.

## 5. Monitoring

- `GET /health` (no auth) for uptime checks.
- `GET /metrics` with `Authorization: Bearer $RELAY_METRICS_TOKEN` returns Prometheus text: devices,
  links, waiting items/bytes, full mailboxes, 24 h sent/delivered/expired, delivery latency, request,
  auth-failure, rate-limit and push counters. Counts only; no identifiers or content.
- The dashboard Overview shows alerts: storage near the cap, full mailboxes, repeated authentication
  failures or rate limits, push failures, device cap, open enrollment. It also exports devices and
  events as CSV/JSON.

## 6. Update

```sh
cd /opt/devicelink-src && sudo git pull
sudo rsync -a --delete --exclude .venv --exclude data server/ /opt/devicelink-relay/
sudo /opt/devicelink-relay/.venv/bin/pip install -r /opt/devicelink-relay/requirements.txt
sudo systemctl restart devicelink-relay
```

Restarts drop open long polls; clients reconnect within seconds and waiting items are durable.
Keep the same hostname when moving from a tunnel to a VM so existing links keep working.
