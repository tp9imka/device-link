import base64
import hashlib
import json
import threading
import time
import uuid
from dataclasses import replace

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from relay.app import create_app
from relay.config import Settings


class Device:
    def __init__(self, curve=None):
        self.key = ec.generate_private_key(curve or ec.SECP256R1())
        self.der = self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.id = hashlib.sha256(self.der).hexdigest()

    def headers(self, method, path, body, now, nonce=None):
        nonce = nonce or str(uuid.uuid4())
        canonical = f"DeviceLink relay request v1\n{method}\n{path}\n{now}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
        return {
            "X-Device-Key": base64.b64encode(self.der).decode(),
            "X-Device-Time": str(now), "X-Device-Nonce": nonce,
            "X-Device-Signature": base64.b64encode(self.key.sign(canonical.encode(), ec.ECDSA(hashes.SHA256()))).decode(),
        }


def encoded(value):
    return json.dumps(value, separators=(",", ":")).encode()


@pytest.fixture
def setup(tmp_path):
    now = [1_800_000_000_000]
    settings = Settings(database=tmp_path / "relay.sqlite3")
    with TestClient(create_app(settings, lambda: now[0])) as client:
        yield settings, now, client


def request(client, device, now, method, path, value=None, body=None, **kwargs):
    body = (encoded(value) if value is not None else b"") if body is None else body
    headers = device.headers(method, path, body, now)
    headers.update(kwargs.pop("headers", {}))
    return client.request(method, path, content=body, headers=headers, **kwargs)


def register(client, device, now):
    response = request(client, device, now, "POST", "/v1/register", {})
    assert response.status_code == 200, response.text
    assert response.json() == {"deviceId": device.id}


def paired(client, now):
    sender, recipient = Device(), Device()
    register(client, sender, now)
    register(client, recipient, now)
    assert request(client, recipient, now, "PUT", f"/v1/peers/{sender.id}", {}).status_code == 204
    return sender, recipient


def envelope(sender, recipient, now, **overrides):
    return {"version": 1, "id": str(uuid.uuid4()), "senderId": sender.id, "recipientId": recipient.id,
            "createdAt": now, "expiresAt": now + 60_000, "sequence": 1,
            "ciphertext": base64.b64encode(b"opaque ciphertext contents").decode(),
            "signature": base64.b64encode(b"opaque e2ee signature").decode(), **overrides}


def test_health_and_authentication_tampering(setup):
    _, now, client = setup
    device = Device()
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/v1/messages").status_code == 401
    headers = device.headers("POST", "/v1/register", b"{}", now[0])
    for altered in (b'{"changed":true}', b" "):
        assert client.post("/v1/register", content=altered, headers=headers).status_code == 401
    headers["X-Device-Key"] = base64.b64encode(Device().der).decode()
    assert client.post("/v1/register", content=b"{}", headers=headers).status_code == 401
    assert request(client, Device(ec.SECP384R1()), now[0], "POST", "/v1/register", {}).status_code == 401
    assert request(client, device, now[0] - 60_001, "POST", "/v1/register", {}).status_code == 401
    register(client, device, now[0])
    headers = device.headers("GET", "/v1/messages?wait=0", b"", now[0])
    assert client.get("/v1/messages?wait=1", headers=headers).status_code == 401
    assert client.delete("/v1/messages?wait=0", headers=headers).status_code in (401, 405)


def test_nonce_replay_survives_restart_and_future_timestamp(setup):
    settings, now, client = setup
    device = Device()
    headers = device.headers("POST", "/v1/register", b"{}", now[0] + 60_000)
    assert client.post("/v1/register", content=b"{}", headers=headers).status_code == 200
    now[0] += 60_001
    with TestClient(create_app(settings, lambda: now[0])) as restarted:
        assert restarted.post("/v1/register", content=b"{}", headers=headers).status_code == 409


def test_registration_enrollment_and_unknown_key(tmp_path):
    device, now = Device(), 1_800_000_000_000
    settings = Settings(database=tmp_path / "relay.sqlite3", enrollment_token="test-enrollment-only")
    with TestClient(create_app(settings, lambda: now)) as client:
        assert request(client, device, now, "POST", "/v1/register", {}).status_code == 403
        assert request(client, device, now, "POST", "/v1/register", {}, headers={"X-Enrollment-Token": "wrong"}).status_code == 403
        assert request(client, device, now, "GET", "/v1/messages").status_code == 403
        assert request(client, device, now, "POST", "/v1/register", {}, headers={"X-Enrollment-Token": "test-enrollment-only"}).status_code == 200


