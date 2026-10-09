"""Operator dashboard: devices, links ("sandboxes"), pending encrypted items and activity.

The relay holds only end-to-end encrypted envelopes, so the dashboard shows metadata
(sizes, timing, routing) and ciphertext fingerprints. Nobody here can read clipboard content.
"""
import base64
import csv
import hashlib
import io
import json
import hmac
import secrets
import statistics
import time
from urllib.parse import parse_qs

from fastapi import Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from starlette.concurrency import run_in_threadpool

from . import __version__
from .auth import identifier, uuid_string
from .dashboard import dashboard_page, login_page
from .errors import RelayError

COOKIE = "dl_admin"


def hash_password(password, iterations=600_000, salt=None):
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    return f"pbkdf2_sha256${iterations}${base64.b64encode(salt).decode()}${base64.b64encode(digest).decode()}"


def verify_password(settings, password):
    if settings.admin_password_hash:
        try:
            algorithm, iterations, salt, expected = settings.admin_password_hash.split("$")
            if algorithm != "pbkdf2_sha256":
                return False
            digest = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(salt), int(iterations))
            return hmac.compare_digest(digest, base64.b64decode(expected))
        except (ValueError, TypeError):
            return False
    return bool(settings.admin_password) and hmac.compare_digest(password.encode(), settings.admin_password.encode())


class Sessions:
    def __init__(self, settings):
        self.settings = settings
        self.secret = (settings.session_secret or secrets.token_hex(32)).encode()
        self.failures = []

    def _mac(self, payload):
        return hmac.new(self.secret, payload.encode(), hashlib.sha256).hexdigest()

    def issue(self):
        expiry = int(time.time()) + self.settings.session_hours * 3600
        nonce = secrets.token_hex(16)
        payload = f"{expiry}.{nonce}.{self.settings.admin_username}"
        return f"{expiry}.{nonce}.{self._mac(payload)}"

    def nonce(self, cookie):
        try:
            expiry, nonce, mac = (cookie or "").split(".")
            payload = f"{expiry}.{nonce}.{self.settings.admin_username}"
            if int(expiry) > time.time() and hmac.compare_digest(mac, self._mac(payload)):
                return nonce
        except ValueError:
            pass
        return None

    def csrf(self, nonce):
        return self._mac("csrf." + nonce)[:32]

    def throttled(self):
        now = time.time()
        self.failures = [moment for moment in self.failures if now - moment < 300]
        return len(self.failures) >= 10


def short(device):
    return device[:12] if device else None


