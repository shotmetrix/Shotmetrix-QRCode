<?php
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
//
// Shotmetrix QR Code, version 1 — reference encoder in PHP (≥ 7.2, 64-bit, extensions sodium + openssl).
// Format description: ../SPEC.md
//
//   require 'ShotmetrixQRCode.php';
//   $url = ShotmetrixQRCode::qrCodeUrl([
//       'publicKey'  => SHOTMETRIX_PUBLIC_KEY, 'discipline' => 1, 'decimal' => true, 'time' => $sessionStart,
//       'shots'      => [['x' => 0.42, 'y' => -1.1, 'value' => 10.6, 'time' => 1791900312.4], …],
//   ]);
//   // put $url into a QR code, error correction L

final class ShotmetrixQRCode
{
    const VERSION         = 1;
    const PLAINTEXT_BYTES = 924;     // 36 header + 120 × 7 shots + 48 zero bytes (reserved)
    const MAX_SHOTS       = 120;
    const SHOT_BYTES      = 7;
    const URL_PREFIX      = 'https://shotmetrix.com/api/qrcode/';
    const INFO            = 'shotmetrix-qrcode-v1';
    const UNITS           = [0.01, 0.1, 0.2];   // mm per step
    const NO_POS          = 0x800;              // −2048 in 12 bits: shot without a position
    const NO_TIME         = 0xFFFFFF;           // shot without a time

    /** Rounds halves away from zero (as the specification requires). */
    private static function roundHalfAway(float $v): int
    {
        return (int)(($v < 0 ? -1 : 1) * floor(abs($v) + 0.5));
    }

    /**
     * The disciplines: zone width per target in mm (25 m pistol: precision and rapid-fire target) and the allowed
     * difference between value and position in tenths; null = not checked (rapid fire). SPEC.md, section 5.
     */
    const DISCIPLINES = [
        1 => [[0.25], 3], 2 => [[0.25], 3], 3 => [[0.8], 3], 4 => [[0.8], 3], 5 => [[0.8], 3], 6 => [[0.8], 3], 7 => [[0.8], 3],
        8 => [[2.5, 4.0], 10], 9 => [null, null], 10 => [[2.5], 10], 11 => [[2.5], 10], 12 => [[2.5], 10],
    ];

    private static function reject(string $message)
    {
        throw new InvalidArgumentException($message);
    }

    private static function isNumber($v): bool
    {
        return (is_int($v) || is_float($v)) && is_finite((float)$v);
    }

    /** DateTime or Unix seconds → seconds; null for anything else. */
    private static function toSeconds($t): ?float
    {
        if ($t instanceof DateTimeInterface) return (float)$t->format('U.u');
        return self::isNumber($t) ? (float)$t : null;
    }

    /** Value in tenths that a position yields (SPEC.md, section 7): d / zone to 6 decimal places, then round up. */
    private static function valueFromPosition(float $x, float $y, float $zone): int
    {
        $tenths = 110 - (int)ceil(floor(sqrt($x * $x + $y * $y) / $zone * 1e6 + 0.5) / 1e6);
        return $tenths > 109 ? 109 : ($tenths < 10 ? 0 : $tenths);
    }

    /** Base32 per RFC 4648, upper case, without "=" padding. */
    public static function base32(string $bytes): string
    {
        $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
        $out = ''; $buffer = 0; $bits = 0;
        for ($i = 0, $n = strlen($bytes); $i < $n; $i++) {
            $buffer = ($buffer << 8) | ord($bytes[$i]); $bits += 8;
            while ($bits >= 5) { $bits -= 5; $out .= $alphabet[($buffer >> $bits) & 31]; }
            $buffer &= (1 << $bits) - 1;
        }
        if ($bits > 0) $out .= $alphabet[($buffer << (5 - $bits)) & 31];
        return $out;
    }

