import base64
import threading
import time

import pytest
from fastapi.testclient import TestClient

from relay.admin import hash_password
from relay.app import create_app
from relay.config import Settings
from test_relay import Device, envelope, register, request

NOW = 1_800_000_000_000
SEALED = base64.b64encode(b"n" * 12 + b"opaque sealed bundle" + b"t" * 16).decode()


def pairing_id(n=1):
    return f"{n:032x}"


@pytest.fixture
def relay(tmp_path):
    now = [NOW]
    settings = Settings(database=tmp_path / "relay.sqlite3", admin_password_hash=hash_password("correct horse", iterations=1000))
    with TestClient(create_app(settings, lambda: now[0])) as client:
        yield settings, now, client


def create(client, device, now, pid=None, ttl=300_000):
    return request(client, device, now, "POST", "/v1/pairings", {"id": pid or pairing_id(), "expiresAt": now + ttl})


def test_qr_pairing_rendezvous_links_both_directions(relay):
    _, now, client = relay
    inviter, joiner = Device(), Device()
    register(client, inviter, now[0])
    assert create(client, inviter, now[0]).status_code == 201
    assert request(client, inviter, now[0], "GET", f"/v1/pairings/{pairing_id()}").json() == {"state": "open", "joinerId": None, "sealed": None}
    register(client, joiner, now[0])
    assert request(client, joiner, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED}).status_code == 204
    status = request(client, inviter, now[0], "GET", f"/v1/pairings/{pairing_id()}?wait=5").json()
    assert status == {"state": "joined", "joinerId": joiner.id, "sealed": SEALED}
    assert request(client, inviter, now[0], "POST", f"/v1/pairings/{pairing_id()}/confirm", {"sealed": SEALED}).status_code == 204
    assert request(client, joiner, now[0], "GET", f"/v1/pairings/{pairing_id()}").json()["state"] == "confirmed"
    # Finished rendezvous is removed once the joiner has read the confirmation.
    assert request(client, joiner, now[0], "GET", f"/v1/pairings/{pairing_id()}").status_code == 404
    # Confirmation authorized the joiner to write into the inviter's sandbox.
    assert request(client, joiner, now[0], "POST", "/v1/messages", envelope(joiner, inviter, now[0], version=2)).status_code == 201


def test_pairing_is_single_use_private_and_bounded(relay):
    _, now, client = relay
    inviter, joiner, other = Device(), Device(), Device()
    for device in (inviter, joiner, other):
        register(client, device, now[0])
    create(client, inviter, now[0])
    assert request(client, inviter, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED}).status_code == 400
    assert request(client, other, now[0], "GET", f"/v1/pairings/{pairing_id()}").status_code == 404
    assert request(client, joiner, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED}).status_code == 204
    assert request(client, other, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED}).status_code == 409
    assert request(client, other, now[0], "POST", f"/v1/pairings/{pairing_id()}/confirm", {"sealed": SEALED}).status_code == 404
    assert request(client, joiner, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": "short"}).status_code == 400
    assert create(client, inviter, now[0], ttl=600_001).status_code == 400
    assert create(client, inviter, now[0], pid="ABC").status_code == 400
    for n in range(2, 7):
        assert create(client, inviter, now[0], pid=pairing_id(n)).status_code == 201
    assert create(client, inviter, now[0], pid=pairing_id(9)).status_code == 429


def test_pairing_expires(relay):
    _, now, client = relay
    inviter, joiner = Device(), Device()
    register(client, inviter, now[0])
    register(client, joiner, now[0])
    create(client, inviter, now[0], ttl=30_000)
    now[0] += 30_000
    assert request(client, joiner, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED}).status_code == 404


