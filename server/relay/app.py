import asyncio
import hmac
import json
import sqlite3
import time
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from .auth import authenticate, decode64, identifier, uuid_string
from .config import Settings
from .errors import RelayError
from .store import Store


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
    if type(value["version"]) is not int or value["version"] != 1 or value["recipientId"] == sender:
        raise RelayError(400, "invalid_envelope")
    for field in ("createdAt", "expiresAt", "sequence"):
        if type(value[field]) is not int or not 0 <= value[field] <= 2**63 - 1:
            raise RelayError(400, "invalid_envelope")
    if value["sequence"] == 0:
        raise RelayError(400, "invalid_envelope")
    created, expires = value["createdAt"], value["expiresAt"]
    if created > now + settings.skew_ms or expires <= now or not 0 < expires - created <= settings.ttl_ms:
        raise RelayError(400, "invalid_expiry")
    try:
        if len(decode64(value["ciphertext"], settings.ciphertext_limit)) < 16:
            raise ValueError()
        if not 8 <= len(decode64(value["signature"], 80)) <= 80:
            raise ValueError()
    except (ValueError, TypeError):
        raise RelayError(400, "invalid_envelope") from None
    return value


def create_app(settings=None, clock=None):
    settings = settings or Settings.environment()
    clock = clock or (lambda: time.time_ns() // 1_000_000)
    store = Store(settings)
    changed = asyncio.Condition()
    uploads = asyncio.Semaphore(4)
    polling = set()

    @asynccontextmanager
    async def lifespan(_app):
        await run_in_threadpool(store.cleanup, clock())

        async def janitor():
            while True:
                await asyncio.sleep(30)
                await run_in_threadpool(store.cleanup, clock())

        task = asyncio.create_task(janitor())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def private_responses(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.exception_handler(sqlite3.Error)
    async def storage_unavailable(_request, _exc):
        return JSONResponse({"error": "storage_unavailable"}, status_code=503)

    @app.exception_handler(RelayError)
    async def relay_error(_request, exc):
        return JSONResponse({"error": exc.code}, status_code=exc.status,
                            headers={"Retry-After": "60"} if exc.status == 429 else None)

    async def signed(request, registration=False):
        maximum = settings.body_limit if request.method == "POST" and request.url.path == "/v1/messages" else 4096
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
                if len(supplied) != 1 or not hmac.compare_digest(supplied[0].encode(), settings.enrollment_token.encode()):
                    raise RelayError(403, "enrollment_required")
            await run_in_threadpool(store.authorize, identity, now, registration)
            return identity, body

    def empty_body(body):
        if strict_json(body) != {}:
            raise RelayError(400, "empty_object_required")

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.post("/v1/register")
    async def register(request: Request):
        identity, body = await signed(request, registration=True)
        empty_body(body)
        await run_in_threadpool(store.register, identity)
        return {"deviceId": identity.id}

    @app.put("/v1/peers/{sender}")
    async def allow(sender: str, request: Request):
        identity, body = await signed(request)
        empty_body(body)
        if not identifier(sender):
            raise RelayError(400, "invalid_peer")
        await run_in_threadpool(store.peer, identity.id, sender)
        return Response(status_code=204)

    @app.delete("/v1/peers/{sender}")
    async def disallow(sender: str, request: Request):
        identity, _ = await signed(request)
        if not identifier(sender):
            raise RelayError(400, "invalid_peer")
        await run_in_threadpool(store.peer, identity.id, sender, True)
        return Response(status_code=204)

    @app.post("/v1/messages")
    async def send(request: Request):
        identity, body = await signed(request)
        envelope = await run_in_threadpool(validate_envelope, body, identity.id, settings, clock())
        async with changed:
            inserted = await run_in_threadpool(store.enqueue, envelope, body, clock())
            changed.notify_all()
        return JSONResponse({"id": envelope["id"]}, status_code=201 if inserted else 200)

    @app.get("/v1/messages")
    async def receive(request: Request):
        identity, _ = await signed(request)
        query = request.query_params
        try:
            if any(key not in ("wait", "exclude", "limit") for key in query) or any(len(query.getlist(key)) > 1 for key in query):
                raise ValueError()
            wait = int(query.get("wait", "0"))
            if not 0 <= wait <= 25:
                raise ValueError()
            limit = int(query.get("limit", "20"))
            if not 1 <= limit <= 20:
                raise ValueError()
            excluded = query.get("exclude", "").split(",") if query.get("exclude") else []
            if len(excluded) > 20 or len(set(excluded)) != len(excluded) or any(not uuid_string(item) for item in excluded):
                raise ValueError()
        except ValueError:
            raise RelayError(400, "invalid_wait") from None
        if identity.id in polling:
            raise RelayError(429, "poll_already_active")
        polling.add(identity.id)
        deadline = asyncio.get_running_loop().time() + wait
        try:
            # Shared condition avoids the check-then-sleep lost-wakeup race.
            async with changed:
                while True:
                    items = await run_in_threadpool(store.mailbox, identity.id, clock(), excluded, limit)
                    remaining = deadline - asyncio.get_running_loop().time()
                    if items or remaining <= 0:
                        return Response(b"[" + b",".join(items) + b"]", media_type="application/json")
                    try:
                        await asyncio.wait_for(changed.wait(), remaining)
                    except TimeoutError:
                        return Response(b"[]", media_type="application/json")
        finally:
            polling.discard(identity.id)

    @app.delete("/v1/messages/{message}")
    async def acknowledge(message: str, request: Request):
        identity, _ = await signed(request)
        if not uuid_string(message):
            raise RelayError(400, "invalid_message")
        await run_in_threadpool(store.acknowledge, identity.id, message)
        return Response(status_code=204)

    return app
