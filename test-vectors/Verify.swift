// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Shotmetrix Kemmer GmbH
// swiftc -parse-as-library swift/ShotmetrixQRCode.swift test-vectors/Verify.swift -o verify && ./verify test-vectors/test-vectors.json
// The Swift encoder must reproduce every valid vector bit for bit and reject every invalid input
// with exactly the expected message.
import Foundation
@main struct Verify {
    static func main() throws {
        let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "test-vectors/test-vectors.json"
        let v = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
        func hex(_ s: String) -> Data { Data(stride(from: 0, to: s.count, by: 2).map { i -> UInt8 in
            let a = s.index(s.startIndex, offsetBy: i); return UInt8(s[a..<s.index(a, offsetBy: 2)], radix: 16)! }) }
        let pub = Data(base64Encoded: v["test_public_key"] as! String)!
        var failures = 0
        for t in v["valid"] as! [[String: Any]] {
            let shots = (t["shots"] as! [[String: Any]]).map { s in
                ShotmetrixQRCode.Shot(x: s["x"] as? Double, y: s["y"] as? Double, value: (s["value"] as! NSNumber).doubleValue, sighter: s["sighter"] as? Bool ?? false,
                                      time: (s["time"] as? NSNumber)?.doubleValue) }
            let now = (v["now"] as! NSNumber).doubleValue
            let secret = t["app_secret"] as! String
            let pt = try ShotmetrixQRCode.plaintext(discipline: t["discipline"] as! Int, decimal: t["decimal"] as! Bool, time: (t["time"] as! NSNumber).doubleValue,
                shots: shots, app: t["app"] as! Int, appSecret: secret.isEmpty ? nil : Data(secret.utf8), id: hex(t["id_hex"] as! String), now: now, fill: Data(count: 840))
            let code = ShotmetrixQRCode.base32(try ShotmetrixQRCode.seal(pt, publicKey: pub, ephemeralPrivate: hex(t["ephemeral_private_hex"] as! String)))
            let ptOk = pt.map { String(format: "%02x", $0) }.joined() == t["plaintext_hex"] as! String
            let ok = ptOk && code == t["code"] as! String
            if !ok { failures += 1 }
            print((ok ? "ok  " : "FAIL") + " " + (t["name"] as! String) + (ptOk ? "" : "  (plaintext differs)"))
        }
        for t in v["input_errors"] as! [[String: Any]] {
            let shots = (t["shots"] as! [[String: Any]]).map { s in
                ShotmetrixQRCode.Shot(x: (s["x"] as? NSNumber)?.doubleValue, y: (s["y"] as? NSNumber)?.doubleValue,
                                      value: (s["value"] as? NSNumber)?.doubleValue ?? .nan, sighter: s["sighter"] as? Bool ?? false,
                                      time: (s["time"] as? NSNumber)?.doubleValue) }
            var message = "(accepted)"
            do { _ = try ShotmetrixQRCode.plaintext(discipline: t["discipline"] as! Int, decimal: t["decimal"] as! Bool, time: (t["time"] as! NSNumber).doubleValue,
                                                    shots: shots, app: t["app"] as! Int, now: (v["now"] as! NSNumber).doubleValue) }
            catch let e as ShotmetrixQRCode.QRCodeError { message = e.message }
            let ok = message == t["error"] as! String
            if !ok { failures += 1 }
            print((ok ? "ok  " : "FAIL") + " input error " + (t["name"] as! String) + (ok ? "" : "  → " + message))
        }
        exit(failures == 0 ? 0 : 1)
    }
}
