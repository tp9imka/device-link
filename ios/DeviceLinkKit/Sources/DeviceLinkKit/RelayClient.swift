import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct HTTPResponse: Sendable {
    public let status: Int
    public let body: Data
}

/// Transport seam for tests and platforms.
public protocol HTTPTransport: Sendable {
    func send(method: String, url: URL, headers: [String: String], body: Data, timeout: TimeInterval) async throws -> HTTPResponse
}

public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    let session: URLSession

    public init(configuration: URLSessionConfiguration = .ephemeral) {
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.timeoutIntervalForResource = 120
        session = URLSession(configuration: configuration)
    }

    public func send(method: String, url: URL, headers: [String: String], body: Data, timeout: TimeInterval) async throws -> HTTPResponse {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = method
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        if !body.isEmpty { request.httpBody = body }
        let session = session
        let box = TaskBox()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<HTTPResponse, Error>) in
                let task = session.dataTask(with: request) { data, response, error in
                    if let error { continuation.resume(throwing: error); return }
                    continuation.resume(returning: HTTPResponse(status: (response as? HTTPURLResponse)?.statusCode ?? 0, body: data ?? Data()))
                }
                box.task = task
                task.resume()
            }
        } onCancel: {
            box.task?.cancel()
        }
    }

    private final class TaskBox: @unchecked Sendable {
        var task: URLSessionDataTask?
    }
}

public struct RelayInfo: Codable, Sendable {
    public var service: String?
    public var version: String?
    public var maxLifetimeSeconds: Int?
    public var enrollment: String?
    public var push: [String]?
}

public struct DeviceMetadata: Codable, Sendable {
    public var platform: String
    public var model: String
    public var appVersion: String
    public init(platform: String, model: String = "", appVersion: String = "") {
        self.platform = platform; self.model = model; self.appVersion = appVersion
    }
}

public struct PairingStatus: Codable, Sendable {
    public var state: String
    public var joinerId: String?
    public var sealed: String?
}

/// Signed relay API (see server/README.md for the canonical request signature).
public struct RelayClient: Sendable {
    public let baseURL: String
    let identity: DeviceIdentity
    let transport: HTTPTransport
    let clock: @Sendable () -> Int64

    public init(baseURL: String, identity: DeviceIdentity, transport: HTTPTransport, clock: @escaping @Sendable () -> Int64) {
        self.baseURL = baseURL; self.identity = identity; self.transport = transport; self.clock = clock
    }

    public func info() async throws -> RelayInfo { try Wire.decoder.decode(RelayInfo.self, from: try await call("GET", "/v1/info", signed: false)) }

    public func register(_ metadata: DeviceMetadata, enrollmentToken: String? = nil, pairingId: String? = nil) async throws {
        var headers: [String: String] = [:]
        if let enrollmentToken, !enrollmentToken.isEmpty { headers["X-Enrollment-Token"] = enrollmentToken }
        if let pairingId { headers["X-Pairing-Id"] = pairingId }
        _ = try await call("POST", "/v1/register", body: try Wire.encoder().encode(metadata), headers: headers)
    }

    public func allow(_ peer: String) async throws { _ = try await call("PUT", "/v1/peers/\(try checked(peer))", body: Data("{}".utf8)) }
    public func revoke(_ peer: String) async throws { _ = try await call("DELETE", "/v1/peers/\(try checked(peer))") }

    public func createPairing(_ id: String, expiresAt: Int64) async throws {
        _ = try await call("POST", "/v1/pairings", body: Data("{\"id\":\"\(id)\",\"expiresAt\":\(expiresAt)}".utf8))
    }
    public func joinPairing(_ id: String, sealed: String) async throws {
        _ = try await call("POST", "/v1/pairings/\(id)/join", body: Data("{\"sealed\":\"\(sealed)\"}".utf8))
    }
    public func confirmPairing(_ id: String, sealed: String) async throws {
        _ = try await call("POST", "/v1/pairings/\(id)/confirm", body: Data("{\"sealed\":\"\(sealed)\"}".utf8))
    }
    public func pollPairing(_ id: String, wait: Int) async throws -> PairingStatus {
        try Wire.decoder.decode(PairingStatus.self, from: try await call("GET", "/v1/pairings/\(id)?wait=\(wait)", timeout: TimeInterval(wait + 20)))
    }
    public func cancelPairing(_ id: String) async throws { _ = try await call("DELETE", "/v1/pairings/\(id)") }

    public func upload(_ envelope: Envelope) async throws { _ = try await call("POST", "/v1/messages", body: try envelope.encode()) }
    public func poll(wait: Int, limit: Int = 1) async throws -> [Envelope] {
        try Envelope.decodeList(try await call("GET", "/v1/messages?wait=\(wait)&limit=\(limit)", timeout: TimeInterval(wait + 20)))
    }
    public func acknowledge(_ id: String) async throws { _ = try await call("DELETE", "/v1/messages/\(id)") }

    public func registerPush(token: String, environment: String, topic: String) async throws {
        _ = try await call("PUT", "/v1/push", body: Data("{\"provider\":\"apns\",\"token\":\"\(token)\",\"environment\":\"\(environment)\",\"topic\":\"\(topic)\"}".utf8))
    }

    private func checked(_ id: String) throws -> String {
        guard Envelope.isHex64(id) else { throw DeviceLinkError.invalid("peer id") }
        return id
    }

    func call(_ method: String, _ path: String, body: Data = Data(), headers extra: [String: String] = [:],
              signed: Bool = true, timeout: TimeInterval = 30) async throws -> Data {
        guard let url = URL(string: baseURL + path) else { throw DeviceLinkError.invalid("relay URL") }
        var headers = extra
        if !body.isEmpty { headers["Content-Type"] = "application/json" }
        if signed { try signatureHeaders(method: method, path: path, body: body).forEach { headers[$0.key] = $0.value } }
        let response = try await transport.send(method: method, url: url, headers: headers, body: body, timeout: timeout)
        guard (200...299).contains(response.status) else {
            let code = (try? JSONSerialization.jsonObject(with: response.body) as? [String: Any])?["error"] as? String
            throw DeviceLinkError.relay(status: response.status, code: code ?? "http_\(response.status)")
        }
        return response.body
    }

    public func signatureHeaders(method: String, path: String, body: Data) throws -> [String: String] {
        let time = String(clock())
        let nonce = UUID().uuidString.lowercased()
        let canonical = "DeviceLink relay request v1\n\(method)\n\(path)\n\(time)\n\(nonce)\n\(Encoding.hex(Encoding.sha256(body)))"
        return [
            "X-Device-Key": Encoding.b64(identity.publicKeyDER),
            "X-Device-Time": time,
            "X-Device-Nonce": nonce,
            "X-Device-Signature": Encoding.b64(try identity.sign(Data(canonical.utf8))),
        ]
    }
}
