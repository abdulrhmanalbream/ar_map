import Foundation

/// Uses the enrollment captured for this consent epoch; never logs images, questions, or responses.
enum VisionAPI {
    /// Covers server queue 8 s + upload 50 s + provider 50 s with a transport margin,
    /// per the platform contract's 120-second client allowance. No hidden retry.
    static let session = PlatformSessions.make(requestTimeout: 120, resourceTimeout: 125)
    static let speechSession = PlatformSessions.make(requestTimeout: 40, resourceTimeout: 45)

    static func request(_ path: String, body: [String: Any], server: String, token: String) async throws -> [String: Any] {
        precondition(path == "/vision/analyze" || path == "/vision/ask")
        guard !token.isEmpty else { throw VisionRequestFailure(error: .authentication, retryable: false) }
        let origin = try validatedServer(server)
        guard let url = URL(string: origin + "/api/v1" + path) else {
            throw VisionRequestFailure(error: .network, retryable: true)
        }
        let payload = try JSONSerialization.data(withJSONObject: body)
        guard payload.count <= 600_000 else { throw VisionRequestFailure(error: .imageEncoding, retryable: false) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = payload

        let result: (Data, URLResponse)
        do {
            result = try await session.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            if error.code == .timedOut { throw VisionRequestFailure(error: .timeout, retryable: true) }
            throw VisionRequestFailure(error: .network, retryable: true)
        }
        try Task.checkCancellation()
        let data = result.0
        let http = result.1 as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard (200...299).contains(status) else {
            if status == 401 { PlatformStore.shared.invalidateSession(token) }
            let body = data.count <= 16_384 ? String(data: data, encoding: .utf8) : nil
            throw VisionFailurePayloads.parse(status: status, body: body,
                                              retryAfter: http?.value(forHTTPHeaderField: "Retry-After"))
        }
        guard data.count <= 1_048_576,
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw VisionRequestFailure(error: .invalidResponse, retryable: true)
        }
        return json
    }

    /// Cloud speech (`/vision/speak`): raw 24 kHz mono s16le PCM. Callers fall back to the
    /// on-device voice on any failure, so errors here are deliberately coarse.
    static func speak(text: String, language: String, server: String, token: String) async throws -> Data {
        guard !token.isEmpty else { throw VisionRequestFailure(error: .authentication, retryable: false) }
        let origin = try validatedServer(server)
        guard let url = URL(string: origin + "/api/v1/vision/speak") else {
            throw VisionRequestFailure(error: .network, retryable: true)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["text": utf16Prefix(text, 1800), "language": language])
        let (data, response) = try await speechSession.data(for: request)
        let http = response as? HTTPURLResponse
        guard let status = http?.statusCode, (200...299).contains(status),
              http?.value(forHTTPHeaderField: "X-Audio-Format") == "pcm_s16le",
              http?.value(forHTTPHeaderField: "X-Sample-Rate") == "24000",
              !data.isEmpty, data.count % 2 == 0, data.count <= 8 * 1024 * 1024 else {
            if http?.statusCode == 401 { PlatformStore.shared.invalidateSession(token) }
            throw VisionRequestFailure(error: .serviceUnavailable, retryable: true)
        }
        return data
    }
}
