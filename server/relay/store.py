import hashlib
import sqlite3
import threading
from contextlib import contextmanager

from .errors import RelayError


class Store:
    """Short serialized transactions. Call from worker threads, never the event loop."""

    def __init__(self, settings):
        self.settings = settings
        self.lock = threading.Lock()
        settings.database.parent.mkdir(parents=True, exist_ok=True)
        with self.transaction() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, key BLOB NOT NULL);
                CREATE TABLE IF NOT EXISTS peers(recipient TEXT NOT NULL, sender TEXT NOT NULL,
                    PRIMARY KEY(recipient,sender));
                CREATE TABLE IF NOT EXISTS nonces(device TEXT NOT NULL, nonce TEXT NOT NULL,
                    received INTEGER NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(device,nonce));
                CREATE INDEX IF NOT EXISTS nonce_expiry ON nonces(expires);
                CREATE INDEX IF NOT EXISTS nonce_received ON nonces(received);
                CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, sender TEXT NOT NULL,
                    recipient TEXT NOT NULL, expires INTEGER NOT NULL, sequence INTEGER NOT NULL, body BLOB NOT NULL);
                CREATE INDEX IF NOT EXISTS mailbox ON messages(recipient);
                CREATE TABLE IF NOT EXISTS dedupe(id TEXT PRIMARY KEY, digest TEXT NOT NULL,
                    expires INTEGER NOT NULL);
            """)

    @contextmanager
    def transaction(self):
        with self.lock:
            db = sqlite3.connect(self.settings.database, timeout=10)
            try:
                db.execute("PRAGMA journal_mode=WAL")
                db.execute("PRAGMA synchronous=FULL")
                db.execute("BEGIN IMMEDIATE")
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise
            finally:
                db.close()

    @staticmethod
    def cleanup_in(db, now):
        db.execute("DELETE FROM nonces WHERE expires < ?", (now,))
        db.execute("DELETE FROM messages WHERE expires <= ?", (now,))
        db.execute("DELETE FROM dedupe WHERE expires <= ?", (now,))

    def cleanup(self, now):
        with self.transaction() as db:
            self.cleanup_in(db, now)

    def authorize(self, identity, now, registration=False):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            existing = db.execute("SELECT key FROM devices WHERE id=?", (identity.id,)).fetchone()
            if not registration and existing is None:
                raise RelayError(403, "registration_required")
            if existing and existing[0] != identity.key:
                raise RelayError(401, "invalid_authentication")
            if db.execute("SELECT 1 FROM nonces WHERE device=? AND nonce=?", (identity.id, identity.nonce)).fetchone():
                raise RelayError(409, "replayed_request")
            own = db.execute("SELECT count(*) FROM nonces WHERE device=? AND received>?", (identity.id, now - 60_000)).fetchone()[0]
            total = db.execute("SELECT count(*) FROM nonces WHERE received>?", (now - 60_000,)).fetchone()[0]
            if own >= self.settings.requests_per_minute or total >= self.settings.global_requests_per_minute:
                raise RelayError(429, "rate_limit")
            # Future-dated valid requests remain replayable until timestamp+skew.
            db.execute("INSERT INTO nonces VALUES(?,?,?,?)", (identity.id, identity.nonce, now, max(now + 60_000, identity.timestamp + self.settings.skew_ms)))

    def register(self, identity):
        with self.transaction() as db:
            existing = db.execute("SELECT key FROM devices WHERE id=?", (identity.id,)).fetchone()
            if existing:
                if existing[0] != identity.key:
                    raise RelayError(409, "identity_conflict")
                return
            if db.execute("SELECT count(*) FROM devices").fetchone()[0] >= self.settings.max_devices:
                raise RelayError(507, "registration_capacity")
            db.execute("INSERT INTO devices VALUES(?,?)", (identity.id, identity.key))

    def peer(self, recipient, sender, remove=False):
        with self.transaction() as db:
            if remove:
                db.execute("DELETE FROM peers WHERE recipient=? AND sender=?", (recipient, sender))
                db.execute("DELETE FROM messages WHERE recipient=? AND sender=?", (recipient, sender))
                return
            if db.execute("SELECT 1 FROM peers WHERE recipient=? AND sender=?", (recipient, sender)).fetchone():
                return
            if db.execute("SELECT count(*) FROM peers WHERE recipient=?", (recipient,)).fetchone()[0] >= self.settings.max_peers:
                raise RelayError(429, "peer_limit")
            db.execute("INSERT INTO peers VALUES(?,?)", (recipient, sender))

    def enqueue(self, envelope, body, now):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            recipient, sender = envelope["recipientId"], envelope["senderId"]
            if not db.execute("SELECT 1 FROM devices WHERE id=?", (recipient,)).fetchone() or not db.execute("SELECT 1 FROM peers WHERE recipient=? AND sender=?", (recipient, sender)).fetchone():
                raise RelayError(403, "recipient_not_authorized")
            digest = hashlib.sha256(body).hexdigest()
            old = db.execute("SELECT digest FROM dedupe WHERE id=?", (envelope["id"],)).fetchone()
            if old:
                if old[0] != digest:
                    raise RelayError(409, "message_conflict")
                return False
            count, size = db.execute("SELECT count(*),coalesce(sum(length(body)),0) FROM messages WHERE recipient=?", (recipient,)).fetchone()
            total = db.execute("SELECT coalesce(sum(length(body)),0) FROM messages").fetchone()[0]
            if count >= self.settings.mailbox_count or size + len(body) > self.settings.mailbox_bytes:
                raise RelayError(429, "mailbox_full")
            if total + len(body) > self.settings.global_bytes:
                raise RelayError(507, "storage_full")
            # Bound acknowledgement tombstones even if clients upload+ack repeatedly.
            if db.execute("SELECT count(*) FROM dedupe").fetchone()[0] >= 100_000:
                raise RelayError(507, "storage_full")
            db.execute("INSERT INTO messages VALUES(?,?,?,?,?,?)", (envelope["id"], sender, recipient, envelope["expiresAt"], envelope["sequence"], body))
            db.execute("INSERT INTO dedupe VALUES(?,?,?)", (envelope["id"], digest, envelope["expiresAt"]))
            return True

    def mailbox(self, recipient, now, excluded=(), limit=20):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            excluded_clause = " AND id NOT IN (" + ",".join("?" for _ in excluded) + ")" if excluded else ""
            return [row[0] for row in db.execute(
                "SELECT body FROM messages WHERE recipient=?" + excluded_clause + " ORDER BY sender,sequence DESC,id LIMIT ?",
                (recipient, *excluded, limit),
            )]

    def acknowledge(self, recipient, message):
        with self.transaction() as db:
            # Idempotent and does not reveal existence/ownership of other messages.
            db.execute("DELETE FROM messages WHERE id=? AND recipient=?", (message, recipient))