    /**
     * Builds the 924-byte plaintext and checks every rule from SPEC.md, section 7, as well as the limits of the
     * format; invalid input is rejected instead of producing a code that Shotmetrix would refuse.
     * $in: discipline (1–12), decimal (bool), time (session start: DateTime|Unix seconds),
     *      shots: [['x', 'y' (mm, +y up; both null = no position), 'value' (e.g. 10.6), 'sighter', 'time' (optional)]],
     *      app?, appSecret? (raw), id? (16 bytes), now? (Unix seconds), fill? (tests only)
     * Errors: InvalidArgumentException with an English message ("shot 3: value out of range").
     */
    public static function plaintext(array $in): string
    {
        if (PHP_INT_SIZE < 8) throw new RuntimeException('64-bit PHP required');
        $shots = isset($in['shots']) && is_array($in['shots']) ? array_values($in['shots']) : [];
        $count = count($shots);
        if ($count < 1 || $count > self::MAX_SHOTS) self::reject('shots: 1 to 120 shots');
        $discipline = $in['discipline'] ?? null;
        if (!is_int($discipline) || !isset(self::DISCIPLINES[$discipline])) self::reject('discipline: unknown number');
        [$zones, $tolerance] = self::DISCIPLINES[$discipline];
        $decimal = !empty($in['decimal']);
        $now = self::isNumber($in['now'] ?? null) ? (float)$in['now'] : microtime(true);
        $startSeconds = self::toSeconds($in['time'] ?? null);
        $start = $startSeconds === null ? null : (int)floor($startSeconds);
        if ($start === null || $start < 946684800 || $start > $now + 86400) self::reject('time: session start must be after 2000-01-01 and not in the future');
        $app = $in['app'] ?? 0;
        if (!is_int($app) || $app < 0 || $app > 65535) self::reject('app: 0 to 65535');
        if (isset($in['id']) && (!is_string($in['id']) || strlen($in['id']) !== 16)) self::reject('id: 16 bytes');

        // Every shot: value, position, time
        $values = []; $times = []; $max = 0.0; $previous = -1; $scored = 0;
        foreach ($shots as $i => $s) {
            $prefix = 'shot ' . ($i + 1) . ': ';
            if (!is_array($s) || !self::isNumber($s['value'] ?? null)) self::reject($prefix . 'value must be a number');
            if (abs($s['value']) > 1000) self::reject($prefix . 'value out of range');
            $value = $decimal ? self::roundHalfAway($s['value'] * 10) : (int)floor($s['value']) * 10;
            if (!($decimal ? ($value === 0 || ($value >= 10 && $value <= 109)) : ($value >= 0 && $value <= 100))) self::reject($prefix . 'value out of range');
            $x = $s['x'] ?? null; $y = $s['y'] ?? null;
            if (($x === null) !== ($y === null)) self::reject($prefix . 'give x and y together');
            if ($x !== null) {
                if (!self::isNumber($x) || !self::isNumber($y)) self::reject($prefix . 'position must be a number');
                if (abs($x) > 409.4 || abs($y) > 409.4) self::reject($prefix . 'position outside ±409.4 mm');
                $max = max($max, abs((float)$x), abs((float)$y));
            }
            $t = self::NO_TIME;
            if (isset($s['time'])) {
                $shotTime = self::toSeconds($s['time']);
                if ($shotTime === null) self::reject($prefix . 'time must be a number or a date');
                $t = self::roundHalfAway(($shotTime - $start) * 100);
                if ($t < 0 || $t >= self::NO_TIME) self::reject($prefix . 'time before the session start or more than 46 h after it');
                if ($t < $previous) self::reject($prefix . 'time earlier than the shot before');
                if ($start + $t / 100 > $now + 86400) self::reject($prefix . 'time in the future');
                $previous = $t;
            }
            if (empty($s['sighter'])) $scored++;
            $values[] = $value; $times[] = $t;
        }
        if ($scored === 0) self::reject('no scored shot');
        $unit = 0;
        foreach (self::UNITS as $k => $u) if ($max / $u <= 2047) { $unit = $k; break; }
        $mm = self::UNITS[$unit];

        // Value and position must agree (checked like the server does, with the position as encoded)
        if ($tolerance !== null) {
            $limit = $decimal ? $tolerance : max(10, $tolerance);
            foreach ($shots as $i => $s) {
                if (!empty($s['sighter']) || !isset($s['x'])) continue;
                $xq = self::roundHalfAway($s['x'] / $mm) * $mm; $yq = self::roundHalfAway($s['y'] / $mm) * $mm;
                $matches = false;
                foreach ($zones as $zone) {
                    $expected = self::valueFromPosition($xq, $yq, $zone);
                    if (!$decimal) $expected = intdiv($expected, 10) * 10;
                    if (abs($expected - $values[$i]) <= $limit) { $matches = true; break; }
                }
                if (!$matches) self::reject('shot ' . ($i + 1) . ': value does not match the position');
            }
        }

        $shotBlock = ''; $total = 0;
        foreach ($shots as $i => $s) {
            $sighter = !empty($s['sighter']);
            if (!$sighter) $total += $values[$i];
            $x = $y = self::NO_POS;
            if (isset($s['x'])) {
                $x = self::roundHalfAway($s['x'] / $mm) & 0xFFF;
                $y = self::roundHalfAway($s['y'] / $mm) & 0xFFF;
            }
            $shotBlock .= pack('N', ($x << 20) | ($y << 8) | ($values[$i] << 1) | ($sighter ? 1 : 0)) . substr(pack('N', $times[$i]), 1);
        }
        $unusedBytes = self::SHOT_BYTES * (self::MAX_SHOTS - $count);
        if ($unusedBytes > 0) $shotBlock .= isset($in['fill']) ? substr(str_pad($in['fill'], $unusedBytes, "\0"), 0, $unusedBytes) : random_bytes($unusedBytes);

        $plaintext = ($in['id'] ?? random_bytes(16)) . pack('n', $app) . str_repeat("\0", 8) . pack('N', $start)
            . chr($discipline) . chr(($decimal ? 1 : 0) | ($unit << 1)) . chr($count)
            . pack('n', $total) . "\0" . $shotBlock . str_repeat("\0", 48);
        if ($app !== 0 && !empty($in['appSecret'])) {
            $plaintext = substr_replace($plaintext, substr(hash_hmac('sha256', $plaintext, $in['appSecret'], true), 0, 8), 18, 8);   // app_mark is still zero at this point
        }
        return $plaintext;
    }

