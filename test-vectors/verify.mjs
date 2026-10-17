// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// node test-vectors/verify.mjs — the JavaScript encoder must reproduce every valid vector bit for bit
// and reject every invalid input with exactly the expected message.
import { readFileSync } from 'node:fs';
import { plaintext, seal, base32 } from '../js/shotmetrix-qrcode.mjs';
const v = JSON.parse(readFileSync(new URL('./test-vectors.json', import.meta.url)));
const hex = h => Uint8Array.from(h.match(/../g), b => parseInt(b, 16));
let failures = 0;
for (const t of v.valid) {
  const pt = await plaintext({ discipline: t.discipline, decimal: t.decimal, time: t.time, shots: t.shots, app: t.app,
    appSecret: t.app_secret ? new TextEncoder().encode(t.app_secret) : undefined, id: hex(t.id_hex), now: v.now, fill: new Uint8Array(840) });
  const ptHex = Buffer.from(pt).toString('hex');
  const code = base32(await seal(pt, v.test_public_key, hex(t.ephemeral_private_hex)));
  const ok = ptHex === t.plaintext_hex && code === t.code;
  if (!ok) failures++;
  console.log((ok ? 'ok  ' : 'FAIL') + ' ' + t.name + (ptHex !== t.plaintext_hex ? '  (plaintext differs)' : ''));
}
for (const t of v.input_errors) {
  let message = '(accepted)';
  try { await plaintext({ ...t, now: v.now }); } catch (e) { message = e.message; }
  const ok = message === t.error;
  if (!ok) failures++;
  console.log((ok ? 'ok  ' : 'FAIL') + ' input error ' + t.name + (ok ? '' : '  → ' + message));
}
process.exit(failures ? 1 : 0);
