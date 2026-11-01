import Foundation
import Combine

/// Group enrollment for the Smart Mutawwif platform. The device bearer token lives in the
/// Keychain; display fields live in UserDefaults. Server secrets never enter this process.
final class PlatformStore: ObservableObject {
    static let shared = PlatformStore()
    static let defaultServer = "https://sm.hsaie.com"

    private let defaults: UserDefaults
    private let tokenAccount = "device_token"

    @Published private(set) var enrolled: Bool
    @Published private(set) var name: String
    @Published private(set) var groupName: String
    @Published private(set) var message: String
    private(set) var token: String
    private(set) var server: String

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let storedToken = Keychain.read(tokenAccount) ?? ""
        token = storedToken
        server = defaults.string(forKey: "sarab_platform.server") ?? PlatformStore.defaultServer
        name = defaults.string(forKey: "sarab_platform.name") ?? ""
        groupName = defaults.string(forKey: "sarab_platform.group") ?? ""
        enrolled = !storedToken.isEmpty
        message = storedToken.isEmpty ? "المجموعة غير مرتبطة" : ""
    }

    func enroll(server url: String, name: String, language: String, token newToken: String, group: String) throws {
        let validated = try validatedServer(url)
        guard Keychain.write(tokenAccount, newToken) else {
            throw EnrollmentValidationError(message: "تعذّر حفظ الربط على الجهاز")
        }
        defaults.set(validated, forKey: "sarab_platform.server")
        defaults.set(name, forKey: "sarab_platform.name")
        defaults.set(language, forKey: "sarab_platform.language")
        defaults.set(group, forKey: "sarab_platform.group")
        updateOnMain {
            self.token = newToken
            self.server = validated
            self.name = name
            self.groupName = group
            self.enrolled = true
            self.message = "تم الربط"
        }
    }

    /// A 401 for the token that made the request ends this enrollment. A stale request
    /// that finishes after re-enrollment must not sign out the newer binding.
    func invalidateSession(_ requestToken: String) {
        updateOnMain {
            guard !requestToken.isEmpty, requestToken == self.token else { return }
            Keychain.delete(self.tokenAccount)
            self.defaults.removeObject(forKey: "sarab_platform.group")
            self.token = ""
            self.groupName = ""
            self.enrolled = false
            self.message = "انتهى الربط؛ انضم للمجموعة مرة أخرى"
        }
    }

    func leave() { invalidateSession(token) }

    private func updateOnMain(_ change: @escaping () -> Void) {
        if Thread.isMainThread { change() } else { DispatchQueue.main.sync(execute: change) }
    }
}
