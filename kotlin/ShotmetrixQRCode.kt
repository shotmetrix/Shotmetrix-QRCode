// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// Shotmetrix QR Code, version 1 — reference encoder in Kotlin (JVM 11+ / Android API 33+, no libraries).
// Older Android versions: X25519 via Tink or BouncyCastle; everything else stays the same.
// Format description: ../SPEC.md
package com.shotmetrix.qrcode

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

object ShotmetrixQRCode {
    const val VERSION = 1
    const val PLAINTEXT_BYTES = 924   // 36 header + 120 × 7 shots + 48 zero bytes (reserved)
    const val MAX_SHOTS = 120
    const val SHOT_BYTES = 7
    private const val NO_TIME = 0xFFFFFF
    const val URL_PREFIX = "https://shotmetrix.com/api/qrcode/"
    private val INFO = "shotmetrix-qrcode-v1".toByteArray()
    private val UNITS = doubleArrayOf(0.01, 0.1, 0.2)
    private val rnd = SecureRandom()

    /**
     * The disciplines: zone width per target in mm (25 m pistol: precision and rapid-fire target) and the allowed
     * deviation between value and position in tenths; null = not checked (rapid fire). SPEC.md, section 5.
     */
    private val DISCIPLINES: Map<Int, Pair<DoubleArray, Int?>> = mapOf(
        1 to Pair(doubleArrayOf(0.25), 3), 2 to Pair(doubleArrayOf(0.25), 3), 3 to Pair(doubleArrayOf(0.8), 3),
        4 to Pair(doubleArrayOf(0.8), 3), 5 to Pair(doubleArrayOf(0.8), 3), 6 to Pair(doubleArrayOf(0.8), 3),
        7 to Pair(doubleArrayOf(0.8), 3), 8 to Pair(doubleArrayOf(2.5, 4.0), 10), 9 to Pair(doubleArrayOf(), null),
        10 to Pair(doubleArrayOf(2.5), 10), 11 to Pair(doubleArrayOf(2.5), 10), 12 to Pair(doubleArrayOf(2.5), 10),
    )

    /** Rounds halves away from zero (roundToInt rounds −2.5 to −2). */
    private fun roundHalfAway(v: Double): Int = (Math.signum(v) * floor(abs(v) + 0.5)).toInt()

    /** Value in tenths that a position yields (SPEC.md, section 7): round to 6 decimal places, then round up. */
    private fun valueFromPosition(x: Double, y: Double, zone: Double): Int {
        val z = 110 - ceil(floor(sqrt(x * x + y * y) / zone * 1e6 + 0.5) / 1e6).toInt()
        return if (z > 109) 109 else if (z < 10) 0 else z
    }

    /**
     * x, y in mm from the centre (+x right, +y UP), both null = no position; value e.g. 10.6;
     * time = shot time in Unix seconds, null = no time
     */
    data class Shot(val x: Double?, val y: Double?, val value: Double, val sighter: Boolean = false, val time: Double? = null)

