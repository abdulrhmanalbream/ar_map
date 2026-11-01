import CoreLocation
import Foundation
import MutawwifCore

/// All campus navigation state and the GPS/compass provider that feeds it.
///
/// One instance for the app, held by the root view: like Android's
/// CampusApp singleton, it owns live sensor streams, and two copies would
/// mean two opinions about where the user is.
///
/// Not @MainActor-annotated on purpose: every mutation already arrives on
/// the main thread (CLLocationManager delivers on the thread that created
/// it, the AR coordinator hops to main), and the annotation would make each
/// delegate closure a Swift 5 isolation error to work around.
final class CampusState: ObservableObject {

    /// The phone screen and the glasses scene are separate UIScenes with
    /// separate SwiftUI hierarchies; both must read this one instance.
    static let shared = CampusState()

    @Published private(set) var landmarks: [Landmark] = []
    @Published private(set) var fix: GpsFix?
    @Published private(set) var headingDegrees: Double?
    @Published private(set) var compassNeedsCalibration = false
    @Published private(set) var target: Landmark?
    @Published private(set) var guidance: GuidanceMode = .noFix
    /// Current route geometry (straight to the door until the path network
    /// editor reaches iPhone).
    @Published private(set) var routePoints: [LatLng] = []
    @Published private(set) var locationDenied = false

    /// Demo mode replaces GPS with a simulated position. It exists because
    /// the system is otherwise unjudgeable without standing on the campus;
    /// real landmarks are kept and restored on exit.
    @Published private(set) var demoActive = false
    @Published private(set) var useRealHeading = false

    private let store = LandmarkStore()
    private let sensors = LocationHeadingProvider()
    private var loaded = false
    private var sensorsRunning = false

    private var simulatedPosition: LatLng?
    private var simulatedHeading: Double = 0
    private var realLandmarksBackup: [Landmark] = []
    private var lastRoutedFrom: LatLng?

    // MARK: Lifecycle

    func load() {
        guard !loaded else { return }
        loaded = true
        landmarks = store.load()
        // An empty campus is a blank, useless screen; until a real survey
        // exists, start in demo mode so there is always something to try.
        if landmarks.count < 2 { startDemo() }
    }

    func startSensors() {
        guard !sensorsRunning else { return }
        sensorsRunning = true
        sensors.onAuthorizationChange = { [weak self] status in
            self?.locationDenied = (status == .denied || status == .restricted)
        }
        sensors.onFix = { [weak self] f in
            guard let self = self else { return }
            // Demo supplies its own position; a real fix would teleport the
            // user out of the simulated campus.
            if self.demoActive { return }
            self.fix = f
            self.recomputeGuidance()
        }
        sensors.onHeading = { [weak self] deg, needsCalibration in
            guard let self = self else { return }
            if !self.demoActive || self.useRealHeading {
                self.headingDegrees = deg
                self.compassNeedsCalibration = needsCalibration
                self.recomputeGuidance()
            }
        }
        sensors.start()
        let status = sensors.authorization
        locationDenied = (status == .denied || status == .restricted)
    }

    func stopSensors() {
        guard sensorsRunning else { return }
        sensorsRunning = false
        sensors.stop()
    }

    // MARK: Target

    func selectTarget(_ landmark: Landmark?) {
        if target?.id != landmark?.id {
            lastRoutedFrom = nil
            routePoints = []
        }
        target = landmark
        recomputeGuidance()
    }

    /// Bearing from the user to the target's nearest door.
    var targetBearing: Double? {
        guard let p = fix?.position, p.isValid, let t = target else { return nil }
        return bearingDegrees(p, t.approachPoint(p))
    }

    var targetDistance: Double? {
        guard let p = fix?.position, p.isValid, let t = target else { return nil }
        return distanceMeters(p, t.approachPoint(p))
    }

    var targetEntrance: Entrance? {
        guard let p = fix?.position, let t = target else { return nil }
        return t.nearestEntrance(p)
    }

