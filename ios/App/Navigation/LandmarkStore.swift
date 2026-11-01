import Foundation
import MutawwifCore

/// Offline landmark storage: plain JSON in Application Support, seeded from
/// the bundled landmarks.json shared with Android.
///
/// Same seed rule as Android's LandmarkStore: seeding only on first run is
/// not enough, because any phone that already opened the app has a file and
/// new bundled campus data would never reach it. A stamped seed version lets
/// new entries merge in without touching what the user recorded.
final class LandmarkStore {
    private let fileName = "landmarks.json"
    private let seedStampKey = "smart_mutawwif.landmark_seed_version"

    private var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("SmartMutawwif", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var fileURL: URL { directory.appendingPathComponent(fileName) }

    func load() -> [Landmark] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            let seeded = loadSeed()
            if !seeded.isEmpty { save(seeded) }
            markSeedApplied()
            return seeded
        }

        let existing: [Landmark]
        do {
            existing = try Landmark.decodeStore(Data(contentsOf: fileURL))
        } catch {
            // Never let a corrupt file brick the app: a survey is replaceable,
            // a crash loop is not.
            existing = []
        }

        if UserDefaults.standard.integer(forKey: seedStampKey) >= LANDMARK_SEED_VERSION {
            return existing
        }

        // Merge by id and let the device win: a landmark surveyed standing at
        // the door is worth more than a coordinate shipped in the app.
        let seed = loadSeed()
        let known = Set(existing.map { $0.id })
        let additions = seed.filter { !known.contains($0.id) }

        // Entrances are the exception: doors are new data no existing record
        // can have, unioned by id so a second door in a later release lands.
        var seedById: [String: Landmark] = [:]
        for s in seed { seedById[s.id] = s }
        let updated: [Landmark] = existing.map { own in
            guard let fromSeed = seedById[own.id] else { return own }
            let ownDoorIds = Set(own.entrances.map { $0.id })
            let newDoors = fromSeed.entrances.filter { !ownDoorIds.contains($0.id) }
            if newDoors.isEmpty && !own.signTexts.isEmpty { return own }
            var copy = own
            copy.entrances = own.entrances + newDoors
            if copy.signTexts.isEmpty { copy.signTexts = fromSeed.signTexts }
            return copy
        }
        markSeedApplied()

        if additions.isEmpty && updated == existing { return existing }
        let merged = updated + additions
        save(merged)
        return merged
    }

    func save(_ landmarks: [Landmark]) {
        guard let data = try? Landmark.encodeStore(landmarks) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    private func loadSeed() -> [Landmark] {
        guard let url = Bundle.main.url(forResource: "landmarks", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let parsed = try? Landmark.decodeStore(data) else {
            return []
        }
        return parsed
    }

    private func markSeedApplied() {
        UserDefaults.standard.set(LANDMARK_SEED_VERSION, forKey: seedStampKey)
    }
}