def install_admin(app):
    settings = app.state.settings
    store = app.state.store
    runtime = app.state.runtime
    clock = app.state.clock
    sessions = Sessions(settings)

    def session(request):
        if not settings.admin_enabled:
            raise RelayError(404, "not_found")
        nonce = sessions.nonce(request.cookies.get(COOKIE))
        if nonce is None:
            raise RelayError(401, "admin_login_required")
        return nonce

    def mutating(request):
        nonce = session(request)
        supplied = request.headers.get("x-csrf-token", "")
        if not hmac.compare_digest(supplied, sessions.csrf(nonce)):
            raise RelayError(403, "csrf")

    def secure(request):
        return request.url.scheme == "https" or request.headers.get("x-forwarded-proto") == "https"

    def base_url(request):
        if settings.public_url:
            return settings.public_url.rstrip("/")
        scheme = "https" if secure(request) else request.url.scheme
        return f"{scheme}://{request.headers.get('host', request.url.netloc)}"

    # ----- pages -------------------------------------------------------------------------------

    @app.get("/admin", response_class=HTMLResponse)
    async def admin_page(request: Request):
        if not settings.admin_enabled:
            return Response(status_code=404)
        nonce = sessions.nonce(request.cookies.get(COOKIE))
        page_nonce = secrets.token_urlsafe(16)
        if nonce is None:
            return login_page(page_nonce, error=request.query_params.get("error") == "1")
        return dashboard_page(page_nonce, sessions.csrf(nonce), settings.admin_username)

    @app.post("/admin/login")
    async def admin_login(request: Request):
        if not settings.admin_enabled:
            return Response(status_code=404)
        if sessions.throttled():
            return RedirectResponse("/admin?error=1", status_code=303)
        body = (await request.body())[:4096].decode("utf-8", "replace")
        form = parse_qs(body)
        username = (form.get("username") or [""])[0]
        password = (form.get("password") or [""])[0]
        valid = hmac.compare_digest(username.encode(), settings.admin_username.encode())
        valid = await run_in_threadpool(verify_password, settings, password) and valid
        if not valid:
            sessions.failures.append(time.time())
            return RedirectResponse("/admin?error=1", status_code=303)
        response = RedirectResponse("/admin", status_code=303)
        response.set_cookie(COOKIE, sessions.issue(), max_age=settings.session_hours * 3600, httponly=True,
                            secure=secure(request), samesite="strict", path="/admin")
        await run_in_threadpool(_event, store, clock(), "admin_login")
        return response

    @app.post("/admin/logout")
    async def admin_logout(request: Request):
        mutating(request)
        response = JSONResponse({"ok": True})
        response.delete_cookie(COOKIE, path="/admin")
        return response

    # ----- read API ----------------------------------------------------------------------------

    @app.get("/admin/api/overview")
    async def overview(request: Request):
        session(request)
        data = await run_in_threadpool(_overview, store, settings, clock(), set(runtime.polling))
        data["runtime"] = {"version": __version__, "uptimeSeconds": int(time.time() - runtime.started),
                           "counters": dict(runtime.counters)}
        data["alerts"] = alerts(data, settings, recent_rejections(runtime))
        return data

    @app.get("/admin/api/export/{kind}")
    async def export(kind: str, request: Request):
        session(request)
        if kind not in ("devices", "events"):
            raise RelayError(404, "not_found")
        fmt = request.query_params.get("format", "json")
        if fmt not in ("json", "csv"):
            raise RelayError(400, "invalid_format")
        if kind == "devices":
            rows = await run_in_threadpool(_devices, store, clock(), set(runtime.polling))
        else:
            rows = await run_in_threadpool(_events, store, settings.max_events)
        await run_in_threadpool(_event, store, clock(), "admin_export")
        name = f"devicelink-{kind}-{clock() // 1000}.{fmt}"
        headers = {"Content-Disposition": f'attachment; filename="{name}"'}
        if fmt == "json":
            return Response(json.dumps(rows, indent=1), media_type="application/json", headers=headers)
        return Response(to_csv(rows), media_type="text/csv; charset=utf-8", headers=headers)

    async def metrics_text():
        data = await run_in_threadpool(_overview, store, settings, clock(), set(runtime.polling))
        return Response(prometheus(data, runtime, recent_rejections(runtime)), media_type="text/plain; version=0.0.4")

    @app.get("/metrics")
    async def metrics(request: Request):
        if not settings.metrics_token:
            raise RelayError(404, "not_found")
        supplied = request.headers.get("authorization", "")
        if not hmac.compare_digest(supplied.encode(), f"Bearer {settings.metrics_token}".encode()):
            raise RelayError(401, "metrics_auth_required")
        return await metrics_text()

    @app.get("/admin/api/metrics")
    async def admin_metrics(request: Request):
        session(request)
        return await metrics_text()

    @app.get("/admin/api/devices")
    async def devices(request: Request):
        session(request)
        return await run_in_threadpool(_devices, store, clock(), set(runtime.polling))

    @app.get("/admin/api/sandboxes")
    async def sandboxes(request: Request):
        session(request)
        return await run_in_threadpool(_sandboxes, store, clock())

    @app.get("/admin/api/pairings")
    async def pairings(request: Request):
        session(request)
        return await run_in_threadpool(_pairings, store, clock())

    @app.get("/admin/api/events")
    async def events(request: Request):
        session(request)
        try:
            limit = min(500, max(1, int(request.query_params.get("limit", "200"))))
        except ValueError:
            raise RelayError(400, "invalid_limit") from None
        return await run_in_threadpool(_events, store, limit)

    @app.get("/admin/api/messages/{message}")
    async def message(message: str, request: Request):
        session(request)
        if not uuid_string(message):
            raise RelayError(400, "invalid_message")
        return await run_in_threadpool(_message, store, message)

    @app.get("/admin/api/setup")
    async def setup(request: Request):
        session(request)
        base = base_url(request)
        token = settings.enrollment_token
        link = f"{base}/setup#v2." + base64.urlsafe_b64encode(token.encode()).rstrip(b"=").decode()
        try:
            import segno
            svg = segno.make(link, error="m").svg_inline(scale=5, dark="#111", light="#fff", border=2)
        except ImportError:
            svg = None
        return {"relayUrl": base, "setupLink": link, "qrSvg": svg, "enrollment": "token" if token else "open",
                "maxLifetimeSeconds": settings.max_ttl_ms // 1000, "pushEnabled": settings.apns_enabled}

    # ----- actions -----------------------------------------------------------------------------

    async def json_body(request):
        import json
        try:
            value = json.loads((await request.body())[:4096] or b"{}")
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except ValueError:
            raise RelayError(400, "invalid_json") from None

    @app.post("/admin/api/devices/{device}/label")
    async def label(device: str, request: Request):
        mutating(request)
        value = (await json_body(request)).get("label", "")
        if not identifier(device) or not isinstance(value, str) or len(value) > 48 or any(ord(c) < 32 for c in value):
            raise RelayError(400, "invalid_label")
        await run_in_threadpool(_update_device, store, device, "label", value.strip())
        return {"ok": True}

    @app.post("/admin/api/devices/{device}/block")
    async def block(device: str, request: Request):
        mutating(request)
        blocked = bool((await json_body(request)).get("blocked", True))
        if not identifier(device):
            raise RelayError(400, "invalid_device")
        await run_in_threadpool(_update_device, store, device, "blocked", 1 if blocked else 0)
        await run_in_threadpool(_event, store, clock(), "device_blocked" if blocked else "device_unblocked", device)
        return {"ok": True}

    @app.delete("/admin/api/devices/{device}")
    async def remove_device(device: str, request: Request):
        mutating(request)
        if not identifier(device):
            raise RelayError(400, "invalid_device")
        await run_in_threadpool(_remove_device, store, device, clock())
        return {"ok": True}

    @app.delete("/admin/api/messages/{message}")
    async def remove_message(message: str, request: Request):
        mutating(request)
        if not uuid_string(message):
            raise RelayError(400, "invalid_message")
        await run_in_threadpool(_remove_message, store, message, clock())
        return {"ok": True}

    @app.delete("/admin/api/links/{first}/{second}")
    async def remove_link(first: str, second: str, request: Request):
        mutating(request)
        if not identifier(first) or not identifier(second):
            raise RelayError(400, "invalid_device")
        await run_in_threadpool(_remove_link, store, first, second, clock())
        return {"ok": True}


# ----- queries (worker threads) ----------------------------------------------------------------

ALERT_WINDOW_SECONDS = 600
REJECTION_ALERT = 50


def recent_rejections(runtime):
    cutoff = time.monotonic() - ALERT_WINDOW_SECONDS
    return sum(1 for at in runtime.rejections if at >= cutoff)


def alerts(overview, settings, rejections):
    """Operator warnings derived from metadata only. Levels: warn, bad."""
    result = []
    sandbox = overview["sandbox"]
    if sandbox["capacityBytes"]:
        used = sandbox["bytes"] / sandbox["capacityBytes"]
        if used >= 0.8:
            result.append({"level": "bad" if used >= 0.95 else "warn", "code": "storage_near_cap",
                           "message": f"Sandbox storage at {used:.0%} of the configured cap; new items may be refused."})
    if overview["sandbox"]["fullMailboxes"]:
        result.append({"level": "warn", "code": "mailbox_full",
                       "message": f"{overview['sandbox']['fullMailboxes']} device mailbox(es) at the item limit; senders are waiting."})
    if rejections >= REJECTION_ALERT:
        result.append({"level": "warn", "code": "repeated_rejections",
                       "message": f"{rejections} authentication failures or rate limits in the last 10 minutes."})
    devices = overview["devices"]["total"]
    if settings.max_devices and devices >= settings.max_devices * 0.9:
        result.append({"level": "warn", "code": "devices_near_cap",
                       "message": f"{devices} of {settings.max_devices} device registrations used."})
    counters = overview.get("runtime", {}).get("counters", {})
    if counters.get("push_failed", 0) >= 5 and counters.get("push_failed", 0) > counters.get("push_sent", 0):
        result.append({"level": "warn", "code": "push_failing",
                       "message": "Most APNs wake-ups since start failed; check the APNs key, team and topic."})
    if overview["config"]["enrollment"] == "open":
        result.append({"level": "warn", "code": "open_enrollment",
                       "message": "No enrollment token is set: any device can register on this relay."})
    return result


def to_csv(rows):
    buffer = io.StringIO()
    if not rows:
        return ""
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0].keys()))
    writer.writeheader()
    for row in rows:
        writer.writerow({key: " ".join(value) if isinstance(value, list) else value for key, value in row.items()})
    return buffer.getvalue()