def test_recipient_allowlist_and_sender_binding(setup):
    _, now, client = setup
    sender, recipient, stranger = Device(), Device(), Device()
    for device in (sender, recipient, stranger):
        register(client, device, now[0])
    item = envelope(sender, recipient, now[0])
    assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 403
    assert request(client, recipient, now[0], "PUT", f"/v1/peers/{sender.id}", {}).status_code == 204
    assert request(client, stranger, now[0], "POST", "/v1/messages", item).status_code == 403
    assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 201
    assert request(client, stranger, now[0], "GET", "/v1/messages").json() == []
    assert request(client, sender, now[0], "GET", "/v1/messages").json() == []
    assert request(client, stranger, now[0], "DELETE", f'/v1/messages/{item["id"]}').status_code == 204
    assert request(client, recipient, now[0], "GET", "/v1/messages").json() == [item]


def test_durable_delivery_ack_and_exact_retry_tombstone(setup):
    settings, now, client = setup
    sender, recipient = paired(client, now[0])
    item = envelope(sender, recipient, now[0])
    assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 201
    with TestClient(create_app(settings, lambda: now[0])) as restarted:
        assert request(restarted, recipient, now[0], "GET", "/v1/messages").json() == [item]
        assert request(restarted, sender, now[0], "POST", "/v1/messages", item).status_code == 200
        assert request(restarted, sender, now[0], "POST", "/v1/messages", dict(item, sequence=2)).status_code == 409
        assert request(restarted, recipient, now[0], "DELETE", f'/v1/messages/{item["id"]}').status_code == 204
    with TestClient(create_app(settings, lambda: now[0])) as restarted:
        assert request(restarted, sender, now[0], "POST", "/v1/messages", item).status_code == 200
        assert request(restarted, recipient, now[0], "GET", "/v1/messages").json() == []


def test_remove_peer_revokes_and_discards_mail(setup):
    _, now, client = setup
    sender, recipient = paired(client, now[0])
    assert request(client, sender, now[0], "POST", "/v1/messages", envelope(sender, recipient, now[0])).status_code == 201
    assert request(client, recipient, now[0], "DELETE", f"/v1/peers/{sender.id}").status_code == 204
    assert request(client, recipient, now[0], "GET", "/v1/messages").json() == []
    assert request(client, sender, now[0], "POST", "/v1/messages", envelope(sender, recipient, now[0])).status_code == 403


@pytest.mark.parametrize("changes", [
    {"id": "../bad"}, {"sequence": -1}, {"sequence": 0}, {"sequence": True}, {"sequence": 2**63},
    {"version": 3}, {"version": True},
    {"createdAt": 1_800_000_060_001}, {"expiresAt": 1_800_000_000_000},
    {"expiresAt": 1_800_086_400_001}, {"expiresAt": 1_800_000_600_001}, {"ciphertext": "not base64"}, {"signature": ""},
    {"unknown": "field"}, {"recipientId": "not a fingerprint"},
])
def test_malformed_envelope_rejected_without_payload_echo(setup, changes):
    _, now, client = setup
    sender, recipient = paired(client, now[0])
    response = request(client, sender, now[0], "POST", "/v1/messages", envelope(sender, recipient, now[0], **changes))
    assert response.status_code == 400
    assert set(response.json()) == {"error"}


def test_expiry_on_read_and_restart(setup):
    settings, now, client = setup
    sender, recipient = paired(client, now[0])
    item = envelope(sender, recipient, now[0], expiresAt=now[0] + 1)
    assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 201
    now[0] += 1
    with TestClient(create_app(settings, lambda: now[0])) as restarted:
        assert request(restarted, recipient, now[0], "GET", "/v1/messages").json() == []
        assert request(restarted, sender, now[0], "POST", "/v1/messages", item).status_code == 400


def test_count_quota_and_ack_releases_capacity(setup):
    _, now, client = setup
    sender, recipient = paired(client, now[0])
    items = [envelope(sender, recipient, now[0], sequence=index + 1) for index in range(21)]
    for item in items[:20]:
        assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 201
    assert request(client, sender, now[0], "POST", "/v1/messages", items[20]).status_code == 429
    assert request(client, sender, now[0], "POST", "/v1/messages", items[0]).status_code == 200
    assert request(client, recipient, now[0], "DELETE", f'/v1/messages/{items[0]["id"]}').status_code == 204
    assert request(client, sender, now[0], "POST", "/v1/messages", items[20]).status_code == 201


@pytest.mark.parametrize("field,status", [("mailbox_bytes", 429), ("global_bytes", 507)])
def test_byte_quota(tmp_path, field, status):
    settings = replace(Settings(database=tmp_path / "relay.sqlite3"), **{field: 1})
    now = 1_800_000_000_000
    with TestClient(create_app(settings, lambda: now)) as client:
        sender, recipient = paired(client, now)
        assert request(client, sender, now, "POST", "/v1/messages", envelope(sender, recipient, now)).status_code == status


