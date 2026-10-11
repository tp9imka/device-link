import Foundation
import XCTest
@testable import DeviceLinkKit

final class LinkStoreTests: XCTestCase {
    func testOldStateKeepsItsRegistrationAndNewMetadataPersists() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("links.json")
        try Data(#"{"relayUrl":"https://relay.example","registeredRelay":"https://relay.example","sequence":42}"#.utf8).write(to: url)
        let store = FileLinkStore(url: url)
        let old = store.load()
        XCTAssertEqual(old.relayUrl, old.registeredRelay)
        XCTAssertEqual(old.sequence, 42)
        XCTAssertNil(old.registeredMetadata)
        let metadata = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "2.0.0")
        try store.update { $0.registeredMetadata = metadata }
        var expected = old
        expected.registeredMetadata = metadata
        XCTAssertEqual(FileLinkStore(url: url).load(), expected)
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        XCTAssertEqual(json["registeredMetadata"] as? [String: String],
                       ["platform": "ios", "model": "Phone", "appVersion": "2.0.0"])
    }

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
