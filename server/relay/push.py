"""Optional APNs wake-up for iPhones. Pushes never contain content, sender names or keys."""
import asyncio
import base64
import json
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from starlette.concurrency import run_in_threadpool

HOSTS = {"production": "https://api.push.apple.com", "sandbox": "https://api.sandbox.push.apple.com"}


def _b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


class PushSender:
    def __init__(self, settings, store, runtime, transport=None):
        self.settings = settings
        self.store = store
        self.runtime = runtime
        self.transport = transport
        self.client = None
        self.token = None
        self.token_issued = 0
        self.tasks = set()

    @property
    def enabled(self):
        return self.settings.apns_enabled or self.transport is not None

    def schedule(self, device, message_id, expires_at):
        if not self.enabled:
            return
        task = asyncio.create_task(self._send(device, message_id, expires_at))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    def _jwt(self):
        # Apple accepts a provider token for up to an hour; refresh every 50 minutes.
        if self.token and time.time() - self.token_issued < 3000:
            return self.token
        with open(self.settings.apns_key_path, "rb") as handle:
            key = serialization.load_pem_private_key(handle.read(), password=None)
        issued = int(time.time())
        header = _b64url(json.dumps({"alg": "ES256", "kid": self.settings.apns_key_id}).encode())
        claims = _b64url(json.dumps({"iss": self.settings.apns_team_id, "iat": issued}).encode())
        r, s = decode_dss_signature(key.sign(f"{header}.{claims}".encode(), ec.ECDSA(hashes.SHA256())))
        self.token = f"{header}.{claims}.{_b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"
        self.token_issued = issued
        return self.token

    async def _post(self, url, headers, payload):
        if self.transport is not None:
            return await self.transport(url, headers, payload)
        if self.client is None:
            import httpx
            self.client = httpx.AsyncClient(http2=True, timeout=10)
        response = await self.client.post(url, headers=headers, content=json.dumps(payload).encode())
        return response.status_code, response.text

    async def _send(self, device, message_id, expires_at):
        try:
            target = await run_in_threadpool(self.store.push_target, device)
            if not target:
                return
            headers = {
                "apns-topic": target["topic"],
                "apns-push-type": "alert",
                "apns-priority": "10",
                "apns-expiration": str(max(0, expires_at // 1000)),
                "apns-collapse-id": "devicelink-new-item",
            }
            if self.transport is None:
                headers["authorization"] = "bearer " + await run_in_threadpool(self._jwt)
            payload = {
                "aps": {"alert": {"title": "DeviceLink", "body": "New item from a linked device"},
                        "sound": "default", "mutable-content": 1, "thread-id": "devicelink"},
                "m": message_id,
            }
            url = f"{HOSTS[target['environment']]}/3/device/{target['token']}"
            status, body = await self._post(url, headers, payload)
            if status == 200:
                self.runtime.counters["push_sent"] += 1
                return
            self.runtime.counters["push_failed"] += 1
            if status == 410 or (status == 400 and "BadDeviceToken" in body):
                await run_in_threadpool(self.store.clear_push_token, target["token"])
        except Exception:  # noqa: BLE001 - push is best effort and must never break delivery
            self.runtime.counters["push_failed"] += 1

    async def close(self):
        for task in list(self.tasks):
            task.cancel()
        if self.client is not None:
            await self.client.aclose()
