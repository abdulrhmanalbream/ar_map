import CoreLocation
import Foundation
import MutawwifCore

/// Fixes worse than this are dropped rather than shown. Same threshold as
/// Android: a 40 m+ fix is wide enough to put the user on the wrong side of
/// a building, and an arrow drawn from it is confidently wrong.
private let MAX_USABLE_ACCURACY_M: Double = 40

/// Above this the compass is treated as needing the figure-of-eight
/// calibration. CoreLocation reports headingAccuracy < 0 when it has no
/// estimate at all; 25° is where an arrow starts aiming at the neighbouring
/// building on this campus.
private let HEADING_CALIBRATION_THRESHOLD_DEG: Double = 25

/// Exponential smoothing on the unit circle. Matches Android's
/// CircularSmoother(alpha = 0.18): raw magnetometer heading jitters by a few
/// degrees even when the phone is still, and an arrow that trembles reads as
/// an app that does not know where it is pointing.
private let HEADING_SMOOTHING_ALPHA: Double = 0.18

/// GPS and compass from one CLLocationManager.
///
/// Unlike Android, CoreLocation supplies true-north heading itself once it
/// has a location (it applies the declination model internally), so there is
/// no GeomagneticField step; magnetic heading is only the fallback before
/// the first fix.
final class LocationHeadingProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()

    var onFix: ((GpsFix) -> Void)?
    var onHeading: ((Double, Bool) -> Void)?
    var onAuthorizationChange: ((CLAuthorizationStatus) -> Void)?

    private(set) var lastFix: GpsFix?
    private var smoothSin: Double?
    private var smoothCos: Double?
    private var lastReported: Double?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        manager.headingFilter = 1
        manager.headingOrientation = .portrait
    }

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }

    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            break
        default:
            break
        }
        manager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            manager.startUpdatingHeading()
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
        smoothSin = nil
        smoothCos = nil
        lastReported = nil
    }

    // MARK: CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthorizationChange?(manager.authorizationStatus)
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let accuracy = location.horizontalAccuracy
        // Negative accuracy means CoreLocation has no valid fix at all.
        if accuracy < 0 || accuracy > MAX_USABLE_ACCURACY_M { return }
        let fix = GpsFix(
            position: LatLng(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude),
            accuracyMeters: Float(accuracy),
            timestampMs: Int64(location.timestamp.timeIntervalSince1970 * 1000)
        )
        lastFix = fix
        onFix?(fix)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let raw = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        guard raw.isFinite, raw >= 0 else { return }
        let needsCalibration = newHeading.headingAccuracy < 0 ||
            newHeading.headingAccuracy > HEADING_CALIBRATION_THRESHOLD_DEG

        let rad = raw * Double.pi / 180
        let s: Double
        let c: Double
        if let ps = smoothSin, let pc = smoothCos {
            s = ps + HEADING_SMOOTHING_ALPHA * (sin(rad) - ps)
            c = pc + HEADING_SMOOTHING_ALPHA * (cos(rad) - pc)
        } else {
            s = sin(rad)
            c = cos(rad)
        }
        smoothSin = s
        smoothCos = c
        var smoothed = atan2(s, c) * 180 / Double.pi
        if smoothed < 0 { smoothed += 360 }

        // Report only real movement, so a still phone does not re-run guidance
        // dozens of times a second for sub-degree noise.
        if let previous = lastReported, abs(relativeBearing(previous, smoothed)) <= 0.5 { return }
        lastReported = smoothed
        onHeading?(smoothed, needsCalibration)
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        // The app shows its own calibration hint; the system sheet would cover
        // the camera at exactly the moment the user is trying to orient.
        false
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // kCLErrorLocationUnknown is transient; CoreLocation keeps trying.
    }
}
