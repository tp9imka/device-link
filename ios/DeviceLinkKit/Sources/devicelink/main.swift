import DeviceLinkKit
import Foundation

// Minimal desktop/terminal client: pair, send and watch. Also used by scripts/interop_e2e.sh.
// State (keys + links) lives in --state DIR (default ~/.config/devicelink), files are 0600.

struct Options {
    var stateDirectory = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/devicelink")
    var insecure = false
    var name = ProcessInfo.processInfo.hostName.components(separatedBy: ".").first ?? "Desktop"
    var timeout: Double = 60
    var count = 1
    var copy = false
    var arguments: [String] = []
}

func parseOptions() -> Options {
    var options = Options()
    var iterator = CommandLine.arguments.dropFirst().makeIterator()
    while let argument = iterator.next() {
        switch argument {
        case "--state": options.stateDirectory = URL(fileURLWithPath: iterator.next() ?? ".")
        case "--insecure": options.insecure = true
        case "--name": options.name = iterator.next() ?? options.name
        case "--timeout": options.timeout = Double(iterator.next() ?? "") ?? options.timeout
        case "--count": options.count = Int(iterator.next() ?? "") ?? options.count
        case "--copy": options.copy = true
        default: options.arguments.append(argument)
        }
    }
    return options
}

func secretFile(_ url: URL, create: () -> Data) throws -> Data {
    if let data = try? Data(contentsOf: url) { return data }
    let data = create()
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    _ = FileManager.default.createFile(atPath: url.path, contents: data, attributes: [.posixPermissions: 0o600])
    return data
}

func makeClient(_ options: Options) throws -> DeviceLinkClient {
    let directory = options.stateDirectory
    let identity = try SoftwareIdentity(rawRepresentation: try secretFile(directory.appendingPathComponent("identity.key")) {
        SoftwareIdentity().privateKey.rawRepresentation
    })
    let encryption = try EncryptionKeyPair(rawRepresentation: try secretFile(directory.appendingPathComponent("encryption.key")) {
        EncryptionKeyPair().privateKey.rawRepresentation
    })
    #if os(macOS)
    let platform = "desktop", model = "macOS"
    #else
    let platform = "desktop", model = "Linux"
    #endif
    return try DeviceLinkClient(identity: identity, encryption: encryption,
                                store: FileLinkStore(url: directory.appendingPathComponent("links.json")),
                                config: LinkConfig(deviceName: options.name, platform: platform, model: model, appVersion: "2.0.0",
                                                   allowInsecureRelay: options.insecure, pollWaitSeconds: 10))
}

func writeClipboard(_ text: String) {
    #if os(macOS)
    let candidates = [["/usr/bin/pbcopy"]]
    #else
    let candidates = [["/usr/bin/wl-copy"], ["/usr/bin/xclip", "-selection", "clipboard"]]
    #endif
    for command in candidates where FileManager.default.isExecutableFile(atPath: command[0]) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: command[0])
        process.arguments = Array(command.dropFirst())
        let pipe = Pipe()
        process.standardInput = pipe
        guard (try? process.run()) != nil else { continue }
        pipe.fileHandleForWriting.write(Data(text.utf8))
        try? pipe.fileHandleForWriting.close()
        process.waitUntilExit()
        return
    }
}

/// Reads the desktop clipboard as text (pbpaste / wl-paste / xclip). Desktops allow this at any time.
func readClipboard() -> String? {
    #if os(macOS)
    let candidates = [["/usr/bin/pbpaste"]]
    #else
    let candidates = [["/usr/bin/wl-paste", "--no-newline"], ["/usr/bin/xclip", "-selection", "clipboard", "-o"]]
    #endif
    for command in candidates where FileManager.default.isExecutableFile(atPath: command[0]) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: command[0])
        process.arguments = Array(command.dropFirst())
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { continue }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return process.terminationStatus == 0 ? String(data: data, encoding: .utf8) : nil
    }
    return nil
}

/// Last text this process put on or saw on the clipboard; shared by both sync directions.
final class ClipboardMemory: @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?
    func get() -> String? { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ text: String?) { lock.lock(); value = text; lock.unlock() }
}

func emit(_ object: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    print(String(data: data, encoding: .utf8)!)
    fflush(stdout)
}

func withTimeout<T: Sendable>(_ seconds: Double, _ body: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await body() }
        group.addTask { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)); throw DeviceLinkError.pairing("Timed out") }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}

