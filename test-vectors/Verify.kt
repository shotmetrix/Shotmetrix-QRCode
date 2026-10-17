// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// kotlinc kotlin/ShotmetrixQRCode.kt test-vectors/Verify.kt -include-runtime -d verify.jar && java -jar verify.jar test-vectors/test-vectors.json
// The Kotlin encoder must reproduce every valid vector bit for bit and reject every invalid input with exactly the
// expected message. No libraries, hence a tiny JSON reader.
package com.shotmetrix.qrcode

import java.io.File

/** Reads JSON into Map, List, String, Double, Boolean and null — enough for the test vectors. */
class JsonReader(private val s: String) {
    private var i = 0
    fun read(): Any? {
        skipWhitespace()
        return when (s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); skipWhitespace()
                if (s[i] == '}') { i++; return m }
                while (true) { skipWhitespace(); val k = text(); skipWhitespace(); i++; m[k] = read(); skipWhitespace(); if (s[i++] == '}') return m } }
            '[' -> { i++; val l = ArrayList<Any?>(); skipWhitespace()
                if (s[i] == ']') { i++; return l }
                while (true) { l.add(read()); skipWhitespace(); if (s[i++] == ']') return l } }
            '"' -> text()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { val a = i; while (i < s.length && s[i] in "+-0123456789.eE") i++; s.substring(a, i).toDouble() }
        }
    }
    private fun skipWhitespace() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun text(): String {
        val sb = StringBuilder(); i++
        while (s[i] != '"') {
            if (s[i] == '\\') { i++
                when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) }
            } else sb.append(s[i])
            i++
        }
        i++; return sb.toString()
    }
}

fun main(args: Array<String>) {
    @Suppress("UNCHECKED_CAST")
    val v = JsonReader(File(if (args.isNotEmpty()) args[0] else "test-vectors/test-vectors.json").readText()).read() as Map<String, Any?>
    val hex = { h: String -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    val pub = java.util.Base64.getDecoder().decode(v["test_public_key"] as String)
    var failures = 0
    @Suppress("UNCHECKED_CAST")
    for (t in v["valid"] as List<Map<String, Any?>>) {
        @Suppress("UNCHECKED_CAST")
        val shots = (t["shots"] as List<Map<String, Any?>>).map {
            ShotmetrixQRCode.Shot(it["x"] as Double?, it["y"] as Double?, it["value"] as Double, it["sighter"] as Boolean? ?: false, it["time"] as Double?)
        }
        val secret = t["app_secret"] as String
        val pt = ShotmetrixQRCode.plaintext((t["discipline"] as Double).toInt(), t["decimal"] as Boolean, t["time"] as Double, shots,
            (t["app"] as Double).toInt(), if (secret.isEmpty()) null else secret.toByteArray(), hex(t["id_hex"] as String), v["now"] as Double, ByteArray(840))
        val code = ShotmetrixQRCode.base32(ShotmetrixQRCode.seal(pt, pub, hex(t["ephemeral_private_hex"] as String)))
        val ptOk = pt.joinToString("") { "%02x".format(it) } == t["plaintext_hex"]
        val ok = ptOk && code == t["code"]
        if (!ok) failures++
        println((if (ok) "ok  " else "FAIL") + " " + t["name"] + (if (ptOk) "" else "  (plaintext differs)"))
    }
    @Suppress("UNCHECKED_CAST")
    for (t in v["input_errors"] as List<Map<String, Any?>>) {
        @Suppress("UNCHECKED_CAST")
        val shots = (t["shots"] as List<Map<String, Any?>>).map {
            ShotmetrixQRCode.Shot(it["x"] as Double?, it["y"] as Double?, it["value"] as Double? ?: Double.NaN, it["sighter"] as Boolean? ?: false, it["time"] as Double?)
        }
        val message = try {
            ShotmetrixQRCode.plaintext((t["discipline"] as Double).toInt(), t["decimal"] as Boolean, t["time"] as Double, shots,
                (t["app"] as Double).toInt(), now = v["now"] as Double); "(accepted)"
        } catch (e: IllegalArgumentException) { e.message }
        val ok = message == t["error"]
        if (!ok) failures++
        println((if (ok) "ok  " else "FAIL") + " input error " + t["name"] + (if (ok) "" else "  → $message"))
    }
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
