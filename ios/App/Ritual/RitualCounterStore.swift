import Foundation
import Combine

/// Local phone tally only; it never sends counts anywhere. Shared so the glasses
/// overlay can mirror the count while the ritual screen is open.
/// Not annotated @MainActor so `.shared` can be referenced from View property
/// initializers on the iOS 16 SDK; every caller is SwiftUI UI code on the main thread.
final class RitualCounterStore: ObservableObject {
    static let shared = RitualCounterStore()

    private static let key = "smart_mutawwif_phone_ritual.state"
    private let defaults: UserDefaults

    @Published private(set) var state: RitualCounterState
    /// True while RitualCounterView is on screen; the glasses overlay shows the count only then.
    @Published var screenActive = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        state = decodeRitualCounter(defaults.string(forKey: RitualCounterStore.key))
    }

    func selectMode(_ mode: String) { update { $0.selectMode(mode) } }
    func increment() { update { $0.increment() } }
    func undo() { update { $0.undo() } }
    /// The caller presents confirmation before invoking this method.
    func reset() { update { $0.reset(UUID().uuidString, now: Int64(Date().timeIntervalSince1970 * 1000)) } }

    private func update(_ change: (RitualCounterState) -> RitualCounterState) {
        let previous = state
        var next = change(previous)
        next.storageError = nil
        var comparablePrevious = previous
        comparablePrevious.storageError = nil
        if next == comparablePrevious { return }
        defaults.set(encodeRitualCounter(next), forKey: RitualCounterStore.key)
        // UserDefaults writes cannot fail synchronously; verify the round trip instead.
        if defaults.string(forKey: RitualCounterStore.key) == nil {
            var failed = previous
            failed.storageError = "saveError"
            state = failed
            return
        }
        state = next
    }
}
