"""Request body read limits: size-scaled deadline and idle timeout instead of a fixed total (F7)."""
import asyncio
import base64
import time

import pytest

from relay.app import create_app
from relay.config import Settings
from relay.store import Store
from test_relay import Device, encoded, envelope

NOW = 1_800_000_000_000
# Grace 0.3 s, 10 kB/s minimum, 0.3 s idle: a 20 kB body may take 0.3 + 2.0 = 2.3 s.
LIMITS = {"body_grace_ms": 300, "body_min_rate": 10_000, "body_idle_ms": 300}
SIZE = 20_000


def linked_relay(settings):
    app = create_app(settings, lambda: NOW)
    sender, recipient = Device(), Device()
    with Store(settings).transaction() as db:
        for device in (sender, recipient):
            db.execute("INSERT INTO devices(id,key,created,last_seen) VALUES(?,?,?,?)", (device.id, device.der, NOW, NOW))
        db.execute("INSERT INTO peers(recipient,sender,created) VALUES(?,?,?)", (recipient.id, sender.id, NOW))
    return app, sender, recipient


@pytest.fixture
def relay(tmp_path):
    return linked_relay(Settings(database=tmp_path / "relay.sqlite3", **LIMITS))


def upload_body(sender, recipient):
    # Padded so the whole JSON envelope is SIZE bytes.
    base = encoded(envelope(sender, recipient, NOW, ciphertext=""))
    padding = SIZE - len(base)
    raw = b"c" * (padding * 3 // 4)
    body = encoded(envelope(sender, recipient, NOW, ciphertext=base64.b64encode(raw).decode()))
    assert abs(len(body) - SIZE) < 8
    return body


def post(app, device, body, schedule, content_length=True):
    """Sends POST /v1/messages through ASGI, yielding (delay, chunk) pairs from [schedule]."""
    headers = device.headers("POST", "/v1/messages", body, NOW)
    raw_headers = [(key.lower().encode(), value.encode()) for key, value in headers.items()]
    raw_headers.append((b"content-type", b"application/json"))
    if content_length:
        raw_headers.append((b"content-length", str(len(body)).encode()))
    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST", "scheme": "http",
             "path": "/v1/messages", "raw_path": b"/v1/messages", "query_string": b"", "root_path": "",
             "headers": raw_headers, "client": ("127.0.0.1", 1), "server": ("testserver", 80)}
    steps = iter(schedule(body))
    status = []

    async def receive():
        try:
            delay, chunk = next(steps)
        except StopIteration:
            await asyncio.sleep(60)
            return {"type": "http.disconnect"}
        await asyncio.sleep(delay)
        return {"type": "http.request", "body": chunk, "more_body": True}

    async def receive_with_end():
        message = await receive()
        if message.get("body") == b"":
            message["more_body"] = False
        return message

    async def send(message):
        if message["type"] == "http.response.start":
            status.append(message["status"])

    async def run():
        await app(scope, receive_with_end, send)

    started = time.monotonic()
    asyncio.run(run())
    return status[0], time.monotonic() - started


def chunks(body, size, delay, stop_after=None, stall=0.0):
    steps = []
    for index, offset in enumerate(range(0, len(body), size)):
        pause = stall if stop_after is not None and index == stop_after else delay
        steps.append((pause, body[offset:offset + size]))
    steps.append((0, b""))
    return steps


def test_slow_but_steady_upload_longer_than_grace_and_idle_passes(relay):
    app, sender, recipient = relay
    # 20 kB at ~20 kB/s: about 1 s, three times the idle limit and the grace, above the minimum rate.
    status, elapsed = post(app, sender, upload_body(sender, recipient), lambda body: chunks(body, 1_000, 0.05))
    assert status == 201
    assert elapsed > 0.9


def test_stalled_upload_is_cut_off_by_the_idle_limit(relay):
    app, sender, recipient = relay
    status, elapsed = post(app, sender, upload_body(sender, recipient),
                           lambda body: chunks(body, 1_000, 0.02, stop_after=5, stall=5.0))
    assert status == 408
    # Well before the 2.3 s size deadline: the idle limit fired.
    assert elapsed < 1.5


def test_drip_slower_than_the_minimum_rate_is_cut_off(relay):
    app, sender, recipient = relay
    # 500 B every 0.1 s = 5 kB/s: every gap is under the idle limit, but it would take 4 s.
    status, elapsed = post(app, sender, upload_body(sender, recipient), lambda body: chunks(body, 500, 0.1))
    assert status == 408
    assert 2.0 < elapsed < 3.5


def test_without_content_length_the_route_maximum_sets_the_deadline(tmp_path):
    app, sender, recipient = linked_relay(Settings(database=tmp_path / "relay.sqlite3", body_limit=60_000, **LIMITS))
    # 5 kB/s takes 4 s: past a 20 kB deadline (2.3 s), inside the 60 kB one (6.3 s).
    status, _ = post(app, sender, upload_body(sender, recipient), lambda body: chunks(body, 500, 0.1), content_length=False)
    assert status == 201


def test_declared_body_over_the_limit_is_refused_before_reading(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", body_limit=10_000, **LIMITS)
    app = create_app(settings, lambda: NOW)
    sender, recipient = Device(), Device()
    status, elapsed = post(app, sender, upload_body(sender, recipient), lambda body: chunks(body, 1_000, 0.2))
    assert status == 413
    assert elapsed < 0.2



def test_cut_off_and_oversized_uploads_are_counted_for_the_operator(tmp_path):
    from fastapi.testclient import TestClient

    from relay.admin import hash_password
    from test_link_v2 import login

    settings = Settings(database=tmp_path / "relay.sqlite3", admin_password_hash=hash_password("correct horse", iterations=1000),
                        **LIMITS)
    app, sender, recipient = linked_relay(settings)
    for _ in range(3):
        assert post(app, sender, upload_body(sender, recipient), lambda body: chunks(body, 1_000, 0.02, stop_after=2, stall=5.0))[0] == 408
    oversized = encoded(envelope(sender, recipient, NOW, ciphertext=base64.b64encode(b"x" * 30 * 1024 * 1024).decode()))
    assert post(app, sender, oversized, lambda body: [(0, body), (0, b"")])[0] == 413
    with TestClient(app) as client:
        login(client)
        overview = client.get("/admin/api/overview").json()
        assert overview["runtime"]["counters"]["body_timeouts"] == 3
        assert overview["runtime"]["counters"]["body_too_large"] == 1
        assert "uploads_timing_out" in {alert["code"] for alert in overview["alerts"]}
        metrics = client.get("/admin/api/metrics").text
        assert "devicelink_body_timeouts_total 3" in metrics
        # Counts only: no device identifier reaches the counters or the metrics.
        assert sender.id not in metrics and sender.id[:12] not in metrics
