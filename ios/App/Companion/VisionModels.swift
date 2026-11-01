import Foundation

/// Monotonic milliseconds, the iOS counterpart of Android's elapsedRealtime().
/// Wall-clock time is never used for freshness: the user may change the clock mid-ritual.
func visionElapsedMs() -> Int64 {
    Int64(ProcessInfo.processInfo.systemUptime * 1000)
}

struct VisionPose: Equatable {
    var yawDegrees: Double
    var pitchDegrees: Double
    var sessionId: Int64 = 0
    var rollDegrees: Double = 0
}

struct VisionBox: Equatable {
    var yMin: Double
    var xMin: Double
    var yMax: Double
    var xMax: Double
}

struct VisionSource: Equatable, Identifiable {
    var id: String
    var title: String
    var url: String
}

struct VisionDevotional: Equatable, Identifiable {
    var id: String
    var title: String
    var arabicText: String
    var translation: String
    var note: String
    var sourceIds: [String]
}

struct VisionItem: Equatable, Identifiable {
    var id: String
    var kind: String
    var label: String
    var description: String
    var box: VisionBox
    var sourceIds: [String] = []
    var landmarkId: String? = nil
    var devotionals: [VisionDevotional] = []
}

struct VisionAnalysis: Equatable {
    var sceneId: String
    var summary: String
    var items: [VisionItem]
    var sources: [VisionSource]
    var provider: String
}

struct VisionHistoryEntry: Equatable {
    var role: String
    var text: String
}

struct VisionAnswer: Equatable {
    var reply: String
    var sources: [VisionSource]
    var provider: String
}

enum VisionError: String, Error {
    case enrollmentRequired = "ENROLLMENT_REQUIRED"
    case cameraUnavailable = "CAMERA_UNAVAILABLE"
    case frameStale = "FRAME_STALE"
    case consentRequired = "CONSENT_REQUIRED"
    case emptyQuestion = "EMPTY_QUESTION"
    case sceneRequired = "SCENE_REQUIRED"
    case network = "NETWORK"
    case rateLimit = "RATE_LIMIT"
    case authentication = "AUTHENTICATION"
    case invalidResponse = "INVALID_RESPONSE"
    case imageEncoding = "IMAGE_ENCODING"
    case serviceBusy = "SERVICE_BUSY"
    case timeout = "TIMEOUT"
    case serviceUnavailable = "SERVICE_UNAVAILABLE"
    case serviceConfiguration = "SERVICE_CONFIGURATION"
    case serviceAuthentication = "SERVICE_AUTHENTICATION"
    case modelUnavailable = "MODEL_UNAVAILABLE"
    case responseBlocked = "RESPONSE_BLOCKED"
    case uploadTimeout = "UPLOAD_TIMEOUT"
}

struct VisionScreenBox: Equatable {
    var left: Double
    var top: Double
    var right: Double
    var bottom: Double
}
