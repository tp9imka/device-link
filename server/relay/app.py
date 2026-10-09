import asyncio
import hmac
import json
import re
import secrets
import sqlite3
import time
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from . import __version__
from .admin import install_admin
from .auth import authenticate, decode64, identifier, uuid_string
from .config import Settings
from .errors import RelayError
from .pages import landing_page
from .push import PushSender
from .store import Store

PAIRING_ID = re.compile(r"[0-9a-f]{32}")
METADATA_FIELDS = {"platform": r"[a-z]{1,16}", "model": r"[A-Za-z0-9 ,._()+-]{0,64}", "appVersion": r"[A-Za-z0-9 ._+-]{0,32}"}


def strict_json(body):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate field")
            result[key] = value
        return result

    try:
        result = json.loads(body, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(result, dict):
            raise ValueError()
        return result
    except (ValueError, RecursionError, UnicodeError):
        raise RelayError(400, "invalid_json") from None


def validate_envelope(body, sender, settings, now):
    value = strict_json(body)
    expected = {"version", "id", "senderId", "recipientId", "createdAt", "expiresAt", "sequence", "ciphertext", "signature"}
    if value.keys() != expected or not uuid_string(value.get("id")) or not identifier(value.get("recipientId")):
        raise RelayError(400, "invalid_envelope")
    if value["senderId"] != sender:
        raise RelayError(403, "sender_mismatch")
    # Version 1: legacy Android Internet Link. Version 2: Link v2 (QR pairing, iOS).
    if type(value["version"]) is not int or value["version"] not in (1, 2) or value["recipientId"] == sender:
        raise RelayError(400, "invalid_envelope")
    for field in ("createdAt", "expiresAt", "sequence"):
        if type(value[field]) is not int or not 0 <= value[field] <= 2**63 - 1:
            raise RelayError(400, "invalid_envelope")
    if value["sequence"] == 0:
        raise RelayError(400, "invalid_envelope")
    created, expires = value["createdAt"], value["expiresAt"]
    if created > now + settings.skew_ms or expires <= now or not 0 < expires - created <= settings.max_ttl_ms:
        raise RelayError(400, "invalid_expiry")
    try:
        if len(decode64(value["ciphertext"], settings.ciphertext_limit)) < 16:
            raise ValueError()
        if not 8 <= len(decode64(value["signature"], 80)) <= 80:
            raise ValueError()
    except (ValueError, TypeError):
        raise RelayError(400, "invalid_envelope") from None
    return value


def validate_metadata(body):
    value = strict_json(body)
    if set(value) - set(METADATA_FIELDS):
        raise RelayError(400, "invalid_metadata")
    for key, pattern in METADATA_FIELDS.items():
        if key in value and (not isinstance(value[key], str) or not re.fullmatch(pattern, value[key])):
            raise RelayError(400, "invalid_metadata")
    return value


def sealed_field(body, maximum=16 * 1024):
    value = strict_json(body)
    sealed = value.get("sealed")
    if set(value) != {"sealed"}:
        raise RelayError(400, "invalid_pairing")
    try:
        if len(decode64(sealed, maximum)) < 29:
            raise ValueError()
    except (ValueError, TypeError):
        raise RelayError(400, "invalid_pairing") from None
    return sealed


class Runtime:
    """In-process state shown on the admin dashboard; it is lost on restart by design."""

    def __init__(self):
        self.started = time.time()
        self.polling = set()
        self.counters = {"auth_failures": 0, "rate_limited": 0, "requests": 0, "push_sent": 0, "push_failed": 0}


def create_app(settings=None, clock=None, push_transport=None):
    settings = settings or Settings.environment()
    clock = clock or (lambda: time.time_ns() // 1_000_000)
    store = Store(settings)
    runtime = Runtime()
    changed = asyncio.Condition()
    uploads = asyncio.Semaphore(4)
    push = PushSender(settings, store, runtime, transport=push_transport)

    @asynccontextmanager
    async def lifespan(_app):
        await run_in_threadpool(store.cleanup, clock())

        async def janitor():
            while True:
                await asyncio.sleep(settings.janitor_seconds)
                with suppress(sqlite3.Error):
                    await run_in_threadpool(store.cleanup, clock())

        task = asyncio.create_task(janitor())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
            await push.close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store, app.state.runtime, app.state.settings, app.state.clock = store, runtime, settings, clock

    @app.middleware("http")
    async def private_responses(request, call_next):
        runtime.counters["requests"] += 1
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        return response

    @app.exception_handler(sqlite3.Error)
    async def storage_unavailable(_request, _exc):
        return JSONResponse({"error": "storage_unavailable"}, status_code=503)

    @app.exception_handler(RelayError)
    async def relay_error(_request, exc):
        if exc.status == 401:
            runtime.counters["auth_failures"] += 1
        if exc.status == 429:
            runtime.counters["rate_limited"] += 1
        return JSONResponse({"error": exc.code}, status_code=exc.status,
                            headers={"Retry-After": "60"} if exc.status == 429 else None)

    async def signed(request, registration=False):
        maximum = settings.body_limit if request.method == "POST" and request.url.path == "/v1/messages" else 32 * 1024
        async with uploads:
            body = bytearray()
            try:
                async with asyncio.timeout(30):
                    async for chunk in request.stream():
                        if len(body) + len(chunk) > maximum:
                            raise RelayError(413, "body_too_large")
                        body.extend(chunk)
            except TimeoutError:
                raise RelayError(408, "body_timeout") from None
            body = bytes(body)
            try:
                target = request.scope["raw_path"].decode("ascii")
                if request.scope["query_string"]:
                    target += "?" + request.scope["query_string"].decode("ascii")
            except UnicodeError:
                raise RelayError(400, "invalid_target") from None
            now = clock()
            identity = await run_in_threadpool(authenticate, request.headers, request.method, target, body, now, settings.skew_ms)
            if registration and settings.enrollment_token:
                supplied = request.headers.getlist("x-enrollment-token")
                pairing = request.headers.getlist("x-pairing-id")
                token_ok = len(supplied) == 1 and hmac.compare_digest(supplied[0].encode(), settings.enrollment_token.encode())
                # Scanning a valid QR code is enough to enroll: the inviter is already trusted.
                pairing_ok = (len(pairing) == 1 and PAIRING_ID.fullmatch(pairing[0])
                              and await run_in_threadpool(store.open_pairing, pairing[0], now))
                if not (token_ok or pairing_ok):
                    raise RelayError(403, "enrollment_required")
            await run_in_threadpool(store.authorize, identity, now, registration)
            return identity, body

    def empty_body(body):
        if strict_json(body) != {}:
            raise RelayError(400, "empty_object_required")

    async def notify():
        async with changed:
            changed.notify_all()

    async def wait_for(predicate, wait):
        """Long-poll helper: re-evaluates [predicate] whenever state changes, until [wait] seconds pass."""
        deadline = asyncio.get_running_loop().time() + wait
        async with changed:
            while True:
                result = await predicate()
                remaining = deadline - asyncio.get_running_loop().time()
                if result is not None or remaining <= 0:
                    return result
                try:
                    await asyncio.wait_for(changed.wait(), remaining)
                except TimeoutError:
                    return None

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/v1/info")
    async def info():
        return {"service": "devicelink-relay", "version": __version__, "maxLifetimeSeconds": settings.max_ttl_ms // 1000,
                "enrollment": "token" if settings.enrollment_token else "open",
                "push": ["apns"] if settings.apns_enabled else []}

    @app.post("/v1/register")
    async def register(request: Request):
        identity, body = await signed(request, registration=True)
        metadata = validate_metadata(body)
        await run_in_threadpool(store.register, identity, clock(), metadata)
        return {"deviceId": identity.id}

    @app.put("/v1/peers/{sender}")
    async def allow(sender: str, request: Request):
        identity, body = await signed(request)
        empty_body(body)
        if not identifier(sender):
            raise RelayError(400, "invalid_peer")
        await run_in_threadpool(store.peer, identity.id, sender, clock())
        return Response(status_code=204)

    @app.delete("/v1/peers/{sender}")
    async def disallow(sender: str, request: Request):
        identity, _ = await signed(request)
        if not identifier(sender):
            raise RelayError(400, "invalid_peer")
        await run_in_threadpool(store.peer, identity.id, sender, clock(), True)
        return Response(status_code=204)

    @app.post("/v1/pairings")
    async def create_pairing(request: Request):
        identity, body = await signed(request)
        value = strict_json(body)
        now = clock()
        if (set(value) != {"id", "expiresAt"} or not isinstance(value["id"], str) or not PAIRING_ID.fullmatch(value["id"])
                or type(value["expiresAt"]) is not int or not now < value["expiresAt"] <= now + settings.max_ttl_ms):
            raise RelayError(400, "invalid_pairing")
        await run_in_threadpool(store.create_pairing, identity.id, value["id"], now, value["expiresAt"])
        return JSONResponse({"id": value["id"]}, status_code=201)

    @app.post("/v1/pairings/{pairing}/join")
    async def join_pairing(pairing: str, request: Request):
        identity, body = await signed(request)
        if not PAIRING_ID.fullmatch(pairing):
            raise RelayError(400, "invalid_pairing")
        await run_in_threadpool(store.join_pairing, identity.id, pairing, sealed_field(body), clock())
        await notify()
        return Response(status_code=204)

    @app.post("/v1/pairings/{pairing}/confirm")
    async def confirm_pairing(pairing: str, request: Request):
        identity, body = await signed(request)
        if not PAIRING_ID.fullmatch(pairing):
            raise RelayError(400, "invalid_pairing")
        await run_in_threadpool(store.confirm_pairing, identity.id, pairing, sealed_field(body), clock())
        await notify()
        return Response(status_code=204)

    @app.get("/v1/pairings/{pairing}")
    async def pairing_status(pairing: str, request: Request):
        identity, _ = await signed(request)
        query = request.query_params
        try:
            if any(key != "wait" for key in query) or len(query.getlist("wait")) > 1:
                raise ValueError()
            wait = int(query.get("wait", "0"))
            if not 0 <= wait <= 25 or not PAIRING_ID.fullmatch(pairing):
                raise ValueError()
        except ValueError:
            raise RelayError(400, "invalid_wait") from None
        first = await run_in_threadpool(store.pairing_status, identity.id, pairing, clock())

        async def progressed():
            current = await run_in_threadpool(store.pairing_status, identity.id, pairing, clock())
            return current if current["state"] != first["state"] or current["state"] == "confirmed" else None

        if first["state"] == "confirmed" or (first["state"] == "joined" and first["sealed"] and identity.id != first["joinerId"]):
            return first
        try:
            return await wait_for(progressed, wait) or first
        except RelayError as failure:
            if failure.status == 404:
                return JSONResponse({"error": "pairing_not_found"}, status_code=404)
            raise

    @app.delete("/v1/pairings/{pairing}")
    async def cancel_pairing(pairing: str, request: Request):
        identity, _ = await signed(request)
        if not PAIRING_ID.fullmatch(pairing):
            raise RelayError(400, "invalid_pairing")
        await run_in_threadpool(store.cancel_pairing, identity.id, pairing)
        return Response(status_code=204)

    @app.post("/v1/messages")
    async def send(request: Request):
        identity, body = await signed(request)
        envelope = await run_in_threadpool(validate_envelope, body, identity.id, settings, clock())
        async with changed:
            inserted = await run_in_threadpool(store.enqueue, envelope, body, clock())
            changed.notify_all()
        if inserted and envelope["recipientId"] not in runtime.polling:
            push.schedule(envelope["recipientId"], envelope["id"], envelope["expiresAt"])
        return JSONResponse({"id": envelope["id"]}, status_code=201 if inserted else 200)

    @app.get("/v1/messages")
    async def receive(request: Request):
        identity, _ = await signed(request)
        query = request.query_params
        try:
            if any(key not in ("wait", "exclude", "limit") for key in query) or any(len(query.getlist(key)) > 1 for key in query):
                raise ValueError()
            wait = int(query.get("wait", "0"))
            if not 0 <= wait <= settings.max_poll_wait:
                raise ValueError()
            limit = int(query.get("limit", "20"))
            if not 1 <= limit <= 20:
                raise ValueError()
            excluded = query.get("exclude", "").split(",") if query.get("exclude") else []
            if len(excluded) > 20 or len(set(excluded)) != len(excluded) or any(not uuid_string(item) for item in excluded):
                raise ValueError()
        except ValueError:
            raise RelayError(400, "invalid_wait") from None
        if identity.id in runtime.polling:
            raise RelayError(429, "poll_already_active")
        runtime.polling.add(identity.id)
        try:
            async def available():
                items = await run_in_threadpool(store.mailbox, identity.id, clock(), excluded, limit)
                return items or None

            items = await wait_for(available, wait) or []
            return Response(b"[" + b",".join(items) + b"]", media_type="application/json")
        finally:
            runtime.polling.discard(identity.id)

    @app.get("/v1/messages/{message}")
    async def fetch_one(message: str, request: Request):
        identity, _ = await signed(request)
        if not uuid_string(message):
            raise RelayError(400, "invalid_message")
        body = await run_in_threadpool(store.message_for, identity.id, message, clock())
        if body is None:
            # Same answer for "not yours", "delivered" and "expired".
            raise RelayError(404, "message_not_found")
        return Response(body, media_type="application/json")

    @app.delete("/v1/messages/{message}")
    async def acknowledge(message: str, request: Request):
        identity, _ = await signed(request)
        if not uuid_string(message):
            raise RelayError(400, "invalid_message")
        await run_in_threadpool(store.acknowledge, identity.id, message, clock())
        return Response(status_code=204)

    @app.put("/v1/push")
    async def register_push(request: Request):
        identity, body = await signed(request)
        value = strict_json(body)
        if (set(value) != {"provider", "token", "environment", "topic"} or value["provider"] != "apns"
                or not isinstance(value["token"], str) or not re.fullmatch(r"[0-9a-f]{32,200}", value["token"])
                or value["environment"] not in ("sandbox", "production")
                or not isinstance(value["topic"], str) or not re.fullmatch(r"[A-Za-z0-9.-]{1,155}", value["topic"])):
            raise RelayError(400, "invalid_push")
        await run_in_threadpool(store.set_push, identity.id, value["token"], value["environment"], value["topic"])
        return Response(status_code=204)

    @app.delete("/v1/push")
    async def remove_push(request: Request):
        identity, _ = await signed(request)
        await run_in_threadpool(store.set_push, identity.id, None, None, None)
        return Response(status_code=204)

    # ----- browser-facing pages ---------------------------------------------------------------

    @app.get("/pair", response_class=HTMLResponse)
    async def pair_page():
        return landing_page("pair", secrets.token_urlsafe(16))

    @app.get("/setup", response_class=HTMLResponse)
    async def setup_page():
        return landing_page("setup", secrets.token_urlsafe(16))

    @app.get("/.well-known/assetlinks.json")
    async def asset_links():
        statements = []
        for entry in filter(None, (item.strip() for item in settings.android_app_links.split(","))):
            package, _, fingerprint = entry.partition(":")
            statements.append({"relation": ["delegate_permission/common.handle_all_urls"],
                               "target": {"namespace": "android_app", "package_name": package,
                                          "sha256_cert_fingerprints": [fingerprint]}})
        return JSONResponse(statements)

    @app.get("/.well-known/apple-app-site-association")
    async def apple_association():
        ids = [item.strip() for item in settings.apple_app_ids.split(",") if item.strip()]
        return JSONResponse({"applinks": {"details": [{"appIDs": ids, "components": [{"/": "/pair"}, {"/": "/setup"}]}]}})

    install_admin(app)
    return app