def test_scanning_a_qr_enrolls_without_the_token(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", enrollment_token="private-token")
    inviter, joiner, stranger = Device(), Device(), Device()
    with TestClient(create_app(settings, lambda: NOW)) as client:
        assert request(client, inviter, NOW, "POST", "/v1/register", {}, headers={"X-Enrollment-Token": "private-token"}).status_code == 200
        create(client, inviter, NOW)
        assert request(client, stranger, NOW, "POST", "/v1/register", {}, headers={"X-Pairing-Id": pairing_id(7)}).status_code == 403
        assert request(client, joiner, NOW, "POST", "/v1/register", {}, headers={"X-Pairing-Id": pairing_id()}).status_code == 200
        assert request(client, joiner, NOW, "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED}).status_code == 204
        # A used code no longer admits new devices.
        assert request(client, stranger, NOW, "POST", "/v1/register", {}, headers={"X-Pairing-Id": pairing_id()}).status_code == 403


def test_pairing_long_poll_wakes_on_join(relay):
    _, now, client = relay
    inviter, joiner = Device(), Device()
    register(client, inviter, now[0])
    register(client, joiner, now[0])
    create(client, inviter, now[0])
    result = []
    thread = threading.Thread(target=lambda: result.append(request(client, inviter, now[0], "GET", f"/v1/pairings/{pairing_id()}?wait=5")))
    started = time.monotonic()
    thread.start()
    time.sleep(0.2)
    request(client, joiner, now[0], "POST", f"/v1/pairings/{pairing_id()}/join", {"sealed": SEALED})
    thread.join(3)
    assert result[0].json()["state"] == "joined"
    assert time.monotonic() - started < 3


def test_sandbox_lifetime_is_capped_and_metadata_validated(relay):
    _, now, client = relay
    device = Device()
    assert request(client, device, now[0], "POST", "/v1/register", {"platform": "ios", "model": "iPhone16,2", "appVersion": "1.0"}).status_code == 200
    assert request(client, Device(), now[0], "POST", "/v1/register", {"platform": "iOS!"}).status_code == 400
    assert request(client, Device(), now[0], "POST", "/v1/register", {"name": "Alice's phone"}).status_code == 400
    assert client.get("/v1/info").json()["maxLifetimeSeconds"] == 600
    assert request(client, device, now[0], "GET", "/v1/messages?wait=51").status_code == 400


def test_push_wakes_offline_iphone_without_content(tmp_path):
    calls = []

    async def transport(url, headers, payload):
        calls.append((url, headers, payload))
        return (410, '{"reason":"Unregistered"}') if len(calls) > 1 else (200, "")

    settings = Settings(database=tmp_path / "relay.sqlite3")
    with TestClient(create_app(settings, lambda: NOW, push_transport=transport)) as client:
        sender, phone = Device(), Device()
        register(client, sender, NOW)
        register(client, phone, NOW)
        request(client, phone, NOW, "PUT", f"/v1/peers/{sender.id}", {})
        token = "ab" * 32
        assert request(client, phone, NOW, "PUT", "/v1/push", {"provider": "apns", "token": token, "environment": "sandbox", "topic": "dev.devicelink.ios"}).status_code == 204
        item = envelope(sender, phone, NOW, version=2)
        assert request(client, sender, NOW, "POST", "/v1/messages", item).status_code == 201
        deadline = time.monotonic() + 2
        while not calls and time.monotonic() < deadline:
            time.sleep(0.02)
        url, headers, payload = calls[0]
        assert url == f"https://api.sandbox.push.apple.com/3/device/{token}"
        assert headers["apns-topic"] == "dev.devicelink.ios"
        assert payload["m"] == item["id"] and "ciphertext" not in str(payload)
        # An unregistered token is forgotten after Apple rejects it.
        request(client, sender, NOW, "POST", "/v1/messages", envelope(sender, phone, NOW, version=2, sequence=2))
        deadline = time.monotonic() + 2
        while len(calls) < 2 and time.monotonic() < deadline:
            time.sleep(0.02)
        time.sleep(0.1)
        assert client.app.state.store.push_target(phone.id) is None


def login(client, password="correct horse"):
    return client.post("/admin/login", content=f"username=admin&password={password}",
                       headers={"Content-Type": "application/x-www-form-urlencoded"}, follow_redirects=False)


def csrf(client):
    page = client.get("/admin").text
    return page.split('data-csrf="')[1].split('"')[0]


def test_admin_requires_configuration_and_login(tmp_path):
    with TestClient(create_app(Settings(database=tmp_path / "relay.sqlite3"), lambda: NOW)) as client:
        assert client.get("/admin").status_code == 404
        assert client.get("/admin/api/overview").status_code == 404


def test_admin_login_session_and_csrf(relay):
    _, now, client = relay
    assert "Administrator sign-in" in client.get("/admin").text
    assert client.get("/admin/api/overview").status_code == 401
    assert login(client, "wrong").headers["location"] == "/admin?error=1"
    response = login(client)
    assert response.status_code == 303 and response.headers["location"] == "/admin"
    assert "httponly" in response.headers["set-cookie"].lower() and "samesite=strict" in response.headers["set-cookie"].lower()
    page = client.get("/admin")
    assert "DeviceLink Relay" in page.text and "nonce-" in page.headers["content-security-policy"]
    assert client.get("/admin/api/overview").status_code == 200
    device = Device()
    register(client, device, now[0])
    assert client.post(f"/admin/api/devices/{device.id}/label", json={"label": "Desk phone"}).status_code == 403
    assert client.post(f"/admin/api/devices/{device.id}/label", json={"label": "Desk phone"}, headers={"X-CSRF-Token": csrf(client)}).status_code == 200
    assert client.get("/admin/api/devices").json()[0]["label"] == "Desk phone"


def test_admin_login_is_throttled(relay):
    _, _, client = relay
    for _ in range(10):
        login(client, "wrong")
    assert login(client).headers["location"] == "/admin?error=1"


def test_admin_sees_devices_sandboxes_and_activity_but_no_content(relay):
    _, now, client = relay
    sender, recipient = Device(), Device()
    request(client, sender, now[0], "POST", "/v1/register", {"platform": "android", "model": "Pixel 9", "appVersion": "2.0.0"})
    request(client, recipient, now[0], "POST", "/v1/register", {"platform": "ios"})
    request(client, recipient, now[0], "PUT", f"/v1/peers/{sender.id}", {})
    request(client, sender, now[0], "PUT", f"/v1/peers/{recipient.id}", {})
    pending = envelope(sender, recipient, now[0], version=2, ciphertext=base64.b64encode(b"SECRET-CLIPBOARD" * 4).decode())
    delivered = envelope(sender, recipient, now[0], version=2, sequence=2)
    for item in (pending, delivered):
        request(client, sender, now[0], "POST", "/v1/messages", item)
    now[0] += 1500
    request(client, recipient, now[0], "DELETE", f"/v1/messages/{delivered['id']}")
    login(client)
    overview = client.get("/admin/api/overview").json()
    assert overview["devices"]["total"] == 2 and overview["links"]["mutual"] == 1
    assert overview["sandbox"]["pending"] == 1 and overview["last24h"]["delivered"] == 1
    assert overview["last24h"]["medianLatencyMs"] == 1500
    devices = {item["id"]: item for item in client.get("/admin/api/devices").json()}
    assert devices[sender.id]["model"] == "Pixel 9" and devices[sender.id]["linked"] == [recipient.id]
    assert devices[recipient.id]["pending"] == 1
    sandboxes = client.get("/admin/api/sandboxes").json()
    assert len(sandboxes) == 1 and sandboxes[0]["mutual"] and [i["id"] for i in sandboxes[0]["items"]] == [pending["id"]]
    assert "SECRET" not in client.get("/admin/api/sandboxes").text
    detail = client.get(f"/admin/api/messages/{pending['id']}").json()
    assert detail["ciphertextBytes"] == 64 and "HPKE" in detail["encryption"]
    kinds = [event["kind"] for event in client.get("/admin/api/events").json()]
    assert {"registered", "sent", "delivered", "admin_login"} <= set(kinds)


def test_admin_block_unlink_purge_and_expiry_events(relay):
    _, now, client = relay
    sender, recipient = Device(), Device()
    register(client, sender, now[0])
    register(client, recipient, now[0])
    request(client, recipient, now[0], "PUT", f"/v1/peers/{sender.id}", {})
    item = envelope(sender, recipient, now[0], version=2)
    request(client, sender, now[0], "POST", "/v1/messages", item)
    login(client)
    token = csrf(client)
    assert client.delete(f"/admin/api/messages/{item['id']}", headers={"X-CSRF-Token": token}).status_code == 200
    assert request(client, recipient, now[0], "GET", "/v1/messages").json() == []
    assert client.post(f"/admin/api/devices/{sender.id}/block", json={"blocked": True}, headers={"X-CSRF-Token": token}).status_code == 200
    assert request(client, sender, now[0], "GET", "/v1/messages").json() == {"error": "device_blocked"}
    client.post(f"/admin/api/devices/{sender.id}/block", json={"blocked": False}, headers={"X-CSRF-Token": token})
    request(client, sender, now[0], "POST", "/v1/messages", envelope(sender, recipient, now[0], version=2, sequence=2, expiresAt=now[0] + 1000))
    now[0] += 1000
    request(client, recipient, now[0], "GET", "/v1/messages")
    assert "expired" in [event["kind"] for event in client.get("/admin/api/events").json()]
    assert client.delete(f"/admin/api/links/{sender.id}/{recipient.id}", headers={"X-CSRF-Token": token}).status_code == 200
    assert request(client, sender, now[0], "POST", "/v1/messages", envelope(sender, recipient, now[0], version=2, sequence=3)).status_code == 403
    assert client.get("/admin/api/sandboxes").json() == []


def test_setup_link_contains_relay_and_token(tmp_path):
    settings = Settings(database=tmp_path / "relay.sqlite3", admin_password="pw-for-tests", enrollment_token="tok",
                        public_url="https://relay.example")
    with TestClient(create_app(settings, lambda: NOW)) as client:
        login(client, "pw-for-tests")
        setup = client.get("/admin/api/setup").json()
        assert setup["setupLink"] == "https://relay.example/setup#v2." + base64.urlsafe_b64encode(b"tok").rstrip(b"=").decode()
        assert setup["qrSvg"].startswith("<svg")


def test_landing_pages_forward_fragment_to_app_with_strict_csp(relay):
    _, _, client = relay
    for kind in ("pair", "setup"):
        page = client.get(f"/{kind}")
        assert page.status_code == 200
        assert f"devicelink://{kind}?relay=" in page.text and "location.hash" in page.text
        assert "script-src 'nonce-" in page.headers["content-security-policy"]


def test_settings_from_toml_and_environment(tmp_path):
    path = tmp_path / "relay.toml"
    path.write_text('admin_username = "ops"\nadmin_password = "from-file"\nmax_ttl_ms = 300000\n')
    settings = Settings.environment({"RELAY_CONFIG": str(path), "RELAY_ADMIN_PASSWORD": "from-env"})
    assert (settings.admin_username, settings.admin_password, settings.max_ttl_ms) == ("ops", "from-env", 300000)
    path.write_text('unknown_key = 1\n')
    with pytest.raises(ValueError):
        Settings.environment({"RELAY_CONFIG": str(path)})
