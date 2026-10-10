import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public enum DeviceLinkError: Error, Equatable, CustomStringConvertible {
    case invalid(String)
    case relay(status: Int, code: String)
    case pairing(String)
    case notConfigured

    public var description: String {
        switch self {
        case .invalid(let reason): return "Invalid data: \(reason)"
        case .relay(let status, let code): return "Relay request failed: \(status) \(code)"
        case .pairing(let reason): return reason
        case .notConfigured: return "Relay is not configured"
        }
    }
}

/// Byte helpers shared by every v2 contract (see docs/protocol/link-v2.md).
public enum Encoding {
    public static func b64(_ data: Data) -> String { data.base64EncodedString() }

    /// Strict standard Base64 with padding; rejects non-canonical input.
    public static func unb64(_ text: String, maxBytes: Int) throws -> Data {
        guard text.count <= (maxBytes + 2) / 3 * 4, let data = Data(base64Encoded: text), data.count <= maxBytes,
              data.base64EncodedString() == text else { throw DeviceLinkError.invalid("base64") }
        return data
    }

    public static func b64url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func unb64url(_ text: String, expectedBytes: Int? = nil) throws -> Data {
        guard !text.isEmpty || expectedBytes == 0, text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") })
        else { throw DeviceLinkError.invalid("base64url") }
        var standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while standard.count % 4 != 0 { standard += "=" }
        guard let data = Data(base64Encoded: standard), b64url(data) == text,
              expectedBytes == nil || data.count == expectedBytes else { throw DeviceLinkError.invalid("base64url") }
        return data
    }

    public static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    public static func unhex(_ text: String) throws -> Data {
        guard text.count % 2 == 0, text.allSatisfy({ "0123456789abcdef".contains($0) }) else { throw DeviceLinkError.invalid("hex") }
        var data = Data(capacity: text.count / 2)
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            data.append(UInt8(text[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    public static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    public static func hkdf(_ ikm: Data, info: String, length: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: Data(), info: Data(info.utf8), outputByteCount: length)
        return key.withUnsafeBytes { Data($0) }
    }

    /// Length-prefixed canonical byte strings; `field(x)` in the protocol document.
    public struct Canonical {
        public private(set) var data = Data()
        public init() {}
        public mutating func field(_ value: String) { field(Data(value.utf8)) }
        public mutating func field(_ value: Data) { u32(UInt32(value.count)); data.append(value) }
        public mutating func u32(_ value: UInt32) { withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) } }
        public mutating func i64(_ value: Int64) { withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) } }
    }

    static func validDisplayText(_ value: String, maxUTF16: Int) -> Bool {
        let units = value.utf16.count
        return units > 0 && units <= maxUTF16 && !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !value.unicodeScalars.contains { $0.properties.generalCategory == .control || $0.properties.generalCategory == .format }
    }

    static func randomBytes(_ count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }
}
