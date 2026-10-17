// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// Shotmetrix QR Code, version 1 — reference encoder in Swift (CryptoKit, iOS 14 / macOS 11).
// Format specification: ../SPEC.md
import CryptoKit
import Foundation

public enum ShotmetrixQRCode {
    public static let version: UInt8 = 1
    public static let plaintextBytes = 924   // 36 header + 120 × 7 shots + 48 zero bytes (reserved)
    public static let maxShots = 120
    public static let shotBytes = 7
    static let noTime: Int = 0xFFFFFF
    public static let urlPrefix = "https://shotmetrix.com/api/qrcode/"
    static let info = Data("shotmetrix-qrcode-v1".utf8)
    static let units: [Double] = [0.01, 0.1, 0.2]

    /// The disciplines: zone width per target in mm (25 m pistol: precision and rapid-fire target) and the allowed
    /// difference between value and position in tenths; nil = not checked (rapid fire). SPEC.md, section 5.
    static let disciplines: [Int: (zones: [Double], tolerance: Int?)] = [
        1: ([0.25], 3), 2: ([0.25], 3), 3: ([0.8], 3), 4: ([0.8], 3), 5: ([0.8], 3), 6: ([0.8], 3), 7: ([0.8], 3),
        8: ([2.5, 4.0], 10), 9: ([], nil), 10: ([2.5], 10), 11: ([2.5], 10), 12: ([2.5], 10),
    ]

    public struct Shot {
        public var x: Double?      // mm from the centre, +x right; x and y both nil = no position
        public var y: Double?      // mm from the centre, +y UP
        public var value: Double   // e.g. 10.6
        public var sighter = false
        public var time: Double?   // shot time, Unix seconds (fractions allowed); nil = no time
        public init(x: Double?, y: Double?, value: Double, sighter: Bool = false, time: Double? = nil) {
            self.x = x; self.y = y; self.value = value; self.sighter = sighter; self.time = time
        }
        public init(x: Double?, y: Double?, value: Double, sighter: Bool = false, date: Date) {
            self.init(x: x, y: y, value: value, sighter: sighter, time: date.timeIntervalSince1970)
        }
    }

    /// Error with an English message ("shot 3: value out of range").
    public struct QRCodeError: Error, CustomStringConvertible {
        public let message: String
        public var description: String { message }
    }
    static func reject(_ message: String) -> QRCodeError { QRCodeError(message: message) }

    /// Rounds halves away from zero (as the specification requires).
    static func roundHalfAway(_ v: Double) -> Int { Int((v < 0 ? -1 : 1) * (abs(v) + 0.5).rounded(.down)) }

    /// Value in tenths that a position scores (SPEC.md, section 7): round to 6 decimal places, then round up.
    static func valueFromPosition(_ x: Double, _ y: Double, _ zone: Double) -> Int {
        let score = 110 - Int(((((x * x + y * y).squareRoot() / zone * 1e6) + 0.5).rounded(.down) / 1e6).rounded(.up))
        return score > 109 ? 109 : (score < 10 ? 0 : score)
    }

