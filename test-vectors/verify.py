# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Shotmetrix Kemmer GmbH
"""python3 test-vectors/verify.py — the Python encoder must reproduce every valid vector bit for bit
and reject every bad input with exactly the expected message."""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "python"))
from shotmetrix_qrcode import plaintext, seal, base32

v = json.load(open(os.path.join(os.path.dirname(__file__), "test-vectors.json"), encoding="utf-8"))
failures = 0
for t in v["valid"]:
    pt = plaintext(t["discipline"], t["decimal"], t["time"], t["shots"], t["app"],
                   t["app_secret"].encode() if t["app_secret"] else None, bytes.fromhex(t["id_hex"]), bytes(840), now=v["now"])
    code = base32(seal(pt, v["test_public_key"], bytes.fromhex(t["ephemeral_private_hex"])))
    ok = pt.hex() == t["plaintext_hex"] and code == t["code"]
    failures += not ok
    print(("ok  " if ok else "FAIL") + " " + t["name"] + ("" if pt.hex() == t["plaintext_hex"] else "  (plaintext differs)"))
for t in v["input_errors"]:
    try:
        plaintext(t["discipline"], t["decimal"], t["time"], t["shots"], t["app"], now=v["now"])
        message = "(accepted)"
    except ValueError as e:
        message = str(e)
    ok = message == t["error"]
    failures += not ok
    print(("ok  " if ok else "FAIL") + " input error " + t["name"] + ("" if ok else "  → " + message))
sys.exit(1 if failures else 0)
