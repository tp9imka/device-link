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
    var printURI = false
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
        case "--print-uri": options.printURI = true
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

/// Writes text to the desktop clipboard; true when the clipboard tool accepted it.
@discardableResult
func writeClipboard(_ text: String) -> Bool {
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
        return process.terminationStatus == 0
    }
    return false
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

/// `code` is the 6-digit confirmation code both screens show after linking; the user compares them.
func linkedEvent(_ peer: LinkedPeer) -> [String: Any] {
    ["event": "linked", "peer": peer.id, "name": peer.name, "platform": peer.platform, "code": peer.pairingCode ?? NSNull()]
}

let usage = """
usage: devicelink [--state DIR] [--insecure] [--name NAME] <command>
  setup <setup-link | relay-url [token]>   configure the relay
  invite [--print-uri]                     create a pairing link and wait for the other device. The link holds a
                                           one-time secret: it is written to DIR/pairing-link.txt (0600, deleted
                                           when invite ends); --print-uri prints it to stdout instead
                                           Linked devices print the 6-digit code; check it matches the other screen
  join <pairing-link>                      link with the device that shows the code
  send <text> | send-file <path>           send to all linked devices
  receive [--count N] [--timeout S] [--copy]  print received items as JSON lines; --copy writes text to the
                                           clipboard (receipt "copied"; otherwise "delivered")
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
        if options.printURI {
            print(session.uri)
            fflush(stdout)
            let peer = try await client.awaitPeer(session)
            emit(linkedEvent(peer))
        } else {
            // The link carries the pairing secret: keep it out of stdout, logs and scrollback unless asked.
            let file = options.stateDirectory.appendingPathComponent("pairing-link.txt")
            try? FileManager.default.removeItem(at: file)
            guard FileManager.default.createFile(atPath: file.path, contents: Data((session.uri + "\n").utf8),
                                                 attributes: [.posixPermissions: 0o600]) else {
                throw DeviceLinkError.invalid("cannot write the pairing link file")
            }
            defer { try? FileManager.default.removeItem(at: file) }
            FileHandle.standardError.write(Data("Pairing link written to \(file.path) (valid 5 minutes, single use). Show it as a QR code or open it on the other device; --print-uri prints it here instead.\n".utf8))
            let peer = try await client.awaitPeer(session)
            emit(linkedEvent(peer))
        }
    case "join":
        guard let link = rest.first else { print(usage); exit(2) }
        let peer = try await withTimeout(options.timeout) { try await client.join(link) }
        emit(linkedEvent(peer))
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
                    let copied = writeClipboard(text)
                    emit(["event": "received", "from": item.peer.name, "kind": item.kind.rawValue])
                    return copied ? .copied : .delivered
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
                // "copied" only when the text really went to the clipboard; printed or stored is "delivered".
                var status = ReceiptStatus.delivered
                if let text = item.text {
                    line["text"] = text
                    if copy && !item.stale && writeClipboard(text) { status = .copied }
                }
                if let bytes = item.bytes() { line["name"] = item.fileName ?? ""; line["mime"] = item.mime; line["size"] = bytes.count
                    line["sha256"] = Encoding.hex(Encoding.sha256(bytes)) }
                emit(line)
                return status
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
