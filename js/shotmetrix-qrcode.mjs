// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// Shotmetrix QR Code, version 1 — reference encoder in JavaScript.
// WebCrypto only (X25519, HKDF-SHA-256, AES-256-GCM): runs in current browsers and in Node ≥ 20, no dependencies.
// Format specification: ../SPEC.md
//
//   import { qrCodeUrl } from './shotmetrix-qrcode.mjs';
//   const url = await qrCodeUrl({ publicKey: SHOTMETRIX_PUBLIC_KEY, discipline: 1, decimal: true,
//                                  time: new Date(), shots: [{ x: 0.42, y: -1.1, value: 10.6, time: new Date() }, …] });
//   // url → put into a QR code in alphanumeric mode, error correction level L

export const VERSION = 1;
export const PLAINTEXT_BYTES = 924;     // 36 header + 120 × 7 shots + 48 zero bytes (reserved)
export const MAX_SHOTS = 120;
export const SHOT_BYTES = 7;
export const URL_PREFIX = 'https://shotmetrix.com/api/qrcode/';
const INFO = 'shotmetrix-qrcode-v1';
const UNITS = [0.01, 0.1, 0.2];          // mm per step
const NO_POS = 0x800;                    // −2048 in 12 bits: shot without a position
const NO_TIME = 0xFFFFFF;                // shot without a time

const subtle = globalThis.crypto.subtle;
/** Rounding as the spec defines it: halves round away from zero (Math.round would turn −2.5 into −2). */
const roundHalfAway = v => Math.sign(v) * Math.round(Math.abs(v));
const randomBytes = n => globalThis.crypto.getRandomValues(new Uint8Array(n));

/** Base32 per RFC 4648, upper case, without "=" padding. */
export function base32(bytes) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let out = '', buffer = 0, bits = 0;
  for (const b of bytes) {
    buffer = (buffer << 8) | b; bits += 8;
    while (bits >= 5) { bits -= 5; out += alphabet[(buffer >> bits) & 31]; }
    buffer &= (1 << bits) - 1;
  }
  if (bits > 0) out += alphabet[(buffer << (5 - bits)) & 31];
  return out;
}

const b64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
const fail = message => { throw new RangeError(message); };
const isNumber = v => typeof v === 'number' && Number.isFinite(v);

/**
 * The disciplines: zone width per target in mm (25 m pistol: precision and rapid-fire target) and the allowed
 * difference between value and position in tenths; null = not checked (rapid fire). SPEC.md, section 5.
 */
const DISCIPLINES = {
  1: [[0.25], 3], 2: [[0.25], 3], 3: [[0.8], 3], 4: [[0.8], 3], 5: [[0.8], 3], 6: [[0.8], 3], 7: [[0.8], 3],
  8: [[2.5, 4.0], 10], 9: [null, null], 10: [[2.5], 10], 11: [[2.5], 10], 12: [[2.5], 10],
};

/** Value in tenths that a position yields (SPEC.md, section 7). */
function valueFromPosition(x, y, zone) {
  const v = 110 - Math.ceil(Math.floor(Math.sqrt(x * x + y * y) / zone * 1e6 + 0.5) / 1e6);   // as on the server: round to 6 decimal places, then round up
  return v > 109 ? 109 : v < 10 ? 0 : v;
}

/** Is t a valid Date or a number of Unix seconds? */
const isTimestamp = t => t instanceof Date ? Number.isFinite(t.getTime()) : isNumber(t);
/** Shot time → hundredths of a second since start (a Date goes via milliseconds, numbers as in the other encoders). */
const offsetHundredths = (t, start) => t instanceof Date ? roundHalfAway((t.getTime() - start * 1000) / 10) : roundHalfAway((t - start) * 100);