    public static func base32(_ data: Data) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var out = "", buffer: UInt32 = 0, bits = 0
        for b in data {
            buffer = (buffer << 8) | UInt32(b); bits += 8
            while bits >= 5 { bits -= 5; out.append(alphabet[Int((buffer >> UInt32(bits)) & 31)]) }
            buffer &= (1 << UInt32(bits)) - 1
        }
        if bits > 0 { out.append(alphabet[Int((buffer << UInt32(5 - bits)) & 31)]) }
        return out
    }

    /// Builds the 924-byte plaintext and checks every rule from SPEC.md, section 7, as well as the limits of the
    /// format; invalid input is rejected instead of building a code that Shotmetrix would refuse.
    /// time: start of the session in Unix seconds; now is for tests only.
    public static func plaintext(discipline: Int, decimal: Bool, time: Double, shots: [Shot],
                                 app: Int = 0, appSecret: Data? = nil, id: Data? = nil, now: Double? = nil, fill: Data? = nil) throws -> Data {
        guard (1...maxShots).contains(shots.count) else { throw reject("shots: 1 to 120 shots") }
        guard let rules = disciplines[discipline] else { throw reject("discipline: unknown number") }
        let now = now ?? Date().timeIntervalSince1970
        guard time.isFinite, time.rounded(.down) >= 946684800, time.rounded(.down) <= now + 86400 else {
            throw reject("time: session start must be after 2000-01-01 and not in the future")
        }
        let start = Int(time.rounded(.down))
        guard (0...65535).contains(app) else { throw reject("app: 0 to 65535") }
        if let id = id, id.count != 16 { throw reject("id: 16 bytes") }

        // Each shot: value, position, time
        var values: [Int] = [], times: [Int] = [], extent = 0.0, lastTime = -1, scored = 0
        for (i, s) in shots.enumerated() {
            let prefix = "shot \(i + 1): "
            guard s.value.isFinite else { throw reject(prefix + "value must be a number") }
            guard abs(s.value) <= 1000 else { throw reject(prefix + "value out of range") }
            let value = decimal ? roundHalfAway(s.value * 10) : Int(s.value.rounded(.down)) * 10
            guard decimal ? (value == 0 || (10...109).contains(value)) : (0...100).contains(value) else { throw reject(prefix + "value out of range") }
            guard (s.x == nil) == (s.y == nil) else { throw reject(prefix + "give x and y together") }
            if let x = s.x, let y = s.y {
                guard x.isFinite, y.isFinite else { throw reject(prefix + "position must be a number") }
                guard abs(x) <= 409.4, abs(y) <= 409.4 else { throw reject(prefix + "position outside ±409.4 mm") }
                extent = max(extent, abs(x), abs(y))
            }
            var t = noTime
            if let shotTime = s.time {
                guard shotTime.isFinite else { throw reject(prefix + "time must be a number or a date") }
                let v = (shotTime - Double(start)) * 100
                guard v > -0.5, v < Double(noTime) - 0.5 else { throw reject(prefix + "time before the session start or more than 46 h after it") }
                t = roundHalfAway(v)
                guard t >= lastTime else { throw reject(prefix + "time earlier than the shot before") }
                guard Double(start) + Double(t) / 100 <= now + 86400 else { throw reject(prefix + "time in the future") }
                lastTime = t
            }
            if !s.sighter { scored += 1 }
            values.append(value); times.append(t)
        }
        guard scored > 0 else { throw reject("no scored shot") }
        let unit = units.firstIndex(where: { extent / $0 <= 2047 }) ?? 2
        let mm = units[unit]

        // Value and position must agree (as on the server, using the position as encoded)
        if let tol = rules.tolerance {
            let limit = decimal ? tol : max(10, tol)
            for (i, s) in shots.enumerated() where !s.sighter {
                guard let x = s.x, let y = s.y else { continue }
                let xq = Double(roundHalfAway(x / mm)) * mm, yq = Double(roundHalfAway(y / mm)) * mm
                let matches = rules.zones.contains { zone in
                    var expected = valueFromPosition(xq, yq, zone)
                    if !decimal { expected = expected / 10 * 10 }
                    return abs(expected - values[i]) <= limit
                }
                guard matches else { throw reject("shot \(i + 1): value does not match the position") }
            }
        }

        var pt = Data(count: plaintextBytes)
        pt.replaceSubrange(0..<16, with: id ?? Data((0..<16).map { _ in UInt8.random(in: 0...255) }))
        pt[16] = UInt8(app >> 8); pt[17] = UInt8(app & 0xFF)
        for i in 0..<4 { pt[26 + i] = UInt8((start >> (24 - 8 * i)) & 0xFF) }
        pt[30] = UInt8(discipline)
        pt[31] = (decimal ? 1 : 0) | UInt8(unit << 1)
        pt[32] = UInt8(shots.count)
        var total = 0
        for (i, s) in shots.enumerated() {
            if !s.sighter { total += values[i] }
            var x = 0x800, y = 0x800
            if let sx = s.x, let sy = s.y {
                x = roundHalfAway(sx / mm) & 0xFFF
                y = roundHalfAway(sy / mm) & 0xFFF
            }
            let word = (x << 20) | (y << 8) | (values[i] << 1) | (s.sighter ? 1 : 0)
            let offset = 36 + shotBytes * i
            for k in 0..<4 { pt[offset + k] = UInt8((word >> (24 - 8 * k)) & 0xFF) }
            for k in 0..<3 { pt[offset + 4 + k] = UInt8((times[i] >> (16 - 8 * k)) & 0xFF) }
        }
        pt[33] = UInt8(total >> 8); pt[34] = UInt8(total & 0xFF)
        let padCount = shotBytes * (maxShots - shots.count), padStart = 36 + shotBytes * shots.count
        var padding = fill.map { Data($0.prefix(padCount)) } ?? Data((0..<padCount).map { _ in UInt8.random(in: 0...255) })
        if padding.count < padCount { padding.append(Data(count: padCount - padding.count)) }
        pt.replaceSubrange(padStart..<(padStart + padCount), with: padding)
        if app != 0, let secret = appSecret {
            let mac = HMAC<SHA256>.authenticationCode(for: pt, using: SymmetricKey(data: secret))   // app_mark is still zero here
            pt.replaceSubrange(18..<26, with: Data(mac).prefix(8))
        }
        return pt
    }

    public static func plaintext(discipline: Int, decimal: Bool, time: Date, shots: [Shot],
                                 app: Int = 0, appSecret: Data? = nil) throws -> Data {
        try plaintext(discipline: discipline, decimal: decimal, time: time.timeIntervalSince1970, shots: shots, app: app, appSecret: appSecret)
    }

    public static func seal(_ plaintext: Data, publicKey: Data, ephemeralPrivate: Data? = nil) throws -> Data {
        guard publicKey.count == 32 else { throw reject("publicKey: 32 bytes") }
        if let e = ephemeralPrivate, e.count != 32 { throw reject("ephemeralPrivate: 32 bytes") }
        let priv = try ephemeralPrivate.map { try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: $0) } ?? Curve25519.KeyAgreement.PrivateKey()
        let eph = priv.publicKey.rawRepresentation
        let shared = try priv.sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey))
        let okm = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: eph + publicKey, sharedInfo: info, outputByteCount: 44)
            .withUnsafeBytes { Data($0) }
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: okm.prefix(32)), nonce: AES.GCM.Nonce(data: okm.suffix(12)), authenticating: Data([version]))
        return Data([version]) + eph + box.ciphertext + box.tag
    }

    public static func qrCodeURL(publicKeyBase64: String, discipline: Int, decimal: Bool, time: Date, shots: [Shot],
                                 app: Int = 0, appSecret: Data? = nil) throws -> String {
        guard let publicKey = Data(base64Encoded: publicKeyBase64) else { throw reject("publicKey: 32 bytes") }
        let pt = try plaintext(discipline: discipline, decimal: decimal, time: time, shots: shots, app: app, appSecret: appSecret)
        return urlPrefix + base32(try seal(pt, publicKey: publicKey))
    }
}
