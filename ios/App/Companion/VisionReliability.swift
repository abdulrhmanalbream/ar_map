import Foundation

/// Fixed codes only: a provider's response body must never become diagnostics or UI copy.
struct VisionRequestFailure: Error, Equatable {
    let error: VisionError
    let retryable: Bool
    var retryAfterMs: Int64 = 0
}

enum VisionFailurePayloads {
    private static let codes: [String: VisionError] = [
        "VISION_BUSY": .serviceBusy,
        "VISION_RATE_LIMITED": .rateLimit,
        "VISION_TIMEOUT": .timeout,
        "VISION_UPLOAD_TIMEOUT": .uploadTimeout,
        "VISION_UNAVAILABLE": .serviceUnavailable,
        "VISION_CONFIGURATION": .serviceConfiguration,
        "VISION_AUTHENTICATION": .serviceAuthentication,
        "VISION_MODEL_UNAVAILABLE": .modelUnavailable,
        "VISION_BLOCKED": .responseBlocked,
        "VISION_INVALID_RESPONSE": .invalidResponse,
    ]
    static let permanent: Set<VisionError> = [.authentication, .serviceConfiguration,
                                              .serviceAuthentication, .modelUnavailable, .responseBlocked]

    static func parse(status: Int, body: String?, retryAfter: String?) -> VisionRequestFailure {
        var json: [String: Any]? = nil
        if let body = body, body.utf16.count <= 16_384, let data = body.data(using: .utf8) {
            json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        }
        // HTTP authentication belongs to our enrollment; upstream authentication has its
        // own 503 code and must not sign the user out of the application.
        let error: VisionError
        if status == 401 || status == 403 {
            error = .authentication
        } else if let code = json?["code"] as? String, let mapped = codes[code] {
            error = mapped
        } else if status == 429 {
            error = .rateLimit
        } else if status == 408 || status == 504 {
            error = .timeout
        } else if status >= 500 && status <= 599 {
            error = .serviceUnavailable
        } else {
            error = .invalidResponse
        }
        var jsonSeconds: Int64 = 0
        if let seconds = jsonNumber(json?["retryAfterSeconds"]), seconds.isFinite, seconds >= 1,
           seconds.truncatingRemainder(dividingBy: 1) == 0 {
            jsonSeconds = Int64(min(seconds, 300))
        }
        var headerSeconds: Int64 = 0
        if let header = retryAfter?.trimmingCharacters(in: .whitespaces), let value = Int64(header), value > 0 {
            headerSeconds = min(value, 300)
        }
        let transient = status == 408 || status == 429 || status >= 500
        let explicitlyFinal = jsonBool(json?["retryable"]) == false
        let retryable = transient && !permanent.contains(error) && !explicitlyFinal
        return VisionRequestFailure(error: error, retryable: retryable,
                                    retryAfterMs: max(jsonSeconds, headerSeconds) * 1000)
    }
}

/// One retry budget for analyze and ask. Context switches cannot bypass server advice.
final class VisionRetryPolicy {
    private(set) var failures = 0
    private(set) var retryAtElapsedMs: Int64 = 0
    private(set) var automaticBlocked = false
    private(set) var lastFailure: VisionRequestFailure?

    func failure(_ failure: VisionRequestFailure, now: Int64) {
        failures = min(failures + 1, 8)
        lastFailure = failure
        automaticBlocked = !failure.retryable
        let minimum: Int64
        switch failure.error {
        case .uploadTimeout: minimum = 2_000
        case .serviceBusy: minimum = 5_000
        case .rateLimit: minimum = 60_000
        case .authentication, .serviceAuthentication, .serviceConfiguration, .modelUnavailable: minimum = 60_000
        case .responseBlocked, .imageEncoding: minimum = 1_000
        default: minimum = VisionPolicy.retryDelayMs(failures)
        }
        let delay = max(minimum, min(max(failure.retryAfterMs, 0), 300_000))
        retryAtElapsedMs = now > Int64.max - delay ? Int64.max : now + delay
    }

    func blocked(_ now: Int64, automatic: Bool) -> VisionError? {
        guard let error = lastFailure?.error else { return nil }
        return (now < retryAtElapsedMs || (automatic && automaticBlocked)) ? error : nil
    }

    func success() {
        failures = 0; retryAtElapsedMs = 0; automaticBlocked = false; lastFailure = nil
    }

    /// A different image/question can resolve a content refusal; it cannot repair credentials.
    func newImage() { if lastFailure?.error == .responseBlocked { success() } }
}

/// Imported photographs are local snapshots, never fresh camera samples.
enum VisionInputPolicy {
    static func available(foreground: Bool, cameraAvailable: Bool, imported: Bool, retained: Bool) -> Bool {
        foreground && (cameraAvailable || imported || retained)
    }

    static func frameUsable(imported: Bool, capturedAt: Int64, now: Int64) -> Bool {
        imported || VisionPolicy.freshTimestamp(capturedAt, now: now, maximumAge: VisionPolicy.frameFreshMs)
    }

    static func automaticAllowed(imported: Bool, importing: Bool, paused: Bool) -> Bool {
        !imported && !importing && !paused
    }
}
