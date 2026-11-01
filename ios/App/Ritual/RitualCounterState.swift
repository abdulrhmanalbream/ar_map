import Foundation

/// Same semantics as the Android/Wear LapCounter: 0...7, revisions order edits, and a
/// reset starts a new session that stays newer even if the wall clock moves back.
struct LapCounter: Equatable {
    let mode: String
    var count: Int = 0
    var sessionId: String = UUID().uuidString
    var revision: Int64 = 0
    var startedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    init?(mode: String, count: Int = 0, sessionId: String = UUID().uuidString, revision: Int64 = 0,
          startedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        guard mode == "tawaf" || mode == "sai", (0...7).contains(count), revision >= 0, startedAt >= 0,
              !sessionId.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        self.mode = mode
        self.count = count
        self.sessionId = sessionId
        self.revision = revision
        self.startedAt = startedAt
    }

    static func fresh(_ mode: String) -> LapCounter {
        // Mode literals are validated by callers; "tawaf"/"sai" always construct.
        LapCounter(mode: mode == "sai" ? "sai" : "tawaf")!
    }

    func increment() -> LapCounter {
        if count == 7 { return self }
        var next = self; next.count += 1; next.revision += 1
        return next
    }

    func undo() -> LapCounter {
        if count == 0 { return self }
        var next = self; next.count -= 1; next.revision += 1
        return next
    }

    func reset(_ newSessionId: String = UUID().uuidString, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> LapCounter {
        LapCounter(mode: mode, sessionId: newSessionId, startedAt: max(now, startedAt + 1)) ?? self
    }

    var json: [String: Any] {
        ["mode": mode, "count": count, "sessionId": sessionId, "revision": revision, "startedAt": startedAt]
    }

    static func from(_ json: [String: Any]?) -> LapCounter? {
        guard let json = json, let mode = json["mode"] as? String,
              let count = jsonNumber(json["count"]), let revision = jsonNumber(json["revision"]),
              let startedAt = jsonNumber(json["startedAt"]), let sessionId = json["sessionId"] as? String,
              count == count.rounded(), revision == revision.rounded(), startedAt == startedAt.rounded(),
              abs(count) < 1e9, revision >= 0, revision < 9e15, startedAt >= 0, startedAt < 9e15 else { return nil }
        return LapCounter(mode: mode, count: Int(count), sessionId: sessionId, revision: Int64(revision),
                          startedAt: Int64(startedAt))
    }
}

/// Two independent manual sessions. iPhone has no paired Galaxy Watch, so the phone
/// is the only count source here.
struct RitualCounterState: Equatable {
    var selectedMode: String = "tawaf"
    var tawaf: LapCounter = .fresh("tawaf")
    var sai: LapCounter = .fresh("sai")
    var storageError: String? = nil

    var localCounter: LapCounter { selectedMode == "tawaf" ? tawaf : sai }

    func selectMode(_ mode: String) -> RitualCounterState {
        guard mode == "tawaf" || mode == "sai" else { return self }
        var next = self; next.selectedMode = mode
        return next
    }

    func increment() -> RitualCounterState { updateLocal { $0.increment() } }
    func undo() -> RitualCounterState { updateLocal { $0.undo() } }
    func reset(_ sessionId: String, now: Int64) -> RitualCounterState { updateLocal { $0.reset(sessionId, now: now) } }

    private func updateLocal(_ change: (LapCounter) -> LapCounter) -> RitualCounterState {
        var next = self
        if selectedMode == "tawaf" { next.tawaf = change(tawaf) } else { next.sai = change(sai) }
        return next
    }
}

func encodeRitualCounter(_ state: RitualCounterState) -> String {
    let json: [String: Any] = ["selectedMode": state.selectedMode, "source": "PHONE",
                               "tawaf": state.tawaf.json, "sai": state.sai.json]
    guard let data = try? JSONSerialization.data(withJSONObject: json), let text = String(data: data, encoding: .utf8) else {
        return "{}"
    }
    return text
}

func decodeRitualCounter(_ raw: String?) -> RitualCounterState {
    guard let raw = raw else { return RitualCounterState() }
    guard let data = raw.data(using: .utf8),
          let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
        return RitualCounterState(storageError: "readError")
    }
    let tawaf = LapCounter.from(json["tawaf"] as? [String: Any]).flatMap { $0.mode == "tawaf" ? $0 : nil }
    let sai = LapCounter.from(json["sai"] as? [String: Any]).flatMap { $0.mode == "sai" ? $0 : nil }
    let mode = json["selectedMode"] as? String
    return RitualCounterState(selectedMode: (mode == "tawaf" || mode == "sai") ? mode! : "tawaf",
                              tawaf: tawaf ?? .fresh("tawaf"), sai: sai ?? .fresh("sai"),
                              storageError: (tawaf == nil || sai == nil) ? "readError" : nil)
}