    fun base32(data: ByteArray): String {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder(); var buffer = 0; var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(abc[(buffer shr bits) and 31]) }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(abc[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /**
     * Builds the 924-byte plaintext and checks every rule from SPEC.md, section 7, plus the limits of the
     * format; invalid input is rejected instead of building a code that Shotmetrix would reject.
     * time: start of the session in Unix seconds; now only for tests.
     * Errors: IllegalArgumentException with an English message ("shot 3: value out of range").
     */
    fun plaintext(discipline: Int, decimal: Boolean, time: Double, shots: List<Shot>, app: Int = 0, appSecret: ByteArray? = null,
                  id: ByteArray? = null, now: Double? = null, fill: ByteArray? = null): ByteArray {
        require(shots.size in 1..MAX_SHOTS) { "shots: 1 to 120 shots" }
        val disc = requireNotNull(DISCIPLINES[discipline]) { "discipline: unknown number" }
        val nowSeconds = now ?: (System.currentTimeMillis() / 1000.0)
        require(time.isFinite() && floor(time) >= 946684800.0 && floor(time) <= nowSeconds + 86400) {
            "time: session start must be after 2000-01-01 and not in the future"
        }
        val start = floor(time).toLong()
        require(app in 0..65535) { "app: 0 to 65535" }
        require(id == null || id.size == 16) { "id: 16 bytes" }

        // Each shot: value, position, time
        val values = IntArray(shots.size); val times = IntArray(shots.size)
        var m = 0.0; var lastTime = -1; var scored = 0
        shots.forEachIndexed { i, s ->
            val n = "shot ${i + 1}: "
            require(s.value.isFinite()) { n + "value must be a number" }
            require(abs(s.value) <= 1000) { n + "value out of range" }
            val value = if (decimal) roundHalfAway(s.value * 10) else floor(s.value).toInt() * 10
            require(if (decimal) value == 0 || value in 10..109 else value in 0..100) { n + "value out of range" }
            require((s.x == null) == (s.y == null)) { n + "give x and y together" }
            if (s.x != null && s.y != null) {
                require(s.x.isFinite() && s.y.isFinite()) { n + "position must be a number" }
                require(abs(s.x) <= 409.4 && abs(s.y) <= 409.4) { n + "position outside ±409.4 mm" }
                m = maxOf(m, abs(s.x), abs(s.y))
            }
            var t = NO_TIME
            if (s.time != null) {
                require(s.time.isFinite()) { n + "time must be a number or a date" }
                val v = (s.time - start.toDouble()) * 100
                require(v > -0.5 && v < NO_TIME - 0.5) { n + "time before the session start or more than 46 h after it" }
                t = roundHalfAway(v)
                require(t >= lastTime) { n + "time earlier than the shot before" }
                require(start + t / 100.0 <= nowSeconds + 86400) { n + "time in the future" }
                lastTime = t
            }
            if (!s.sighter) scored++
            values[i] = value; times[i] = t
        }
        require(scored > 0) { "no scored shot" }
        val unit = UNITS.indexOfFirst { m / it <= 2047 }
        val mm = UNITS[unit]

        // Value and position must agree (as on the server, using the position as it is stored in the code)
        val tol = disc.second
        if (tol != null) {
            val limit = if (decimal) tol else maxOf(10, tol)
            shots.forEachIndexed { i, s ->
                if (s.sighter || s.x == null || s.y == null) return@forEachIndexed
                val xq = roundHalfAway(s.x / mm) * mm; val yq = roundHalfAway(s.y / mm) * mm
                val fits = disc.first.any { zone ->
                    var expected = valueFromPosition(xq, yq, zone)
                    if (!decimal) expected = expected / 10 * 10
                    abs(expected - values[i]) <= limit
                }
                require(fits) { "shot ${i + 1}: value does not match the position" }
            }
        }

        val bb = ByteBuffer.allocate(PLAINTEXT_BYTES)        // big-endian; the rest stays zero (reserved)
        bb.put(id ?: ByteArray(16).also { rnd.nextBytes(it) })
        bb.putShort(app.toShort()); bb.put(ByteArray(8)); bb.putInt(start.toInt())
        bb.put(discipline.toByte()); bb.put(((if (decimal) 1 else 0) or (unit shl 1)).toByte()); bb.put(shots.size.toByte())
        var total = 0
        val shotBuf = ByteBuffer.allocate(SHOT_BYTES * shots.size)
        shots.forEachIndexed { i, s ->
            if (!s.sighter) total += values[i]
            var x = 0x800; var y = 0x800
            if (s.x != null && s.y != null) { x = roundHalfAway(s.x / mm) and 0xFFF; y = roundHalfAway(s.y / mm) and 0xFFF }
            shotBuf.putInt((x shl 20) or (y shl 8) or (values[i] shl 1) or (if (s.sighter) 1 else 0))
            val t = times[i]
            shotBuf.put((t shr 16).toByte()); shotBuf.put((t shr 8).toByte()); shotBuf.put(t.toByte())
        }
        bb.putShort(total.toShort()); bb.put(0)
        bb.put(shotBuf.array())
        val rest = SHOT_BYTES * (MAX_SHOTS - shots.size)
        bb.put(fill?.copyOf(rest) ?: ByteArray(rest).also { rnd.nextBytes(it) })
        val pt = bb.array()
        if (app != 0 && appSecret != null) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(appSecret, "HmacSHA256")) }.doFinal(pt)   // app_mark field is still zero here
            System.arraycopy(mac, 0, pt, 18, 8)
        }
        return pt
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, len: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        val out = java.io.ByteArrayOutputStream(); var t = ByteArray(0); var i = 1
        while (out.size() < len) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            t = mac.doFinal(t + INFO + byteArrayOf(i.toByte())); out.write(t); i++
        }
        return out.toByteArray().copyOf(len)
    }

    fun seal(plaintext: ByteArray, publicKey: ByteArray, ephemeralPrivate: ByteArray? = null): ByteArray {
        require(publicKey.size == 32) { "publicKey: 32 bytes" }
        require(ephemeralPrivate == null || ephemeralPrivate.size == 32) { "ephemeralPrivate: 32 bytes" }
        val kf = KeyFactory.getInstance("XDH")
        val serverKey = kf.generatePublic(X509EncodedKeySpec(hex("302a300506032b656e032100") + publicKey))
        val priv = if (ephemeralPrivate != null) kf.generatePrivate(PKCS8EncodedKeySpec(hex("302e020100300506032b656e04220420") + ephemeralPrivate))
                   else KeyPairGenerator.getInstance("X25519").generateKeyPair().private
        val ephPub = ephemeralPublic(priv, kf)
        val shared = KeyAgreement.getInstance("XDH").apply { init(priv); doPhase(serverKey, true) }.generateSecret()
        val okm = hkdf(shared, ephPub + publicKey, 44)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(okm.copyOf(32), "AES"), GCMParameterSpec(128, okm.copyOfRange(32, 44)))
        c.updateAAD(byteArrayOf(VERSION.toByte()))
        return byteArrayOf(VERSION.toByte()) + ephPub + c.doFinal(plaintext) // ciphertext + 16-byte tag
    }

    /** Public key for the private key: key agreement with the base point 9. */
    private fun ephemeralPublic(priv: java.security.PrivateKey, kf: KeyFactory): ByteArray {
        val basePoint = ByteArray(32).also { it[0] = 9 }
        val pub = kf.generatePublic(X509EncodedKeySpec(hex("302a300506032b656e032100") + basePoint))
        return KeyAgreement.getInstance("XDH").apply { init(priv); doPhase(pub, true) }.generateSecret()
    }

    fun qrCodeUrl(publicKeyBase64: String, discipline: Int, decimal: Boolean, time: Double, shots: List<Shot>,
                  app: Int = 0, appSecret: ByteArray? = null): String {
        val key = try { java.util.Base64.getDecoder().decode(publicKeyBase64) } catch (e: IllegalArgumentException) { ByteArray(0) }
        val code = seal(plaintext(discipline, decimal, time, shots, app, appSecret), key)
        return URL_PREFIX + base32(code)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
