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

    func testMissingDirectoryIsNotAnError() {
        XCTAssertEqual(ReceivedFiles.prune(in: root.appendingPathComponent("absent")), 0)
    }
}