/**
 * Builds the 924-byte plaintext and checks every rule from SPEC.md, section 7, plus the limits of the
 * format; invalid input is rejected instead of producing a code that Shotmetrix would refuse.
 * input: { discipline (1–12), decimal (bool), time (Date|Unix seconds: start of the session),
 *          shots: [{x, y (mm, +y up; both null = no position), value (e.g. 10.6), sighter, time (Date|Unix seconds, optional)}],
 *          app?: number, appSecret?: Uint8Array, id?: Uint8Array(16), now?: Unix seconds, fill?: Uint8Array (tests only) }
 * Errors: RangeError with an English message ("shot 3: value out of range").
 */
export async function plaintext(input) {
  const shots = Array.isArray(input.shots) ? input.shots : [];
  if (shots.length < 1 || shots.length > MAX_SHOTS) fail('shots: 1 to 120 shots');
  const rules = DISCIPLINES[input.discipline];
  if (!Number.isInteger(input.discipline) || !rules) fail('discipline: unknown number');
  const now = isNumber(input.now) ? input.now : Date.now() / 1000;
  const start = !isTimestamp(input.time) ? NaN : Math.floor(input.time instanceof Date ? input.time.getTime() / 1000 : input.time);
  if (!(start >= 946684800 && start <= now + 86400)) fail('time: session start must be after 2000-01-01 and not in the future');
  const app = input.app ?? 0;
  if (!Number.isInteger(app) || app < 0 || app > 65535) fail('app: 0 to 65535');
  if (input.id != null && input.id.length !== 16) fail('id: 16 bytes');

  // Every shot: value, position, time
  const values = [], times = [];
  let max = 0, previous = -1, scored = 0;
  shots.forEach((s, i) => {
    const prefix = 'shot ' + (i + 1) + ': ';
    if (s === null || typeof s !== 'object' || !isNumber(s.value)) fail(prefix + 'value must be a number');
    if (Math.abs(s.value) > 1000) fail(prefix + 'value out of range');
    const value = input.decimal ? roundHalfAway(s.value * 10) : Math.floor(s.value) * 10;
    if (!(input.decimal ? value === 0 || (value >= 10 && value <= 109) : value >= 0 && value <= 100)) fail(prefix + 'value out of range');
    if ((s.x == null) !== (s.y == null)) fail(prefix + 'give x and y together');
    if (s.x != null) {
      if (!isNumber(s.x) || !isNumber(s.y)) fail(prefix + 'position must be a number');
      if (Math.abs(s.x) > 409.4 || Math.abs(s.y) > 409.4) fail(prefix + 'position outside ±409.4 mm');
      max = Math.max(max, Math.abs(s.x), Math.abs(s.y));
    }
    let t = NO_TIME;
    if (s.time != null) {
      if (!isTimestamp(s.time)) fail(prefix + 'time must be a number or a date');
      t = offsetHundredths(s.time, start);
      if (!(t >= 0 && t < NO_TIME)) fail(prefix + 'time before the session start or more than 46 h after it');
      if (t < previous) fail(prefix + 'time earlier than the shot before');
      if (start + t / 100 > now + 86400) fail(prefix + 'time in the future');
      previous = t;
    }
    if (!s.sighter) scored++;
    values.push(value); times.push(t);
  });
  if (scored === 0) fail('no scored shot');
  const unit = UNITS.findIndex(u => max / u <= 2047);
  const mm = UNITS[unit];

  // Value and position must agree (as on the server, using the position as it is stored in the code)
  if (rules[1] !== null) {
    const tol = input.decimal ? rules[1] : Math.max(10, rules[1]);
    shots.forEach((s, i) => {
      if (s.sighter || s.x == null) return;
      const xq = roundHalfAway(s.x / mm) * mm, yq = roundHalfAway(s.y / mm) * mm;
      const matches = rules[0].some(zone => {
        let expected = valueFromPosition(xq, yq, zone);
        if (!input.decimal) expected = Math.floor(expected / 10) * 10;
        return Math.abs(expected - values[i]) <= tol;
      });
      if (!matches) fail('shot ' + (i + 1) + ': value does not match the position');
    });
  }

  const pt = new Uint8Array(PLAINTEXT_BYTES);
  const dv = new DataView(pt.buffer);
  pt.set(input.id || randomBytes(16), 0);
  dv.setUint16(16, app);
  dv.setUint32(26, start);
  pt[30] = input.discipline;
  pt[31] = (input.decimal ? 1 : 0) | (unit << 1);
  pt[32] = shots.length;
  let total = 0;
  shots.forEach((s, i) => {
    if (!s.sighter) total += values[i];
    let x = NO_POS, y = NO_POS;
    if (s.x != null) {
      x = roundHalfAway(s.x / mm) & 0xFFF;
      y = roundHalfAway(s.y / mm) & 0xFFF;
    }
    const o = 36 + SHOT_BYTES * i, t = times[i];
    dv.setUint32(o, ((x << 20) | (y << 8) | (values[i] << 1) | (s.sighter ? 1 : 0)) >>> 0);
    pt[o + 4] = t >>> 16; pt[o + 5] = (t >>> 8) & 255; pt[o + 6] = t & 255;
  });
  dv.setUint16(33, total);
  const remaining = SHOT_BYTES * (MAX_SHOTS - shots.length);
  pt.set(input.fill ? input.fill.subarray(0, remaining) : randomBytes(remaining), 36 + SHOT_BYTES * shots.length);
  if (app && input.appSecret) {
    const k = await subtle.importKey('raw', input.appSecret, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const mac = new Uint8Array(await subtle.sign('HMAC', k, pt));         // app_mark is still all zero at this point
    pt.set(mac.subarray(0, 8), 18);
  }
  return pt;
}

/**
 * Encrypts the plaintext for the public key (32 bytes raw or Base64). Result: 973 bytes.
 * ephemeralPrivate (32 bytes) is for test vectors only — otherwise every code gets a fresh one-time key.
 */
export async function seal(plaintext, publicKey, ephemeralPrivate) {
  let server;
  try { server = typeof publicKey === 'string' ? b64(publicKey) : publicKey; } catch { server = null; }
  if (!server || server.length !== 32) fail('publicKey: 32 bytes');
  if (ephemeralPrivate != null && ephemeralPrivate.length !== 32) fail('ephemeralPrivate: 32 bytes');
  let priv;
  if (ephemeralPrivate) {
    const pkcs8 = new Uint8Array([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20, ...ephemeralPrivate]);
    priv = await subtle.importKey('pkcs8', pkcs8, { name: 'X25519' }, true, ['deriveBits']);
  } else {
    priv = (await subtle.generateKey({ name: 'X25519' }, true, ['deriveBits'])).privateKey;
  }
  const jwk = await subtle.exportKey('jwk', priv);
  const ephemeral = Uint8Array.from(atob(jwk.x.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  const serverKey = await subtle.importKey('raw', server, { name: 'X25519' }, false, []);
  const shared = new Uint8Array(await subtle.deriveBits({ name: 'X25519', public: serverKey }, priv, 256));
  const hk = await subtle.importKey('raw', shared, 'HKDF', false, ['deriveBits']);
  const salt = new Uint8Array(64); salt.set(ephemeral, 0); salt.set(server, 32);
  const okm = new Uint8Array(await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info: new TextEncoder().encode(INFO) }, hk, 44 * 8));
  const key = await subtle.importKey('raw', okm.subarray(0, 32), 'AES-GCM', false, ['encrypt']);
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv: okm.subarray(32, 44), additionalData: new Uint8Array([VERSION]), tagLength: 128 }, key, plaintext));
  const code = new Uint8Array(1 + 32 + ct.length);
  code[0] = VERSION; code.set(ephemeral, 1); code.set(ct, 33);
  return code;
}

/** All in one: input → URL for the QR code. */
export async function qrCodeUrl(input) {
  const code = await seal(await plaintext(input), input.publicKey, input.ephemeralPrivate);
  return URL_PREFIX + base32(code);
}
