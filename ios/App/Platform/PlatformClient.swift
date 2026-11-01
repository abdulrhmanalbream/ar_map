import Foundation

struct PlatformError: Error, LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

/// Redirects are refused: a bearer token must only ever reach the validated origin.
final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum PlatformSessions {
    static let delegate = NoRedirectDelegate()

    static func make(requestTimeout: TimeInterval, resourceTimeout: TimeInterval) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = requestTimeout
        configuration.timeoutIntervalForResource = resourceTimeout
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    /// Ordinary JSON API: 10 s connect-ish idle window, 30 s overall, like Android.
    static let api = make(requestTimeout: 30, resourceTimeout: 40)
}

final class PlatformClient {
    static let shared = PlatformClient()
    private let store: PlatformStore

    init(store: PlatformStore = .shared) { self.store = store }

    func request(_ path: String, body: [String: Any]? = nil, server: String? = nil,
                 authenticated: Bool = true) async throws -> [String: Any] {
        precondition(path.hasPrefix("/") && !path.contains(".."))
        let origin = try validatedServer(server ?? store.server)
        guard let url = URL(string: origin + "/api/v1" + path) else {
            throw PlatformError(status: 0, message: "تعذّر الاتصال بالخدمة (0)")
        }
        let requestToken = store.token
        var request = URLRequest(url: url)
        request.httpMethod = body == nil ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if authenticated {
            guard !requestToken.isEmpty else { throw PlatformError(status: 0, message: "اربط المجموعة أولاً") }
            request.setValue("Bearer \(requestToken)", forHTTPHeaderField: "Authorization")
        }
        if let body = body {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await PlatformSessions.api.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard data.count <= 1_048_576 else {
            throw PlatformError(status: status, message: "استجابة الخدمة أكبر من الحد المسموح")
        }
        let json = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]) ?? [:]
        guard (200...299).contains(status) else {
            if status == 401 && authenticated { store.invalidateSession(requestToken) }
            throw PlatformError(status: status, message: platformErrorMessage(status: status, response: json))
        }
        return json
    }

    /// Joins the public group by name, or a private group by invitation code.
    func enroll(name: String, language: String, code: String?) async throws {
        let request = try enrollmentRequest(name: name, language: language, code: code)
        let server = PlatformStore.defaultServer
        let response = try await self.request(request.path, body: request.body, server: server, authenticated: false)
        guard let token = response["token"] as? String, !token.isEmpty,
              let group = (response["group"] as? [String: Any])?["name"] as? String else {
            throw PlatformError(status: 0, message: "تعذّر الاتصال بالخدمة (0)")
        }
        try store.enroll(server: server, name: request.name, language: request.language, token: token, group: group)
    }
}
