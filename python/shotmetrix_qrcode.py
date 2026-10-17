# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Shotmetrix Kemmer GmbH
"""Shotmetrix QR Code, version 1 — reference encoder in Python (package "cryptography").

    from shotmetrix_qrcode import qr_code_url
    url = qr_code_url(public_key=SHOTMETRIX_PUBLIC_KEY, discipline=1, decimal=True, time=1791900000,
                      shots=[{"x": 0.42, "y": -1.1, "value": 10.6, "time": 1791900312.4}, ...])

time = start of the session; the time of a shot is optional. Both are Unix seconds or a datetime.

Format description: ../SPEC.md
"""
import base64, hashlib, hmac, math, os, struct
import time as _time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 1
PLAINTEXT_BYTES = 924   # 36 header + 120 × 7 shots + 48 zero bytes (reserved)
MAX_SHOTS = 120
SHOT_BYTES = 7
URL_PREFIX = "https://shotmetrix.com/api/qrcode/"
INFO = b"shotmetrix-qrcode-v1"
UNITS = (0.01, 0.1, 0.2)
NO_POS = 0x800
NO_TIME = 0xFFFFFF


def round_half_away(v: float) -> int:
    """Rounds halves away from zero — Python's round() rounds them to the even number."""
    return int(math.copysign(math.floor(abs(v) + 0.5), v))


# The disciplines: zone width per target in mm (25 m pistol: precision and rapid-fire target) and the allowed
# difference between value and position in tenths; None = not checked (rapid fire). SPEC.md, section 5.
DISCIPLINES = {1: ((0.25,), 3), 2: ((0.25,), 3), 3: ((0.8,), 3), 4: ((0.8,), 3), 5: ((0.8,), 3), 6: ((0.8,), 3),
               7: ((0.8,), 3), 8: ((2.5, 4.0), 10), 9: (None, None), 10: ((2.5,), 10), 11: ((2.5,), 10), 12: ((2.5,), 10)}


def is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def to_timestamp(t):
    """datetime or Unix seconds → seconds; None for anything else."""
    if hasattr(t, "timestamp"):
        return t.timestamp()
    return float(t) if is_number(t) else None


def value_from_position(x: float, y: float, zone: float) -> int:
    """Value in tenths that a position gives (SPEC.md, section 7): rounded to 6 decimal places, then rounded up."""
    tenths = 110 - math.ceil(math.floor(math.sqrt(x * x + y * y) / zone * 1e6 + 0.5) / 1e6)
    return 109 if tenths > 109 else 0 if tenths < 10 else tenths


def base32(data: bytes) -> str:
    return base64.b32encode(data).decode().rstrip("=")


