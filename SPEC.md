# Shotmetrix QR Code — Specification, version 1

A Shotmetrix QR code carries the shots of one training session from your app to
[Shotmetrix](https://shotmetrix.com) as a QR code. The shooter scans it. The code opens a Shotmetrix
page that shows the session, and they save it to their account there. Each code can be redeemed
**once**.

The format is open. You do not need to register to produce codes.

The rest of this document says exactly what to put into the code, so an encoder in any language
produces the same bytes. Reference encoders for JavaScript, Python, Swift, Kotlin and PHP are in this
repository, together with test vectors and the discipline table as JSON (`disciplines.json`).

---

## 1. Overview

```
QR code (alphanumeric mode, error correction L, always version 24 = 113×113 modules)
└── URL      https://shotmetrix.com/api/qrcode/<CODE>
    └── CODE Base32 of 973 bytes
        ├── version            1 byte   = 0x01
        ├── ephemeral key     32 bytes  X25519 public key, new for every code
        ├── ciphertext       924 bytes  AES-256-GCM of the plaintext
        └── tag               16 bytes  GCM authentication tag
```

Because the plaintext always has 924 bytes, every code has the same length (1557 Base32 characters,
1591 characters with the URL). The QR code is therefore the same size for 1 shot and for 120: a QR
library that picks the size itself always lands on version 24. (The length is chosen to stay clear of
version 23, which the widely used web decoder jsQR cannot read.)

The code is encrypted with Shotmetrix' **public** key, so only Shotmetrix can read it. Any change to it
makes it invalid. Each code contains a random 16-byte ID, and Shotmetrix accepts every ID once.

**What the encryption does *not* prove:** anyone holding the public key can produce a code, so a code
alone does not prove that a result is real. That is why imported results count for training only
(statistics, shot pattern, logbook), not for leaderboards, competitions or duels — unless the code
carries the mark of a registered app (section 6).

## 2. URL and QR code

```
https://shotmetrix.com/api/qrcode/<CODE>
```

- **The code stays upper case.** Base32 (`A–Z`, `2–7`) is in the QR alphanumeric character set, which
  takes about 20 % less space than byte mode. The prefix before it is a short byte segment. QR libraries
  that split text into segments by themselves (iOS CoreImage, `qrcode` for JavaScript and Python) produce
  version 24.
- **If your library uses one mode for the whole text** (for example ZXing without `QR_COMPACT`), write
  the whole URL in upper case: `HTTPS://SHOTMETRIX.COM/API/QRCODE/<CODE>`. Shotmetrix accepts both.
  A lower-case URL in byte mode would need version 29.
- **Error correction L.** It is enough on a phone screen and keeps the code at version 24.
  Use M (version 27) only for printed codes.
- **Quiet zone:** 4 modules.
- **Minimum size on screen:** about 7 cm (0.6 mm per module). Show it at full brightness.

## 3. Key exchange and encryption

Shotmetrix publishes a 32-byte X25519 public key `S` (Base64; see `keys/`).

1. Generate a fresh X25519 key pair `(e, E)` for **every** code. Never reuse it.
2. `shared = X25519(e, S)` (32 bytes).
3. `okm = HKDF-SHA-256(IKM = shared, salt = E ‖ S, info = "shotmetrix-qrcode-v1", L = 44)`
   - `key = okm[0..31]`
   - `nonce = okm[32..43]`
4. `ciphertext ‖ tag = AES-256-GCM(key, nonce, plaintext, AAD = 0x01)`, with a 128-bit tag.
5. `code = 0x01 ‖ E ‖ ciphertext ‖ tag` (973 bytes).
6. `URL = "https://shotmetrix.com/api/qrcode/" + Base32(code)`, using RFC 4648 upper case without `=` padding.

Most platforms ship all three primitives; in Python they come from the `cryptography` package:

| Platform | X25519 | HKDF | AES-GCM |
|---|---|---|---|
| iOS / macOS (CryptoKit) | `Curve25519.KeyAgreement` | `hkdfDerivedSymmetricKey` | `AES.GCM` |
| Browser, Node ≥ 20 (WebCrypto) | `X25519` | `HKDF` | `AES-GCM` |
| JVM 11+ | `XDH` | HMAC (5 lines) | `AES/GCM/NoPadding` |
| Python | `cryptography` | `HKDF` | `AESGCM` |
| PHP (`sodium`, `openssl`) | `sodium_crypto_scalarmult` | `hash_hkdf` | `openssl_encrypt` (`aes-256-gcm`) |

On Android, X25519 is not available in every version; where it is missing, Tink or BouncyCastle provide it.
The versions in this table are where the primitives exist, not tested minimums — the reference encoders are
tested with the versions listed under *Tested with* in README.md (the Kotlin encoder on the JVM only, not on Android).

## 4. Plaintext (924 bytes)

All multi-byte integers are **big-endian**.

| Offset | Size | Field | Meaning |
|---|---|---|---|
| 0 | 16 | `id` | Random. Makes the code unique; Shotmetrix accepts each id once. |
| 16 | 2 | `app` | Your app number (section 6), `0` if none. |
| 18 | 8 | `app_mark` | Section 6; all zero without an app number. |
| 26 | 4 | `time` | Start of the session, Unix seconds (UTC). |
| 30 | 1 | `discipline` | Number from the table in section 5. |
| 31 | 1 | `flags` | Bit 0: `1` = decimal scoring, `0` = whole rings. Bits 1–2: position unit (`0` = 0.01 mm, `1` = 0.1 mm, `2` = 0.2 mm). Bits 3–7: `0`. |
| 32 | 1 | `count` | Number of shots, 1–120 (sighters included). |
| 33 | 2 | `total` | Sum of the scored (non-sighter) shot values, in tenths. |
| 35 | 1 | — | `0` |
| 36 | 840 | `shots` | 120 slots × 7 bytes, in the order the shots were fired. Slots after `count` are filled with random bytes. |
| 876 | 48 | — | Reserved, all `0`. Keeps the QR code at version 24. |

**Shot (7 bytes):**

```
bytes 0–3   (32 bits)
  bits 31–20  x      12-bit two's complement, in units (flags bits 1–2), right = +
  bits 19–8   y      12-bit two's complement, in units, UP = +
  bits  7–1   value  0–109 = score in tenths (10.6 → 106); whole rings: ring × 10
  bit   0     sighter
bytes 4–6   (24 bits)
  t           time of the shot in hundredths of a second after `time`; 0xFFFFFF = no time
```

- Positions are measured from the centre of the target to the centre of the shot, in millimetres.
- **+y is up**, as you look at the target.
- A shot without a position has `x = y = −2048` (`0x800`).
- Use the finest unit that fits every position: 0.01 mm up to ±20.47 mm, 0.1 mm up to ±204.7 mm,
  0.2 mm up to ±409.4 mm.
- **Shot time:** `t = round((shot time − time) × 100)`, both in Unix seconds. `t` covers 0 to
  167 772.14 s (46.6 hours) after the start of the session. Shots before `time` cannot be encoded, so set
  `time` to the start of the session (at the latest, the first shot). Shots without a time (for example
  entered by hand) get `0xFFFFFF`; a code may mix both. If your system only knows whole seconds, send
  whole seconds (`t` is then a multiple of 100).
- **Rounding:** `round(position / unit)`, `round(value × 10)` and `round(seconds × 100)` round halves
  **away from zero**, computed in IEEE double. Note that JavaScript's `Math.round` and Python's
  `round()` do not; the reference encoders show how to do it.
- A miss has value `0`. Decimal scoring has no values between 0.1 and 0.9.

## 5. Disciplines

These are the disciplines Shotmetrix runs, with their Shotmetrix discipline code. The numbers are part
of the format: they are never reassigned, and new ones are only appended. `disciplines.json` has the
same table as data.

| No. | Shotmetrix code | Discipline | Distance | Calibre | Target | Zone (mm) | Score check |
|---|---|---|---|---|---|---|---|
| 1 | `10m_air_rifle` | 10m Air Rifle | 10 m | 4.5 mm | air_rifle | 0.25 | ±0.3 |
| 2 | `10m_air_rifle_supported` | 10m Air Rifle (supported) | 10 m | 4.5 mm | air_rifle | 0.25 | ±0.3 |
| 3 | `10m_air_pistol` | 10m Air Pistol | 10 m | 4.5 mm | air_pistol | 0.8 | ±0.3 |
| 4 | `10m_air_pistol_supported` | 10m Air Pistol (supported) | 10 m | 4.5 mm | air_pistol | 0.8 | ±0.3 |
| 5 | `50m_rifle_prone` | 50m Rifle Prone | 50 m | 5.6 mm | rifle_50m | 0.8 | ±0.3 |
| 6 | `50m_rifle_3_positions` | 50m Rifle 3 Positions | 50 m | 5.6 mm | rifle_50m | 0.8 | ±0.3 |
| 7 | `50m_rifle_supported` | 50m Rifle (supported) | 50 m | 5.6 mm | rifle_50m | 0.8 | ±0.3 |
| 8 | `25m_pistol` | 25m Pistol | 25 m | 5.6 mm | precision + rapid_fire | 2.5 / 4 | ±1 |
| 9 | `25m_rapid_fire_pistol` | 25m Rapid Fire Pistol | 25 m | 5.6 mm | rapid_fire | 4 | — |
| 10 | `25m_standard_pistol` | 25m Standard Pistol | 25 m | 5.6 mm | precision | 2.5 | ±1 |
| 11 | `50m_pistol` | 50m Pistol | 50 m | 5.6 mm | precision | 2.5 | ±1 |
| 12 | `25m_pistol_supported` | 25m Pistol (supported) | 25 m | 5.6 mm | precision | 2.5 | ±1 |

**Targets** (ISSF rule 6.3.4; diameters in mm; *ring step* is the difference between the radii of two
neighbouring rings; *zone* is the ring step divided by 10, the width of one tenth):

| Target | | Ten ring Ø | Inner ten Ø | Ring step | Black Ø | Lowest ring / Ø | Zone |
|---|---|---|---|---|---|---|---|
| `air_rifle` | 10m air rifle target | 0.5 | — | 2.5 | 30.5 | 1 / 45.5 | 0.25 |
| `air_pistol` | 10m air pistol target | 11.5 | 5 | 8 | 59.5 | 1 / 155.5 | 0.8 |
| `rifle_50m` | 50m rifle target | 10.4 | 5 | 8 | 112.4 | 1 / 154.4 | 0.8 |
| `precision` | 25m precision and 50m pistol target | 50 | 25 | 25 | 200 | 1 / 500 | 2.5 |
| `rapid_fire` | 25m rapid fire target | 100 | 50 | 40 | 500 | 5 / 500 | 4 |

The 25 m pistol (no. 8) is shot on two targets: the precision stage on `precision`, the rapid-fire
stage on `rapid_fire`. A shot is accepted if its value fits either target. 25 m rapid fire (no. 9) is
not checked against the position.

## 6. Registered apps (optional)

Ask Shotmetrix to register your app and you receive an app number and a 32-byte secret. Fill
`app_mark` after building the plaintext with the mark still zero:

```
app_mark = HMAC-SHA-256(secret, plaintext with app_mark = 0)[0..7]
```

Codes with a valid mark show your app's name as the source. Shotmetrix can let such results count
like those from an electronic scoring system.

A secret shipped inside an app can be extracted with some effort. If that happens, Shotmetrix blocks
the app, and its codes are rejected from then on.

## 7. Rules a code must follow

| Rule | |
|---|---|
| values | 0 or 1.0–10.9; with whole-ring scoring, whole rings only. |
| total | `total` equals the sum of the scored shot values; at least one scored shot. |
| value and position | Agree within the score check in section 5 (shots without a position are not checked). |
| shot times | In the order of the shots; equal times are fine, shots without a time are skipped. |
| time | The session starts after 2000-01-01; the session and its last shot are not more than a day in the future. |

The score check computes the value from the distance `d` between the shot centre and the target
centre: `value = 11.0 − ceil(d / zone) / 10`, at most 10.9, and 0 below 1.0, with the zone from
section 5. `d` uses the position as encoded (after rounding to the unit), and `d / zone` is rounded
to six decimal places before `ceil`.

The reference encoders check all of these rules and refuse to build a code that breaks one, with an
English error message (for example `shot 3: value out of range`). They also refuse input the format
cannot hold: more than 120 shots, a position outside ±409.4 mm, only x or only y, a shot time before
the session start or more than 46.6 hours after it, an app number above 65535, numbers that are not
finite.

## 8. Testing

`test-vectors/test-vectors.json` contains

- `valid`: valid codes (with a fixed id, ephemeral key and zero filling), each with its plaintext as hex,
- `rejected`: codes that Shotmetrix rejects, each with the reason (`expected_error`),
- `input_errors`: inputs an encoder must refuse, each with the error message of the reference encoders.

Shot times in the vectors are absolute Unix seconds; `now` is the time to check against. Every
reference encoder reproduces the valid codes bit for bit and refuses every faulty input with exactly
the listed message:

```
node test-vectors/verify.mjs
python3 test-vectors/verify.py
php test-vectors/verify.php
swiftc -parse-as-library swift/ShotmetrixQRCode.swift test-vectors/Verify.swift -o verify && ./verify test-vectors/test-vectors.json
kotlinc kotlin/ShotmetrixQRCode.kt test-vectors/Verify.kt -include-runtime -d verify.jar && java -jar verify.jar test-vectors/test-vectors.json
```

The vectors use a **test key**. Its private half is published in the file, so real servers do not
accept codes made with it. For production, use the key in `keys/shotmetrix-public-key.txt`.