def prometheus(overview, runtime, rejections):
    lines = []

    def metric(name, kind, help_text, value):
        lines.extend((f"# HELP devicelink_{name} {help_text}", f"# TYPE devicelink_{name} {kind}", f"devicelink_{name} {value}"))

    metric("devices_registered", "gauge", "Registered device identities.", overview["devices"]["total"])
    metric("devices_online", "gauge", "Devices currently long-polling.", overview["devices"]["online"])
    metric("devices_active_24h", "gauge", "Devices seen in the last 24 hours.", overview["devices"]["active24h"])
    metric("devices_blocked", "gauge", "Blocked devices.", overview["devices"]["blocked"])
    metric("links_mutual", "gauge", "Mutually linked device pairs.", overview["links"]["mutual"])
    metric("pairings_open", "gauge", "Open QR pairing rendezvous.", overview["links"]["openPairings"])
    metric("sandbox_items", "gauge", "Encrypted items waiting for delivery.", overview["sandbox"]["pending"])
    metric("sandbox_bytes", "gauge", "Bytes of encrypted items waiting.", overview["sandbox"]["bytes"])
    metric("sandbox_capacity_bytes", "gauge", "Configured global sandbox byte cap.", overview["sandbox"]["capacityBytes"])
    metric("sandbox_full_mailboxes", "gauge", "Mailboxes at the item limit.", overview["sandbox"]["fullMailboxes"])
    for kind in ("sent", "delivered", "expired"):
        metric(f"items_{kind}_24h", "gauge", f"Items {kind} in the last 24 hours.", overview["last24h"][kind])
    if overview["last24h"]["medianLatencyMs"] is not None:
        metric("delivery_latency_median_seconds", "gauge", "Median upload-to-acknowledge time, last 24 hours.",
               overview["last24h"]["medianLatencyMs"] / 1000)
    for name, value in runtime.counters.items():
        metric(f"{name}_total", "counter", f"{name.replace('_', ' ').capitalize()} since start.", value)
    metric("rejections_10m", "gauge", "Authentication failures and rate limits in the last 10 minutes.", rejections)
    metric("uptime_seconds", "gauge", "Seconds since the relay started.", int(time.time() - runtime.started))
    return "\n".join(lines) + "\n"


