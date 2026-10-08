import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database: Path = Path("data/relay.sqlite3")
    enrollment_token: str = ""
    body_limit: int = 24 * 1024 * 1024
    ciphertext_limit: int = 16 * 1024 * 1024
    mailbox_count: int = 20
    mailbox_bytes: int = 64 * 1024 * 1024
    global_bytes: int = 256 * 1024 * 1024
    max_devices: int = 10_000
    requests_per_minute: int = 120
    global_requests_per_minute: int = 10_000
    max_peers: int = 100
    ttl_ms: int = 24 * 60 * 60 * 1000
    skew_ms: int = 60_000

    @classmethod
    def environment(cls):
        return cls(
            database=Path(os.environ.get("RELAY_DATABASE", "data/relay.sqlite3")),
            enrollment_token=os.environ.get("RELAY_ENROLLMENT_TOKEN", ""),
            mailbox_bytes=int(os.environ.get("RELAY_MAILBOX_BYTES", 64 * 1024 * 1024)),
            global_bytes=int(os.environ.get("RELAY_GLOBAL_BYTES", 256 * 1024 * 1024)),
        )
