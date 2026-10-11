import XCTest

final class ReceivedFilesTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("ReceivedFilesTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: root) }

    /// Writes `<root>/<id>/file.bin` and backdates the item directory by `age` seconds.
    private func item(_ id: String, age: TimeInterval, now: Date) throws -> URL {
        let directory = root.appendingPathComponent(id)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: directory.appendingPathComponent("file.bin"))
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-age)], ofItemAtPath: directory.path)
        return directory
    }

    func testFilesOlderThanADayAreDeletedAndNewerOnesKept() throws {
        let now = Date()
        let old = try item("old", age: ReceivedFiles.maxAge + 60, now: now)
        let fresh = try item("fresh", age: 60, now: now)

        XCTAssertEqual(ReceivedFiles.prune(in: root, now: now), 1)

        XCTAssertFalse(FileManager.default.fileExists(atPath: old.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fresh.appendingPathComponent("file.bin").path))
    }

    func testStreamedFileLandsWholeAndAFailedOneLeavesNothing() throws {
        let url = root.appendingPathComponent("photo.jpg")
        try ReceivedFiles.write(to: url, replacing: false) { sink in try sink(Data([1, 2])); try sink(Data([3])) }
        XCTAssertEqual(try Data(contentsOf: url), Data([1, 2, 3]))
        XCTAssertThrowsError(try ReceivedFiles.write(to: url, replacing: false) { try $0(Data([9])) }, "never overwrites unless asked")
        try ReceivedFiles.write(to: url, replacing: true) { try $0(Data([4])) }
        XCTAssertEqual(try Data(contentsOf: url), Data([4]))

        struct Cut: Error {}
        let other = root.appendingPathComponent("cut.bin")
        XCTAssertThrowsError(try ReceivedFiles.write(to: other, replacing: true) { sink in try sink(Data([1])); throw Cut() })
        XCTAssertFalse(FileManager.default.fileExists(atPath: other.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), ["photo.jpg"])
    }

    func testMissingDirectoryIsNotAnError() {
        XCTAssertEqual(ReceivedFiles.prune(in: root.appendingPathComponent("absent")), 0)
    }
}