    /**
     * Encrypts the plaintext for the public key (32 bytes raw or Base64). Result: 973 bytes.
     * $ephemeralPrivate (32 bytes) is for test vectors only — otherwise every code gets a fresh one-time key.
     */
    public static function seal(string $plaintext, string $publicKey, ?string $ephemeralPrivate = null): string
    {
        $server = strlen($publicKey) === 32 ? $publicKey : base64_decode($publicKey, true);
        if ($server === false || strlen($server) !== 32) self::reject('publicKey: 32 bytes');
        if ($ephemeralPrivate !== null && strlen($ephemeralPrivate) !== 32) self::reject('ephemeralPrivate: 32 bytes');
        $priv = $ephemeralPrivate ?? random_bytes(32);
        $ephemeralPublic = sodium_crypto_scalarmult_base($priv);
        $shared = sodium_crypto_scalarmult($priv, $server);
        $okm = hash_hkdf('sha256', $shared, 44, self::INFO, $ephemeralPublic . $server);
        $tag = '';
        $ct = openssl_encrypt($plaintext, 'aes-256-gcm', substr($okm, 0, 32), OPENSSL_RAW_DATA, substr($okm, 32, 12), $tag, chr(self::VERSION), 16);
        return chr(self::VERSION) . $ephemeralPublic . $ct . $tag;
    }

    /** All in one: input → URL for the QR code. */
    public static function qrCodeUrl(array $in): string
    {
        return self::URL_PREFIX . self::base32(self::seal(self::plaintext($in), $in['publicKey'], $in['ephemeralPrivate'] ?? null));
    }
}