def plaintext(discipline, decimal, time, shots, app=0, app_secret=None, id=None, fill=None, now=None) -> bytes:
    """Builds the 924-byte plaintext and checks every rule from SPEC.md, section 7, plus the limits of the
    format; it rejects bad input instead of building a code that Shotmetrix would refuse.
    Errors: ValueError with an English message ("shot 3: value out of range")."""
    def fail(m):
        raise ValueError(m)
    if not isinstance(shots, (list, tuple)) or not 1 <= len(shots) <= MAX_SHOTS:
        fail("shots: 1 to 120 shots")
    if not isinstance(discipline, int) or isinstance(discipline, bool) or discipline not in DISCIPLINES:
        fail("discipline: unknown number")
    zones, tol = DISCIPLINES[discipline]
    now = now if is_number(now) else _time.time()
    start_ts = to_timestamp(time)
    start = math.floor(start_ts) if start_ts is not None else None
    if start is None or not 946684800 <= start <= now + 86400:
        fail("time: session start must be after 2000-01-01 and not in the future")
    if not isinstance(app, int) or isinstance(app, bool) or not 0 <= app <= 65535:
        fail("app: 0 to 65535")
    if id is not None and len(id) != 16:
        fail("id: 16 bytes")

    # Each shot: value, position, time
    values, times, m, last, scored = [], [], 0.0, -1, 0
    for i, s in enumerate(shots):
        n = f"shot {i + 1}: "
        if not isinstance(s, dict):
            fail(n + "value must be a number")
        v = s.get("value")
        if not is_number(v):
            fail(n + "value must be a number")
        if abs(v) > 1000:
            fail(n + "value out of range")
        value = round_half_away(v * 10) if decimal else math.floor(v) * 10
        if not ((value == 0 or 10 <= value <= 109) if decimal else 0 <= value <= 100):
            fail(n + "value out of range")
        x, y = s.get("x"), s.get("y")
        if (x is None) != (y is None):
            fail(n + "give x and y together")
        if x is not None:
            if not is_number(x) or not is_number(y):
                fail(n + "position must be a number")
            if abs(x) > 409.4 or abs(y) > 409.4:
                fail(n + "position outside ±409.4 mm")
            m = max(m, abs(x), abs(y))
        t = NO_TIME
        if s.get("time") is not None:
            shot_ts = to_timestamp(s["time"])
            if shot_ts is None:
                fail(n + "time must be a number or a date")
            t = round_half_away((shot_ts - start) * 100)
            if not 0 <= t < NO_TIME:
                fail(n + "time before the session start or more than 46 h after it")
            if t < last:
                fail(n + "time earlier than the shot before")
            if start + t / 100 > now + 86400:
                fail(n + "time in the future")
            last = t
        if not s.get("sighter"):
            scored += 1
        values.append(value)
        times.append(t)
    if scored == 0:
        fail("no scored shot")
    unit = next(i for i, u in enumerate(UNITS) if m / u <= 2047)
    mm = UNITS[unit]

    # Value and position must agree (as on the server, using the position as it is stored in the code)
    if tol is not None:
        limit = tol if decimal else max(10, tol)
        for i, s in enumerate(shots):
            if s.get("sighter") or s.get("x") is None:
                continue
            xq, yq = round_half_away(s["x"] / mm) * mm, round_half_away(s["y"] / mm) * mm
            matches = False
            for zone in zones:
                expected = value_from_position(xq, yq, zone)
                if not decimal:
                    expected = expected // 10 * 10
                if abs(expected - values[i]) <= limit:
                    matches = True
                    break
            if not matches:
                fail(f"shot {i + 1}: value does not match the position")

    body, total = b"", 0
    for i, s in enumerate(shots):
        if not s.get("sighter"):
            total += values[i]
        if s.get("x") is None:
            x = y = NO_POS
        else:
            x = round_half_away(s["x"] / mm) & 0xFFF
            y = round_half_away(s["y"] / mm) & 0xFFF
        body += struct.pack(">I", (x << 20) | (y << 8) | (values[i] << 1) | (1 if s.get("sighter") else 0))
        body += struct.pack(">I", times[i])[1:]
    rest = SHOT_BYTES * (MAX_SHOTS - len(shots))
    body += (fill[:rest] if fill is not None else os.urandom(rest))
    head = (id or os.urandom(16)) + struct.pack(">H", app) + b"\0" * 8 + struct.pack(">I", start) \
        + bytes([discipline, (1 if decimal else 0) | (unit << 1), len(shots)]) + struct.pack(">H", total) + b"\0"
    pt = head + body + bytes(48)
    if app and app_secret:
        mac = hmac.new(app_secret, pt, hashlib.sha256).digest()[:8]   # the app_mark field is still zero here
        pt = pt[:18] + mac + pt[26:]
    assert len(pt) == PLAINTEXT_BYTES
    return pt


def seal(pt: bytes, public_key, ephemeral_private: bytes = None) -> bytes:
    try:
        server = base64.b64decode(public_key, validate=True) if isinstance(public_key, str) else bytes(public_key)
    except Exception:
        server = b""
    if len(server) != 32:
        raise ValueError("publicKey: 32 bytes")
    if ephemeral_private is not None and len(ephemeral_private) != 32:
        raise ValueError("ephemeralPrivate: 32 bytes")
    priv = X25519PrivateKey.from_private_bytes(ephemeral_private) if ephemeral_private else X25519PrivateKey.generate()
    eph = priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    shared = priv.exchange(X25519PublicKey.from_public_bytes(server))
    okm = HKDF(algorithm=hashes.SHA256(), length=44, salt=eph + server, info=INFO).derive(shared)
    ct = AESGCM(okm[:32]).encrypt(okm[32:44], pt, bytes([VERSION]))       # ciphertext + 16-byte tag
    return bytes([VERSION]) + eph + ct


def qr_code_url(public_key, discipline, decimal, time, shots, app=0, app_secret=None) -> str:
    code = seal(plaintext(discipline, decimal, time, shots, app, app_secret), public_key)
    return URL_PREFIX + base32(code)