let usage = """
usage: devicelink [--state DIR] [--insecure] [--name NAME] <command>
  setup <setup-link | relay-url [token]>   configure the relay
  invite                                   print a pairing link (show it as a QR) and wait for the other device
  join <pairing-link>                      link with the device that shows the code
  send <text> | send-file <path>           send to all linked devices
  receive [--count N] [--timeout S]        print received items as JSON lines
  watch [--copy]                           receive until interrupted; --copy writes text to the clipboard
  sync                                     two-way clipboard: received text is copied, local copies are sent
  peers | unlink <device-id> | id
"""

let options = parseOptions()
guard let command = options.arguments.first else { print(usage); exit(2) }
let rest = Array(options.arguments.dropFirst())

do {
    let client = try makeClient(options)
    switch command {
    case "id":
        print(client.deviceId)
    case "setup":
        guard let first = rest.first else { print(usage); exit(2) }
        if try await !client.applySetupLink(first) {
            try await client.configure(relayURL: first, enrollmentToken: rest.dropFirst().first ?? "")
        }
        try await client.ensureRegistered(force: true)
        print("Relay configured: \(await client.relayURL)")
    case "invite":
        let session = try await client.invite()
        print(session.uri)
        fflush(stdout)
        let peer = try await client.awaitPeer(session)
        emit(["event": "linked", "peer": peer.id, "name": peer.name, "platform": peer.platform])
    case "join":
        guard let link = rest.first else { print(usage); exit(2) }
        let peer = try await withTimeout(options.timeout) { try await client.join(link) }
        emit(["event": "linked", "peer": peer.id, "name": peer.name, "platform": peer.platform])
    case "send", "send-file":
        guard let value = rest.first else { print(usage); exit(2) }
        let content: OutgoingContent
        if command == "send" {
            content = .text(value)
        } else {
            let url = URL(fileURLWithPath: value)
            let ext = url.pathExtension.lowercased()
            let mime = ["png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "gif": "image/gif", "webp": "image/webp"][ext]
            content = mime.map { .image(name: url.lastPathComponent, mime: $0, data: (try? Data(contentsOf: url)) ?? Data()) }
                ?? .file(name: url.lastPathComponent, mime: "application/octet-stream", data: try Data(contentsOf: url))
        }
        for outcome in try await client.send(content) {
            emit(["event": "sent", "peer": outcome.peerId, "item": outcome.itemId ?? NSNull(), "error": outcome.error ?? NSNull()])
        }
    case "sync":
        // Desktops may read the clipboard, so this side is fully automatic in both directions.
        let memory = ClipboardMemory()
        memory.set(readClipboard())
        emit(["event": "sync", "peers": await client.peers.count])
        let receiver = Task {
            await client.runReceiver { item in
                if let text = item.text, !item.stale {
                    memory.set(text)
                    writeClipboard(text)
                    emit(["event": "received", "from": item.peer.name, "kind": item.kind.rawValue])
                    return .copied
                }
                emit(["event": "received", "from": item.peer.name, "kind": item.kind.rawValue, "name": item.fileName ?? ""])
                return .delivered
            }
        }
        while !Task.isCancelled {
            try await Task.sleep(nanoseconds: 700_000_000)
            guard let text = readClipboard(), !text.isEmpty, text != memory.get(),
                  text.utf8.count <= Limits.maxTextBytes else { continue }
            memory.set(text)
            let outcomes = try await client.send(.text(text))
            emit(["event": "sent", "accepted": outcomes.filter(\.accepted).count, "peers": outcomes.count])
        }
        receiver.cancel()
    case "receive", "watch":
        let limit = command == "watch" ? Int.max : options.count
        let copy = options.copy
        var received = 0
        let deadline = Date().addingTimeInterval(command == "watch" ? .infinity : options.timeout)
        while received < limit && Date() < deadline {
            received += try await client.receiveOnce(wait: 10) { item in
                var line: [String: Any] = ["event": "received", "kind": item.kind.rawValue, "from": item.peer.id, "stale": item.stale]
                if let text = item.text { line["text"] = text; if copy && !item.stale { writeClipboard(text) } }
                if let bytes = item.bytes() { line["name"] = item.fileName ?? ""; line["mime"] = item.mime; line["size"] = bytes.count
                    line["sha256"] = Encoding.hex(Encoding.sha256(bytes)) }
                emit(line)
                return item.kind == .text && copy ? .copied : (item.kind == .file ? .delivered : .copied)
            }
        }
        if received < limit && command == "receive" { emit(["event": "timeout", "received": received]); exit(1) }
    case "peers":
        for peer in await client.peers { emit(["peer": peer.id, "name": peer.name, "platform": peer.platform]) }
    case "unlink":
        guard let id = rest.first else { print(usage); exit(2) }
        try await client.unlink(id)
    default:
        print(usage)
        exit(2)
    }
} catch {
    FileHandle.standardError.write(Data("devicelink: \(error)\n".utf8))
    exit(1)
}
