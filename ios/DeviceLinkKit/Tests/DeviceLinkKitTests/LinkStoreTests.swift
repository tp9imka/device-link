import Foundation
import XCTest
@testable import DeviceLinkKit

final class LinkStoreTests: XCTestCase {
    /// Two stores on one file stand in for the app and an extension: separate locks, separate file handles.
    func testConcurrentWritersOnOneFileLoseNoUpdate() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("links.json")
        let stores = [FileLinkStore(url: url), FileLinkStore(url: url)]
        let rounds = 100
        DispatchQueue.concurrentPerform(iterations: stores.count) { index in
            for round in 0..<rounds {
                try! stores[index].update { state in
                    state.sequence += 1
                    state.seen["\(index)-\(round)"] = Int64.max
                }
            }
        }
        let final = FileLinkStore(url: url).load()
        XCTAssertEqual(final.sequence, Int64(stores.count * rounds))
        XCTAssertEqual(final.seen.count, stores.count * rounds)
    }
}