def _event(store, now, kind, device=None):
    with store.transaction() as db:
        store.event(db, now, kind, device)


def _update_device(store, device, column, value):
    assert column in ("label", "blocked")
    with store.transaction() as db:
        if not db.execute(f"UPDATE devices SET {column}=? WHERE id=?", (value, device)).rowcount:
            raise RelayError(404, "device_not_found")


def _remove_device(store, device, now):
    with store.transaction() as db:
        if not db.execute("DELETE FROM devices WHERE id=?", (device,)).rowcount:
            raise RelayError(404, "device_not_found")
        db.execute("DELETE FROM peers WHERE recipient=? OR sender=?", (device, device))
        db.execute("DELETE FROM messages WHERE recipient=? OR sender=?", (device, device))
        db.execute("DELETE FROM pairings WHERE creator=? OR joiner=?", (device, device))
        store.event(db, now, "device_removed", device)


def _remove_message(store, message, now):
    with store.transaction() as db:
        row = db.execute("DELETE FROM messages WHERE id=? RETURNING recipient, sender, length(body)", (message,)).fetchone()
        if row:
            store.event(db, now, "purged", row[0], row[1], row[2])


def _remove_link(store, first, second, now):
    with store.transaction() as db:
        db.execute("DELETE FROM peers WHERE (recipient=? AND sender=?) OR (recipient=? AND sender=?)", (first, second, second, first))
        db.execute("DELETE FROM messages WHERE (recipient=? AND sender=?) OR (recipient=? AND sender=?)", (first, second, second, first))
        store.event(db, now, "link_removed", first, second)


