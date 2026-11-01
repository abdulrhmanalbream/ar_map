import Foundation

struct EnrollmentValidationError: Error, LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct EnrollmentRequest {
    let path: String
    let body: [String: String]
    let name: String
    let language: String
}

/// A public join has no invitation or consent fields; opting into sharing happens separately.
func enrollmentRequest(name: String, language: String, code: String?) throws -> EnrollmentRequest {
    let displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !displayName.isEmpty, displayName.utf16.count <= 80 else {
        throw EnrollmentValidationError(message: "أدخل اسماً من حرف إلى ٨٠ حرفاً")
    }
    var body = ["name": displayName, "language": language]
    let path: String
    if let code = code {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw EnrollmentValidationError(message: "أدخل رمز المجموعة الخاصة") }
        body["code"] = trimmed
        path = "/devices/enroll"
    } else {
        path = "/devices/enroll-default"
    }
    return EnrollmentRequest(path: path, body: body, name: displayName, language: language)
}

/// Fixed messages only; server text is never displayed.
func platformErrorMessage(status: Int, response: [String: Any]) -> String {
    if status == 503 && (response["code"] as? String) == "DEFAULT_GROUP_UNAVAILABLE" {
        return "المجموعة العامة غير متاحة الآن؛ حاول لاحقاً أو استخدم رمز مجموعة خاصة من المشرف"
    }
    if status == 401 || status == 403 { return "تعذّر التحقق من الربط أو رمز المجموعة" }
    if status == 429 { return "طلبات كثيرة؛ حاول بعد قليل" }
    return "تعذّر الاتصال بالخدمة (\(status))"
}

/// Only an HTTPS origin with no path, credentials, query or fragment is accepted.
func validatedServer(_ input: String) throws -> String {
    var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
    while text.hasSuffix("/") { text.removeLast() }
    guard let components = URLComponents(string: text), components.scheme == "https",
          let host = components.host, !host.isEmpty, components.user == nil, components.password == nil,
          components.query == nil, components.fragment == nil, components.path.isEmpty,
          let url = components.url else {
        throw EnrollmentValidationError(message: "أدخل عنوان HTTPS فقط، دون مسار أو بيانات دخول")
    }
    return url.absoluteString
}
