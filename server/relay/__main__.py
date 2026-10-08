import os

import uvicorn


if __name__ == "__main__":
    # A private default, no URLs/device identifiers in access logs, one worker.
    os.umask(0o077)
    uvicorn.run("relay.app:create_app", factory=True,
                host=os.environ.get("RELAY_BIND", "127.0.0.1"),
                port=int(os.environ.get("RELAY_PORT", "8000")),
                access_log=False, limit_concurrency=32,
                timeout_keep_alive=5, timeout_graceful_shutdown=30)