    func ranked() -> [LandmarkFix] {
        let from = fix?.position ?? LatLng(latitude: .nan, longitude: .nan)
        return rankByDistance(landmarks, from)
    }

    // MARK: Guidance

    private func recomputeGuidance() {
        let position = fix?.position
        if let position = position, let t = target {
            guidance = guidanceFor(
                userPosition: position,
                userHeadingDeg: headingDegrees,
                target: t,
                others: landmarks
            )
        } else {
            guidance = .noFix
        }

        // Re-route only after real movement: re-running A* on every GPS tick
        // and compass degree is wasted work on a phone already running hot.
        if let position = position, let t = target, position.isValid {
            let moved = lastRoutedFrom.map { distanceMeters($0, position) > 5.0 } ?? true
            if moved || routePoints.isEmpty {
                lastRoutedFrom = position
                routePoints = CoreBridge.route(from: position, to: t.approachPoint(position))
            }
        } else if target == nil {
            routePoints = []
        }
    }

    // MARK: Demo

    func startDemo() {
        guard !demoActive else { return }
        realLandmarksBackup = landmarks
        let centre: LatLng
        if let p = fix?.position, p.isValid {
            centre = p
        } else {
            // Fallback so the demo works indoors or before GPS locks on,
            // which is precisely when someone needs it.
            centre = LatLng(latitude: 24.4672, longitude: 39.6111)
        }
        simulatedPosition = centre
        simulatedHeading = headingDegrees ?? 0
        let demo = CoreBridge.demoLandmarks(centre: centre, headingDeg: simulatedHeading)
        landmarks = demo
        demoActive = true
        target = demo.first(where: { $0.name.contains("الملعب") })
        publishSimulatedFix()
    }

    func stopDemo() {
        guard demoActive else { return }
        demoActive = false
        simulatedPosition = nil
        landmarks = realLandmarksBackup
        target = nil
        fix = sensors.lastFix
        headingDegrees = nil
        recomputeGuidance()
    }

    /// Simulated walking; negative steps away from the target.
    func demoWalk(_ metres: Double) {
        guard demoActive, let from = simulatedPosition else { return }
        if metres >= 0 {
            if let t = target?.position {
                simulatedPosition = CoreBridge.step(towards: from, t, metres: metres)
            } else {
                simulatedPosition = CoreBridge.step(from: from, bearingDeg: simulatedHeading, metres: metres)
            }
        } else {
            let away: Double
            if let t = target?.position {
                away = (bearingDegrees(from, t) + 180).truncatingRemainder(dividingBy: 360)
            } else {
                away = (simulatedHeading + 180).truncatingRemainder(dividingBy: 360)
            }
            simulatedPosition = CoreBridge.step(from: from, bearingDeg: away, metres: -metres)
        }
        publishSimulatedFix()
    }

    func demoTurn(_ degrees: Double) {
        guard demoActive else { return }
        useRealHeading = false
        simulatedHeading = (simulatedHeading + degrees + 360).truncatingRemainder(dividingBy: 360)
        headingDegrees = simulatedHeading
        recomputeGuidance()
    }

    func demoTeleportToTarget() {
        guard demoActive, let t = target?.position else { return }
        simulatedPosition = t
        publishSimulatedFix()
    }

    /// Simulated turning proves the maths; only the real compass proves the
    /// phone knows which way it is physically pointing.
    func toggleRealHeading() {
        useRealHeading.toggle()
        if !useRealHeading { simulatedHeading = headingDegrees ?? simulatedHeading }
        recomputeGuidance()
    }

    private func publishSimulatedFix() {
        guard let p = simulatedPosition else { return }
        fix = GpsFix(position: p, accuracyMeters: 5, timestampMs: Int64(Date().timeIntervalSince1970 * 1000))
        if !useRealHeading { headingDegrees = simulatedHeading }
        recomputeGuidance()
    }
}
