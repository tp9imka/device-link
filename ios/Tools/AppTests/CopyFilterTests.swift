import XCTest

final class CopyFilterTests: XCTestCase {
    private let text = Data("482913".utf8)

    func testOneCopyReportedTwiceIsSentOnce() {
        // iOS posted changedNotification twice for the same change (types added, then removed).
        var filter = CopyFilter()
        XCTAssertTrue(filter.shouldSend(changeCount: 7, ownChangeCount: -1, content: text))
        XCTAssertFalse(filter.shouldSend(changeCount: 7, ownChangeCount: -1, content: text))
    }

    func testTwoStepWriteOfTheSameContentIsSentOnce() {
        var filter = CopyFilter()
        let now = Date()
        XCTAssertTrue(filter.shouldSend(changeCount: 7, ownChangeCount: -1, content: text, now: now))
        XCTAssertFalse(filter.shouldSend(changeCount: 8, ownChangeCount: -1, content: text, now: now.addingTimeInterval(0.1)))
    }

    func testCopyingSomethingElseIsSent() {
        var filter = CopyFilter()
        let now = Date()
        XCTAssertTrue(filter.shouldSend(changeCount: 7, ownChangeCount: -1, content: text, now: now))
        XCTAssertTrue(filter.shouldSend(changeCount: 8, ownChangeCount: -1, content: Data("other".utf8), now: now.addingTimeInterval(0.1)))
    }

    func testCopyingTheSameTextAgainLaterIsSent() {
        var filter = CopyFilter()
        let now = Date()
        XCTAssertTrue(filter.shouldSend(changeCount: 7, ownChangeCount: -1, content: text, now: now))
        XCTAssertTrue(filter.shouldSend(changeCount: 9, ownChangeCount: -1, content: text, now: now.addingTimeInterval(5)))
    }

    func testAReceivedClipDoesNotEchoBack() {
        var filter = CopyFilter()
        XCTAssertFalse(filter.shouldSend(changeCount: 4, ownChangeCount: 4, content: text))
    }
}
