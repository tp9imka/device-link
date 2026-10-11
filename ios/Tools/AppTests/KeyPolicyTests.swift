import XCTest

/// A stored key is replaced only on a device without links, and a key that could not be read is never
/// overwritten: linked peers pinned the old one.
final class KeyPolicyTests: XCTestCase {
    private struct Undecodable: Error {}
    private let stored = Data("stored".utf8)
    private let fresh = Data("fresh".utf8)
    private var written: [Data] = []

    /// Loads with a decoder that accepts only `stored` and a creator that makes `fresh`.
    private func load(_ read: StoredKey, hasLinks: Bool) throws -> Data {
        try KeyPolicy.load(read, as: .encryption, hasLinks: hasLinks,
                           decode: { [stored] data in
                               guard data == stored else { throw Undecodable() }
                               return data
                           },
                           create: { [fresh] in (fresh, fresh) },
                           store: { written.append($0) })
    }

    private func assertLost(_ read: StoredKey, hasLinks: Bool, _ reason: KeysLostError.Reason,
                            file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try load(read, hasLinks: hasLinks), file: file, line: line) { error in
            XCTAssertEqual(error as? KeysLostError, KeysLostError(key: .encryption, reason: reason), file: file, line: line)
        }
        XCTAssertEqual(written, [], "a lost key must not be overwritten", file: file, line: line)
    }

    func testReadableKeyIsUsedAsIs() throws {
        for hasLinks in [false, true] {
            XCTAssertEqual(try load(.found(stored), hasLinks: hasLinks), stored)
        }
        XCTAssertEqual(written, [])
    }

    func testMissingKeyWithoutLinksIsCreatedAndStored() throws {
        XCTAssertEqual(try load(.missing, hasLinks: false), fresh)
        XCTAssertEqual(written, [fresh])
    }

    func testMissingKeyWithLinksIsLost() {
        assertLost(.missing, hasLinks: true, .missing)
    }

    func testCorruptKeyWithoutLinksIsReplaced() throws {
        XCTAssertEqual(try load(.found(Data("garbage".utf8)), hasLinks: false), fresh)
        XCTAssertEqual(written, [fresh])
    }

    func testCorruptKeyWithLinksIsLost() {
        assertLost(.found(Data("garbage".utf8)), hasLinks: true, .unreadable)
    }

    func testUnreadableKeyIsLostWithLinks() {
        assertLost(.unreadable, hasLinks: true, .unreadable)
    }

    func testUnreadableKeyIsLostEvenWithoutLinks() {
        // Before the first unlock the item exists but cannot be read; it recovers by itself.
        assertLost(.unreadable, hasLinks: false, .unreadable)
    }
}
