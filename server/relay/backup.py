"""Consistent live backup of the relay database: python -m relay.backup DEST [--keep N]

Uses SQLite's online backup API, so the relay can keep running. DEST is a file, or a
directory that receives timestamped copies (oldest pruned beyond --keep). The copy holds
metadata and short-lived ciphertext only; keep it private and encrypted at rest anyway.
"""
import argparse
import os
import sqlite3
import sys
import time
from contextlib import closing
from pathlib import Path

from .config import Settings

PREFIX = "relay-"


def backup(source, destination):
    destination = Path(destination)
    temporary = destination.with_name(destination.name + ".partial")
    old = os.umask(0o077)
    try:
        with closing(sqlite3.connect(f"file:{source}?mode=ro", uri=True)) as src, closing(sqlite3.connect(temporary)) as dst:
            src.backup(dst)
            if dst.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("backup failed integrity check")
        os.replace(temporary, destination)
    finally:
        os.umask(old)
        temporary.unlink(missing_ok=True)
    return destination


def prune(directory, keep):
    copies = sorted(Path(directory).glob(f"{PREFIX}*.sqlite3"))
    for stale in copies[:max(0, len(copies) - keep)]:
        stale.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m relay.backup", description=__doc__.splitlines()[0])
    parser.add_argument("destination", help="backup file, or directory for timestamped copies")
    parser.add_argument("--keep", type=int, default=14, help="copies to keep in a directory destination (default 14)")
    args = parser.parse_args(argv)
    source = Settings.environment().database
    if not source.exists():
        sys.exit(f"No database at {source}")
    target = Path(args.destination)
    if target.is_dir():
        written = backup(source, target / f"{PREFIX}{time.strftime('%Y%m%d-%H%M%S')}.sqlite3")
        prune(target, max(1, args.keep))
    else:
        written = backup(source, target)
    print(written)


if __name__ == "__main__":
    main()
