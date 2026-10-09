import os
import tomllib
from dataclasses import dataclass, fields
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    """Relay configuration.

    Loaded from an optional TOML file (``RELAY_CONFIG``) and then ``RELAY_*`` environment
    variables, which win. Keys are the field names below; see ``config.example.toml``.
    Keep real values (admin password, tokens, hostnames, APNs keys) outside git.
    """

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
    # Sandbox lifetime cap. Clients default to 5 minutes; anything longer is refused.
    max_ttl_ms: int = 10 * 60 * 1000
    max_poll_wait: int = 50
    skew_ms: int = 60_000
    janitor_seconds: int = 10
    open_pairings_per_device: int = 5
    event_retention_ms: int = 7 * 24 * 60 * 60 * 1000
    max_events: int = 50_000
    # Public base URL used in admin setup QR codes (for example the Cloudflare tunnel URL).
    public_url: str = ""
    # Admin dashboard. Disabled unless a password or password hash is configured.
    admin_username: str = "admin"
    admin_password: str = ""
    admin_password_hash: str = ""
    session_secret: str = ""
    session_hours: int = 12
    # Optional Apple Push Notification service (token auth, .p8 key).
    apns_key_path: str = ""
    apns_key_id: str = ""
    apns_team_id: str = ""
    # Optional app-link verification files, comma separated.
    # android_app_links: "package.name:AA:BB:...SHA256", apple_app_ids: "TEAMID.bundle.id"
    android_app_links: str = ""
    apple_app_ids: str = ""

    @property
    def admin_enabled(self):
        return bool(self.admin_password or self.admin_password_hash)

    @property
    def apns_enabled(self):
        return bool(self.apns_key_path and self.apns_key_id and self.apns_team_id)

    @classmethod
    def environment(cls, environ=None):
        environ = os.environ if environ is None else environ
        values = {}
        path = environ.get("RELAY_CONFIG")
        if path:
            with open(path, "rb") as handle:
                values.update(tomllib.load(handle))
        for item in fields(cls):
            key = "RELAY_" + item.name.upper()
            if key in environ:
                values[item.name] = environ[key]
        known = {item.name: item for item in fields(cls)}
        unknown = set(values) - set(known)
        if unknown:
            raise ValueError(f"Unknown relay settings: {', '.join(sorted(unknown))}")
        converted = {}
        for name, value in values.items():
            default = known[name].default
            if isinstance(default, bool):
                converted[name] = value if isinstance(value, bool) else str(value).lower() in ("1", "true", "yes")
            elif isinstance(default, int):
                converted[name] = int(value)
            elif isinstance(default, Path):
                converted[name] = Path(value)
            else:
                converted[name] = str(value)
        return cls(**converted)
