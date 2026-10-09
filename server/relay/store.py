import hashlib
import sqlite3
import threading
from contextlib import contextmanager

from .errors import RelayError

SCHEMA = """
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
CREATE TABLE IF NOT EXISTS pairings(id TEXT PRIMARY KEY, creator TEXT NOT NULL, created INTEGER NOT NULL,
    expires INTEGER NOT NULL, state TEXT NOT NULL, joiner TEXT, join_sealed TEXT, confirm_sealed TEXT);
CREATE INDEX IF NOT EXISTS pairing_expiry ON pairings(expires);
CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL,
    device TEXT, peer TEXT, size INTEGER, value INTEGER);
CREATE INDEX IF NOT EXISTS event_time ON events(at);
"""

# Columns added after the first release; applied idempotently to existing databases.
MIGRATIONS = {
    "devices": [("created", "INTEGER NOT NULL DEFAULT 0"), ("last_seen", "INTEGER NOT NULL DEFAULT 0"),
                ("platform", "TEXT NOT NULL DEFAULT ''"), ("model", "TEXT NOT NULL DEFAULT ''"),
                ("app_version", "TEXT NOT NULL DEFAULT ''"), ("label", "TEXT NOT NULL DEFAULT ''"),
                ("blocked", "INTEGER NOT NULL DEFAULT 0"), ("push_token", "TEXT"), ("push_env", "TEXT"),
                ("push_topic", "TEXT"), ("sent_count", "INTEGER NOT NULL DEFAULT 0"),
                ("sent_bytes", "INTEGER NOT NULL DEFAULT 0"), ("received_count", "INTEGER NOT NULL DEFAULT 0"),
                ("received_bytes", "INTEGER NOT NULL DEFAULT 0")],
    "peers": [("created", "INTEGER NOT NULL DEFAULT 0")],
    "messages": [("created", "INTEGER NOT NULL DEFAULT 0")],
}


