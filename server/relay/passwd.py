"""Print a PBKDF2 hash for RELAY_ADMIN_PASSWORD_HASH: python -m relay.passwd"""
import getpass

from .admin import hash_password

if __name__ == "__main__":
    first = getpass.getpass("Admin password: ")
    if len(first) < 10:
        raise SystemExit("Use at least 10 characters.")
    if getpass.getpass("Repeat: ") != first:
        raise SystemExit("Passwords differ.")
    print(hash_password(first))
