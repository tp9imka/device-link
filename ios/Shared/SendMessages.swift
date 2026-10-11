import DeviceLinkKit
import Foundation

/// Plain-language messages for what can go wrong while linking or sending. Never shows a raw Swift
/// error, and never includes clipboard text, file names or device names.
enum SendMessages {
    static let notLinked = "No linked device yet. Open DeviceLink to link one."
    static let unreachable = "The relay could not be reached. Check the internet connection and try again."
    static let mailboxFull = "The other device has too much waiting. Open DeviceLink there so it can catch up, then try again."
    static let refused = "The relay refused this device."
    static let generic = "Could not send. Try again in a moment."

    static var tooLarge: String { "This is too large to send. The limit is \(Limits.maxFileBytes / (1024 * 1024)) MB." }
    static var textTooLong: String { "This text is too long to send. The limit is \(Limits.maxTextBytes / 1024) KB." }

    /// True when a file of `bytes` can be sent at all; check before reading the file into memory.
    static func fits(bytes: Int) -> Bool { bytes <= Limits.maxFileBytes }

    static func describe(_ error: Error) -> String {
        if let error = error as? DeviceLinkError {
            switch error {
            case .notConfigured: return "No relay configured. Scan a code from a linked device or the relay's Setup page."
            case .pairing(let reason): return reason
            case .relay(let status, let code): return describe(relayStatus: status, code: code)
            case .invalid(let reason):
                if reason == "text" { return textTooLong }
                if reason.hasPrefix("File is larger") || reason == "file size" { return tooLarge }
                return generic
            }
        }
        if error is URLError { return unreachable }
        return generic
    }

    /// `SendOutcome.error`: a relay error code, or a description of a network error.
    static func describe(outcomeError: String?) -> String {
        guard let code = outcomeError else { return generic }
        if code.contains("NSURLErrorDomain") || code.hasPrefix("URLError") { return unreachable }
        return describe(relayStatus: 0, code: code)
    }

    private static func describe(relayStatus status: Int, code: String) -> String {
        switch code {
        case "mailbox_full": return mailboxFull
        case "body_too_large": return tooLarge
        default:
            if status == 401 || status == 403 { return refused }
            return generic
        }
    }
}
