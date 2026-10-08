import base64
import hashlib
import re
import uuid
from dataclasses import dataclass

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .errors import RelayError


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def uuid_string(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def decode64(value, limit):
    if not isinstance(value, str) or len(value) > ((limit + 2) // 3) * 4:
        raise ValueError("invalid base64")
    result = base64.b64decode(value, validate=True)
    if len(result) > limit or base64.b64encode(result).decode() != value:
        raise ValueError("invalid base64")
    return result


@dataclass(frozen=True)
class Identity:
    id: str
    key: bytes
    nonce: str
    timestamp: int


def authenticate(headers, method, target, body, now, skew):
    try:
        # Duplicate security headers are ambiguous between HTTP intermediaries.
        names = ("x-device-key", "x-device-time", "x-device-nonce", "x-device-signature")
        if any(len(headers.getlist(name)) != 1 for name in names):
            raise ValueError("headers")
        time = headers["x-device-time"]
        nonce = headers["x-device-nonce"]
        if not re.fullmatch(r"[0-9]{1,16}", time) or not uuid_string(nonce):
            raise ValueError("metadata")
        timestamp = int(time)
        if abs(timestamp - now) > skew:
            raise ValueError("time")
        der = decode64(headers["x-device-key"], 128)
        key = serialization.load_der_public_key(der)
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            raise ValueError("curve")
        if key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != der:
            raise ValueError("canonical key")
        signature = decode64(headers["x-device-signature"], 80)
        canonical = f"DeviceLink relay request v1\n{method}\n{target}\n{time}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
        key.verify(signature, canonical.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return Identity(hashlib.sha256(der).hexdigest(), der, nonce, timestamp)
    except (ValueError, InvalidSignature, UnsupportedAlgorithm, TypeError):
        raise RelayError(401, "invalid_authentication") from None
