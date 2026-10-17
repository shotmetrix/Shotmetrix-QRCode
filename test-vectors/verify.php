<?php
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// php test-vectors/verify.php — the PHP encoder must reproduce every valid vector bit for bit
// and reject every invalid input with exactly the expected message.
require __DIR__ . '/../php/ShotmetrixQRCode.php';

$v = json_decode(file_get_contents(__DIR__ . '/test-vectors.json'), true);
$failures = 0;
foreach ($v['valid'] as $t) {
    $plaintext = ShotmetrixQRCode::plaintext([
        'discipline' => $t['discipline'], 'decimal' => $t['decimal'], 'time' => $t['time'], 'shots' => $t['shots'],
        'app' => $t['app'], 'appSecret' => $t['app_secret'], 'id' => hex2bin($t['id_hex']), 'now' => $v['now'], 'fill' => str_repeat("\0", 840),
    ]);
    $code = ShotmetrixQRCode::base32(ShotmetrixQRCode::seal($plaintext, $v['test_public_key'], hex2bin($t['ephemeral_private_hex'])));
    $plaintextOk = bin2hex($plaintext) === $t['plaintext_hex'];
    $ok = $plaintextOk && $code === $t['code'];
    if (!$ok) $failures++;
    echo ($ok ? 'ok   ' : 'FAIL ') . $t['name'] . ($plaintextOk ? '' : '  (plaintext differs)') . "\n";
}
foreach ($v['input_errors'] as $t) {
    try { ShotmetrixQRCode::plaintext($t + ['now' => $v['now']]); $message = '(accepted)'; }
    catch (InvalidArgumentException $e) { $message = $e->getMessage(); }
    $ok = $message === $t['error'];
    if (!$ok) $failures++;
    echo ($ok ? 'ok   ' : 'FAIL ') . 'input error ' . $t['name'] . ($ok ? '' : '  → ' . $message) . "\n";
}
exit($failures ? 1 : 0);