def test_body_limit_and_json_duplicates(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", body_limit=64)
    now = 1_800_000_000_000
    with TestClient(create_app(settings, lambda: now)) as client:
        device = Device()
        register(client, device, now)
        assert request(client, device, now, "POST", "/v1/messages", body=b"x" * 65).status_code == 413
        assert request(client, device, now, "POST", "/v1/messages", body=b'{"id":1,"id":2}').status_code == 400
        assert request(client, device, now, "POST", "/v1/messages", body=b'{"id":NaN}').status_code == 400


def test_persistent_rate_limit(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", requests_per_minute=2)
    now = 1_800_000_000_000
    device = Device()
    with TestClient(create_app(settings, lambda: now)) as client:
        register(client, device, now)
        assert request(client, device, now, "GET", "/v1/messages").status_code == 200
    with TestClient(create_app(settings, lambda: now)) as restarted:
        assert request(restarted, device, now, "GET", "/v1/messages").status_code == 429


def test_long_poll_wakes_on_delivery_and_empty_wait_is_bounded(setup):
    _, now, client = setup
    sender, recipient = paired(client, now[0])
    response = []
    started = threading.Event()

    def poll():
        started.set()
        response.append(request(client, recipient, now[0], "GET", "/v1/messages?wait=3"))

    thread = threading.Thread(target=poll)
    thread.start()
    started.wait()
    time.sleep(0.1)
    item = envelope(sender, recipient, now[0])
    assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 201
    thread.join(timeout=2)
    assert not thread.is_alive()
    assert response[0].json() == [item]
    request(client, recipient, now[0], "DELETE", f'/v1/messages/{item["id"]}')
    before = time.monotonic()
    assert request(client, recipient, now[0], "GET", "/v1/messages?wait=1").json() == []
    assert 0.8 < time.monotonic() - before < 2


def test_excluded_offer_does_not_redeliver_or_spin_and_sequence_order(setup):
    _, now, client = setup
    sender, recipient = paired(client, now[0])
    later = envelope(sender, recipient, now[0], sequence=2)
    earlier = envelope(sender, recipient, now[0], sequence=1)
    for item in (later, earlier):
        assert request(client, sender, now[0], "POST", "/v1/messages", item).status_code == 201
    assert request(client, recipient, now[0], "GET", "/v1/messages").json() == [later, earlier]
    assert request(client, recipient, now[0], "GET", "/v1/messages?limit=1").json() == [later]
    path = f'/v1/messages?wait=1&exclude={earlier["id"]},{later["id"]}'
    before = time.monotonic()
    response = request(client, recipient, now[0], "GET", path)
    assert response.json() == []
    assert response.headers["cache-control"] == "no-store"
    assert time.monotonic() - before >= 0.8
    assert request(client, recipient, now[0], "GET", f'/v1/messages?exclude={earlier["id"]}').json() == [later]
    assert request(client, recipient, now[0], "GET", "/v1/messages?exclude=bad").status_code == 400
    assert request(client, recipient, now[0], "GET", "/v1/messages?wait=0&wait=1").status_code == 400
    assert request(client, recipient, now[0], "GET", "/v1/messages?limit=0").status_code == 400
    assert request(client, recipient, now[0], "GET", "/v1/messages?limit=21").status_code == 400
    assert request(client, recipient, now[0], "GET", f'/v1/messages?limit=1&exclude={later["id"]}').json() == [earlier]


def test_duplicate_auth_headers_and_malformed_key_fail_closed(setup):
    _, now, client = setup
    device = Device()
    headers = device.headers("POST", "/v1/register", b"{}", now[0])
    duplicated = list(headers.items()) + [("X-Device-Time", str(now[0]))]
    assert client.post("/v1/register", content=b"{}", headers=duplicated).status_code == 401
    headers["X-Device-Key"] = base64.b64encode(b"not DER").decode()
    assert client.post("/v1/register", content=b"{}", headers=headers).status_code == 401


def test_concurrent_enqueue_is_atomic_for_final_mailbox_slot(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", mailbox_count=1)
    now = 1_800_000_000_000
    with TestClient(create_app(settings, lambda: now)) as client:
        sender, recipient = paired(client, now)
        responses = []
        barrier = threading.Barrier(2)

        def send():
            barrier.wait()
            responses.append(request(client, sender, now, "POST", "/v1/messages", envelope(sender, recipient, now)).status_code)

        threads = [threading.Thread(target=send) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=3)
            assert not thread.is_alive()
        assert sorted(responses) == [201, 429]
        assert len(request(client, recipient, now, "GET", "/v1/messages").json()) == 1


def test_only_one_pending_poll_per_recipient(setup):
    _, now, client = setup
    device = Device()
    register(client, device, now[0])
    thread = threading.Thread(target=lambda: request(client, device, now[0], "GET", "/v1/messages?wait=1"))
    thread.start()
    time.sleep(0.1)
    response = request(client, device, now[0], "GET", "/v1/messages?wait=1")
    thread.join(timeout=2)
    assert response.status_code == 429


def test_ciphertext_size_limit(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", ciphertext_limit=16)
    now = 1_800_000_000_000
    with TestClient(create_app(settings, lambda: now)) as client:
        sender, recipient = paired(client, now)
        item = envelope(sender, recipient, now, ciphertext=base64.b64encode(b"a" * 17).decode())
        assert request(client, sender, now, "POST", "/v1/messages", item).status_code == 400