class Store:
    """Short serialized transactions. Call from worker threads, never the event loop."""

    def __init__(self, settings):
        self.settings = settings
        self.lock = threading.Lock()
        settings.database.parent.mkdir(parents=True, exist_ok=True)
        with self.transaction() as db:
            db.executescript(SCHEMA)
            for table, columns in MIGRATIONS.items():
                existing = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
                for name, definition in columns:
                    if name not in existing:
                        db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {definition}")

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
    def event(db, now, kind, device=None, peer=None, size=None, value=None):
        db.execute("INSERT INTO events(at,kind,device,peer,size,value) VALUES(?,?,?,?,?,?)",
                   (now, kind, device, peer, size, value))

    def cleanup_in(self, db, now):
        db.execute("DELETE FROM nonces WHERE expires < ?", (now,))
        # Undelivered sandbox items are recorded (metadata only) before they disappear.
        db.execute("INSERT INTO events(at,kind,device,peer,size) SELECT expires,'expired',recipient,sender,length(body) "
                   "FROM messages WHERE expires <= ?", (now,))
        db.execute("DELETE FROM messages WHERE expires <= ?", (now,))
        db.execute("DELETE FROM dedupe WHERE expires <= ?", (now,))
        db.execute("INSERT INTO events(at,kind,device) SELECT expires,'pairing_expired',creator "
                   "FROM pairings WHERE expires <= ? AND state != 'confirmed'", (now,))
        db.execute("DELETE FROM pairings WHERE expires <= ?", (now,))

    def cleanup(self, now):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            db.execute("DELETE FROM events WHERE at < ?", (now - self.settings.event_retention_ms,))
            db.execute("DELETE FROM events WHERE id <= (SELECT coalesce(max(id),0) FROM events) - ?", (self.settings.max_events,))

    # ----- device authentication -------------------------------------------------------------

    def authorize(self, identity, now, registration=False, pairing=None):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            existing = db.execute("SELECT key, blocked FROM devices WHERE id=?", (identity.id,)).fetchone()
            if not registration and existing is None:
                raise RelayError(403, "registration_required")
            if existing and existing[0] != identity.key:
                raise RelayError(401, "invalid_authentication")
            if existing and existing[1]:
                raise RelayError(403, "device_blocked")
            if db.execute("SELECT 1 FROM nonces WHERE device=? AND nonce=?", (identity.id, identity.nonce)).fetchone():
                raise RelayError(409, "replayed_request")
            own = db.execute("SELECT count(*) FROM nonces WHERE device=? AND received>?", (identity.id, now - 60_000)).fetchone()[0]
            total = db.execute("SELECT count(*) FROM nonces WHERE received>?", (now - 60_000,)).fetchone()[0]
            if own >= self.settings.requests_per_minute or total >= self.settings.global_requests_per_minute:
                raise RelayError(429, "rate_limit")
            # Future-dated valid requests remain replayable until timestamp+skew.
            db.execute("INSERT INTO nonces VALUES(?,?,?,?)", (identity.id, identity.nonce, now, max(now + 60_000, identity.timestamp + self.settings.skew_ms)))
            if existing:
                db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now, identity.id))

    def open_pairing(self, pairing, now):
        """True when an unexpired, unjoined pairing exists: its QR admits one new device."""
        with self.transaction() as db:
            return db.execute("SELECT 1 FROM pairings WHERE id=? AND state='open' AND expires>?", (pairing, now)).fetchone() is not None

    def register(self, identity, now, metadata):
        with self.transaction() as db:
            existing = db.execute("SELECT key FROM devices WHERE id=?", (identity.id,)).fetchone()
            if existing:
                if existing[0] != identity.key:
                    raise RelayError(409, "identity_conflict")
            else:
                if db.execute("SELECT count(*) FROM devices").fetchone()[0] >= self.settings.max_devices:
                    raise RelayError(507, "registration_capacity")
                db.execute("INSERT INTO devices(id,key,created,last_seen) VALUES(?,?,?,?)", (identity.id, identity.key, now, now))
                self.event(db, now, "registered", identity.id)
            db.execute("UPDATE devices SET platform=?, model=?, app_version=?, last_seen=? WHERE id=?",
                       (metadata.get("platform", ""), metadata.get("model", ""), metadata.get("appVersion", ""), now, identity.id))

    # ----- allowlists and links ----------------------------------------------------------------

    def peer(self, recipient, sender, now=0, remove=False):
        with self.transaction() as db:
            if remove:
                removed = db.execute("DELETE FROM peers WHERE recipient=? AND sender=?", (recipient, sender)).rowcount
                db.execute("DELETE FROM messages WHERE recipient=? AND sender=?", (recipient, sender))
                if removed:
                    self.event(db, now, "unlinked", recipient, sender)
                return
            if db.execute("SELECT 1 FROM peers WHERE recipient=? AND sender=?", (recipient, sender)).fetchone():
                return
            if db.execute("SELECT count(*) FROM peers WHERE recipient=?", (recipient,)).fetchone()[0] >= self.settings.max_peers:
                raise RelayError(429, "peer_limit")
            db.execute("INSERT INTO peers(recipient,sender,created) VALUES(?,?,?)", (recipient, sender, now))

    # ----- sandbox messages --------------------------------------------------------------------

    def enqueue(self, envelope, body, now):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            recipient, sender = envelope["recipientId"], envelope["senderId"]
            if not db.execute("SELECT 1 FROM devices WHERE id=? AND blocked=0", (recipient,)).fetchone() or not db.execute("SELECT 1 FROM peers WHERE recipient=? AND sender=?", (recipient, sender)).fetchone():
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
            db.execute("INSERT INTO messages(id,sender,recipient,expires,sequence,body,created) VALUES(?,?,?,?,?,?,?)",
                       (envelope["id"], sender, recipient, envelope["expiresAt"], envelope["sequence"], body, now))
            db.execute("INSERT INTO dedupe VALUES(?,?,?)", (envelope["id"], digest, envelope["expiresAt"]))
            db.execute("UPDATE devices SET sent_count=sent_count+1, sent_bytes=sent_bytes+? WHERE id=?", (len(body), sender))
            self.event(db, now, "sent", sender, recipient, len(body))
            return True

    def mailbox(self, recipient, now, excluded=(), limit=20):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            excluded_clause = " AND id NOT IN (" + ",".join("?" for _ in excluded) + ")" if excluded else ""
            return [row[0] for row in db.execute(
                "SELECT body FROM messages WHERE recipient=?" + excluded_clause + " ORDER BY sender,sequence DESC,id LIMIT ?",
                (recipient, *excluded, limit),
            )]

    def message_for(self, recipient, message, now):
        """One waiting envelope for its recipient (notification previews); does not acknowledge."""
        with self.transaction() as db:
            row = db.execute("SELECT body FROM messages WHERE id=? AND recipient=? AND expires>?", (message, recipient, now)).fetchone()
            return row and row[0]

    def acknowledge(self, recipient, message, now=0):
        with self.transaction() as db:
            # Idempotent and does not reveal existence/ownership of other messages.
            row = db.execute("DELETE FROM messages WHERE id=? AND recipient=? RETURNING sender, length(body), created",
                             (message, recipient)).fetchone()
            if row:
                db.execute("UPDATE devices SET received_count=received_count+1, received_bytes=received_bytes+? WHERE id=?", (row[1], recipient))
                self.event(db, now, "delivered", recipient, row[0], row[1], max(0, now - row[2]) if row[2] else None)

    # ----- QR pairing rendezvous ---------------------------------------------------------------

    def create_pairing(self, creator, pairing, now, expires):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            if db.execute("SELECT 1 FROM pairings WHERE id=?", (pairing,)).fetchone():
                raise RelayError(409, "pairing_exists")
            if db.execute("SELECT count(*) FROM pairings WHERE creator=? AND state='open'", (creator,)).fetchone()[0] >= self.settings.open_pairings_per_device:
                raise RelayError(429, "pairing_limit")
            db.execute("INSERT INTO pairings(id,creator,created,expires,state) VALUES(?,?,?,?,'open')", (pairing, creator, now, expires))
            self.event(db, now, "pairing_created", creator)

    def join_pairing(self, joiner, pairing, sealed, now):
        with self.transaction() as db:
            self.cleanup_in(db, now)
            row = db.execute("SELECT creator, state FROM pairings WHERE id=?", (pairing,)).fetchone()
            if row is None:
                raise RelayError(404, "pairing_not_found")
            if row[0] == joiner:
                raise RelayError(400, "self_pairing")
            if row[1] != "open":
                raise RelayError(409, "pairing_used")
            db.execute("UPDATE pairings SET state='joined', joiner=?, join_sealed=? WHERE id=?", (joiner, sealed, pairing))

    def confirm_pairing(self, creator, pairing, sealed, now):
        with self.transaction() as db:
            row = db.execute("SELECT creator, state, joiner FROM pairings WHERE id=? AND expires>?", (pairing, now)).fetchone()
            if row is None or row[0] != creator:
                raise RelayError(404, "pairing_not_found")
            if row[1] != "joined":
                raise RelayError(409, "pairing_not_joined")
            db.execute("UPDATE pairings SET state='confirmed', confirm_sealed=? WHERE id=?", (sealed, pairing))
            # The creator authorized the joiner by confirming; the joiner allows the creator itself.
            db.execute("INSERT OR IGNORE INTO peers(recipient,sender,created) VALUES(?,?,?)", (creator, row[2], now))
            self.event(db, now, "linked", creator, row[2])

    def pairing_status(self, caller, pairing, now):
        with self.transaction() as db:
            row = db.execute("SELECT creator, state, joiner, join_sealed, confirm_sealed FROM pairings WHERE id=? AND expires>?",
                             (pairing, now)).fetchone()
            if row is None or caller not in (row[0], row[2]):
                raise RelayError(404, "pairing_not_found")
            creator, state, joiner, join_sealed, confirm_sealed = row
            if caller == creator:
                return {"state": state, "joinerId": joiner, "sealed": join_sealed}
            if state == "confirmed":
                # The joiner has everything it needs; the rendezvous is finished.
                db.execute("DELETE FROM pairings WHERE id=?", (pairing,))
            return {"state": state, "joinerId": joiner, "sealed": confirm_sealed}

    def cancel_pairing(self, caller, pairing):
        with self.transaction() as db:
            db.execute("DELETE FROM pairings WHERE id=? AND (creator=? OR joiner=?)", (pairing, caller, caller))

    # ----- push --------------------------------------------------------------------------------

    def set_push(self, device, token, environment, topic):
        with self.transaction() as db:
            db.execute("UPDATE devices SET push_token=?, push_env=?, push_topic=? WHERE id=?", (token, environment, topic, device))

    def push_target(self, device):
        with self.transaction() as db:
            row = db.execute("SELECT push_token, push_env, push_topic FROM devices WHERE id=? AND push_token IS NOT NULL", (device,)).fetchone()
            return row and {"token": row[0], "environment": row[1], "topic": row[2]}

    def clear_push_token(self, token):
        with self.transaction() as db:
            db.execute("UPDATE devices SET push_token=NULL, push_env=NULL, push_topic=NULL WHERE push_token=?", (token,))
