import DeviceLinkKit
import XCTest

final class SendMessagesTests: XCTestCase {
    func testFailuresReadAsPlainSentences() {
        XCTAssertEqual(SendMessages.describe(DeviceLinkError.relay(status: 429, code: "mailbox_full")), SendMessages.mailboxFull)
        XCTAssertEqual(SendMessages.describe(URLError(.notConnectedToInternet)), SendMessages.unreachable)
        XCTAssertEqual(SendMessages.describe(DeviceLinkError.relay(status: 403, code: "forbidden")), SendMessages.refused)
        XCTAssertEqual(SendMessages.describe(DeviceLinkError.invalid("File is larger than 40 MB")), SendMessages.tooLarge)
        XCTAssertEqual(SendMessages.describe(DeviceLinkError.invalid("text")), SendMessages.textTooLong)
    }

    func testSendOutcomeCodesReadAsPlainSentences() {
        XCTAssertEqual(SendMessages.describe(outcomeError: "mailbox_full"), SendMessages.mailboxFull)
        XCTAssertEqual(SendMessages.describe(outcomeError: String(describing: URLError(.timedOut))), SendMessages.unreachable)
    }

    func testNoRawErrorTextLeaks() {
        struct Odd: Error {}
        for error: Error in [Odd(), DeviceLinkError.invalid("signature"), DeviceLinkError.relay(status: 500, code: "internal")] {
            let text = SendMessages.describe(error)
            XCTAssertFalse(text.contains("Odd") || text.contains("signature") || text.contains("internal"), text)
        }
    }

    func testSizeLimitMatchesTheSDK() {
        XCTAssertTrue(SendMessages.fits(bytes: Limits.maxFileBytes))
        XCTAssertFalse(SendMessages.fits(bytes: Limits.maxFileBytes + 1))
    }
}