def _overview(store, settings, now, polling):
    day = now - 24 * 3600 * 1000
    with store.transaction() as db:
        total, active, blocked = db.execute(
            "SELECT count(*), coalesce(sum(last_seen>?),0), coalesce(sum(blocked),0) FROM devices", (day,)).fetchone()
        known = {row[0] for row in db.execute("SELECT id FROM devices")}
        pending, pending_bytes, next_expiry = db.execute(
            "SELECT count(*), coalesce(sum(length(body)),0), min(expires) FROM messages").fetchone()
        mutual = db.execute("SELECT count(*) FROM peers a JOIN peers b ON a.recipient=b.sender AND a.sender=b.recipient "
                            "WHERE a.recipient < a.sender").fetchone()[0]
        one_way = db.execute("SELECT count(*) FROM peers a WHERE NOT EXISTS (SELECT 1 FROM peers b "
                             "WHERE b.recipient=a.sender AND b.sender=a.recipient)").fetchone()[0]
        open_pairings = db.execute("SELECT count(*) FROM pairings WHERE expires>?", (now,)).fetchone()[0]
        full = db.execute("SELECT count(*) FROM (SELECT recipient FROM messages GROUP BY recipient HAVING count(*)>=?)",
                          (settings.mailbox_count,)).fetchone()[0]
        rows = db.execute("SELECT at, kind, value FROM events WHERE at>? AND kind IN ('sent','delivered','expired')", (day,)).fetchall()
    hours = [{"hour": day + index * 3600 * 1000, "sent": 0, "delivered": 0, "expired": 0} for index in range(24)]
    latencies = []
    totals = {"sent": 0, "delivered": 0, "expired": 0}
    for at, kind, value in rows:
        index = min(23, max(0, (at - day) // (3600 * 1000)))
        hours[index][kind] += 1
        totals[kind] += 1
        if kind == "delivered" and value is not None:
            latencies.append(value)
    latencies.sort()
    return {
        "now": now,
        "devices": {"total": total, "online": len(polling & known), "active24h": active, "blocked": blocked},
        "links": {"mutual": mutual, "oneWay": one_way, "openPairings": open_pairings},
        "sandbox": {"pending": pending, "bytes": pending_bytes, "nextExpiry": next_expiry, "fullMailboxes": full,
                    "capacityBytes": settings.global_bytes, "maxLifetimeSeconds": settings.max_ttl_ms // 1000},
        "last24h": dict(totals, medianLatencyMs=int(statistics.median(latencies)) if latencies else None,
                        p95LatencyMs=latencies[int(len(latencies) * 0.95) - 1] if len(latencies) >= 20 else None,
                        hourly=hours),
        "config": {"enrollment": "token" if settings.enrollment_token else "open", "push": settings.apns_enabled,
                   "mailboxCount": settings.mailbox_count, "mailboxBytes": settings.mailbox_bytes,
                   "maxPollWait": settings.max_poll_wait, "maxDevices": settings.max_devices},
    }


def _devices(store, now, polling):
    with store.transaction() as db:
        peers = {}
        for recipient, sender in db.execute("SELECT recipient, sender FROM peers"):
            peers.setdefault(recipient, set()).add(sender)
        pending = {row[0]: (row[1], row[2]) for row in db.execute(
            "SELECT recipient, count(*), sum(length(body)) FROM messages GROUP BY recipient")}
        rows = db.execute("SELECT id, label, platform, model, app_version, created, last_seen, blocked, push_token IS NOT NULL, "
                          "sent_count, sent_bytes, received_count, received_bytes FROM devices ORDER BY last_seen DESC").fetchall()
    result = []
    for (device, label, platform, model, version, created, last_seen, blocked, push,
         sent_count, sent_bytes, received_count, received_bytes) in rows:
        allowed = peers.get(device, set())
        linked = sorted(peer for peer in allowed if device in peers.get(peer, set()))
        count, size = pending.get(device, (0, 0))
        result.append({"id": device, "short": short(device), "label": label, "platform": platform, "model": model,
                       "appVersion": version, "created": created, "lastSeen": last_seen, "online": device in polling,
                       "blocked": bool(blocked), "push": bool(push), "linked": linked, "allows": sorted(allowed),
                       "pending": count, "pendingBytes": size or 0, "sent": sent_count, "sentBytes": sent_bytes,
                       "received": received_count, "receivedBytes": received_bytes})
    return result


def _sandboxes(store, now):
    with store.transaction() as db:
        labels = {row[0]: (row[1], row[2]) for row in db.execute("SELECT id, label, platform FROM devices")}
        pairs = {}

        def entry(a, b):
            key = tuple(sorted((a, b)))
            return pairs.setdefault(key, {"devices": list(key), "allows": [], "created": None, "items": [], "lastActivity": None})

        for recipient, sender, created in db.execute("SELECT recipient, sender, created FROM peers"):
            item = entry(recipient, sender)
            item["allows"].append({"recipient": recipient, "sender": sender})
            item["created"] = min(filter(None, (item["created"], created)), default=created or None)
        for message, sender, recipient, size, created, expires, sequence in db.execute(
                "SELECT id, sender, recipient, length(body), created, expires, sequence FROM messages ORDER BY created"):
            entry(sender, recipient)["items"].append({"id": message, "from": sender, "to": recipient, "size": size,
                                                      "created": created, "expires": expires, "sequence": sequence})
        for a, b, at in db.execute("SELECT device, peer, max(at) FROM events WHERE peer IS NOT NULL GROUP BY device, peer"):
            key = tuple(sorted((a, b)))
            if key in pairs:
                pairs[key]["lastActivity"] = max(filter(None, (pairs[key]["lastActivity"], at)))
    result = []
    for (a, b), item in pairs.items():
        item["names"] = [{"id": device, "short": short(device), "label": labels.get(device, ("", ""))[0],
                          "platform": labels.get(device, ("", ""))[1]} for device in (a, b)]
        item["mutual"] = len(item["allows"]) == 2
        item["pendingBytes"] = sum(entry["size"] for entry in item["items"])
        result.append(item)
    result.sort(key=lambda item: (-(len(item["items"])), -(item["lastActivity"] or 0)))
    return result


def _pairings(store, now):
    with store.transaction() as db:
        rows = db.execute("SELECT id, creator, created, expires, state, joiner FROM pairings WHERE expires>? ORDER BY created DESC", (now,)).fetchall()
    return [{"id": row[0][:8], "creator": row[1], "created": row[2], "expires": row[3], "state": row[4], "joiner": row[5]} for row in rows]


def _events(store, limit):
    with store.transaction() as db:
        rows = db.execute("SELECT at, kind, device, peer, size, value FROM events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return [{"at": at, "kind": kind, "device": device, "peer": peer, "size": size, "value": value}
            for at, kind, device, peer, size, value in rows]


def _message(store, message):
    import json
    with store.transaction() as db:
        row = db.execute("SELECT body, created FROM messages WHERE id=?", (message,)).fetchone()
    if row is None:
        raise RelayError(404, "message_not_found")
    envelope = json.loads(row[0])
    ciphertext = base64.b64decode(envelope["ciphertext"])
    return {
        "id": envelope["id"], "version": envelope["version"], "senderId": envelope["senderId"],
        "recipientId": envelope["recipientId"], "createdAt": envelope["createdAt"], "expiresAt": envelope["expiresAt"],
        "receivedAt": row[1], "sequence": envelope["sequence"], "envelopeBytes": len(row[0]),
        "ciphertextBytes": len(ciphertext), "ciphertextSha256": hashlib.sha256(ciphertext).hexdigest(),
        "ciphertextPreview": ciphertext[:48].hex(), "signaturePreview": envelope["signature"][:24] + "…",
        "encryption": ("HPKE X25519 / HKDF-SHA256 / ChaCha20-Poly1305 sealed to the recipient device key, "
                       "signed by the sender's P-256 device identity" if envelope["version"] == 2 else
                       "HPKE X25519 / HKDF-SHA256 / AES-256-GCM (legacy Android Internet Link)"),
    }
