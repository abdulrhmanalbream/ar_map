import Foundation

// Projects campus coordinates onto a 2D canvas.
//
// The map is drawn from the surveyed landmarks themselves rather than from a
// pre-drawn campus image. That matters practically: there is no campus map to
// work from, and requiring one would block the whole feature. Auto-fitting the
// bounds means the map works the moment the first two landmarks are captured
// and keeps working as more are added.

/// Geographic bounding box of a set of points.
public struct GeoBounds: Equatable {
    public var minLat: Double
    public var maxLat: Double
    public var minLon: Double
    public var maxLon: Double

    public init(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        self.minLat = minLat
        self.maxLat = maxLat
        self.minLon = minLon
        self.maxLon = maxLon
    }

    public var centre: LatLng {
        return LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    }

    /// Bounds containing every supplied point, or nil if there are none.
    ///
    /// A single point yields a degenerate box; `MapTransform` pads it so a
    /// lone landmark still renders sensibly instead of dividing by zero.
    public static func of(_ points: [LatLng]) -> GeoBounds? {
        let valid = points.filter { $0.isValid }
        if valid.isEmpty { return nil }

        var minLat = Double.greatestFiniteMagnitude
        var maxLat = -Double.greatestFiniteMagnitude
        var minLon = Double.greatestFiniteMagnitude
        var maxLon = -Double.greatestFiniteMagnitude

        for p in valid {
            minLat = min(minLat, p.latitude)
            maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude)
            maxLon = max(maxLon, p.longitude)
        }
        return GeoBounds(minLat: minLat, maxLat: maxLat, minLon: minLon, maxLon: maxLon)
    }
}

/// A point on the canvas, in pixels.
public struct CanvasPoint: Equatable {
    public var x: Float
    public var y: Float

    public init(x: Float, y: Float) {
        self.x = x
        self.y = y
    }
}

/// Maps geographic coordinates to canvas pixels.
///
/// Longitude is scaled by cos(latitude) so the campus keeps its true shape;
/// without that correction a north-south street would look wider or narrower
/// than it really is, and distances read off the map would be wrong.
public final class MapTransform {
    private let bounds: GeoBounds
    private let canvasWidth: Float
    private let canvasHeight: Float
    private let paddingPx: Float

    private let metresPerDegLat = 111_195.0
    private let metresPerDegLon: Double

    private let minEast: Double
    private let minNorth: Double
    private let scale: Double

    /// - Parameter marginFraction: extra margin around the data, as a fraction of its span.
    public init(
        bounds: GeoBounds,
        canvasWidth: Float,
        canvasHeight: Float,
        paddingPx: Float = 60,
        marginFraction: Double = 0.15
    ) {
        self.bounds = bounds
        self.canvasWidth = canvasWidth
        self.canvasHeight = canvasHeight
        self.paddingPx = paddingPx

        let perLat = 111_195.0
        let centreLat = bounds.centre.latitude
        let perLon = 111_195.0 * cos(toRadians(centreLat))
        self.metresPerDegLon = perLon

        // Convert the bounding box into metres so the aspect ratio is honest.
        let spanNorth = (bounds.maxLat - bounds.minLat) * perLat
        let spanEast = (bounds.maxLon - bounds.minLon) * perLon

        // A single landmark (or a tight cluster) has near-zero span; give it a
        // sane default so the map is still usable rather than infinite.
        let paddedNorth = max(spanNorth * (1 + marginFraction * 2), 60.0)
        let paddedEast = max(spanEast * (1 + marginFraction * 2), 60.0)

        let usableW = Double(max(Float(1), canvasWidth - paddingPx * 2))
        let usableH = Double(max(Float(1), canvasHeight - paddingPx * 2))

        // One scale for both axes, so the campus is never stretched.
        let s = min(usableW / paddedEast, usableH / paddedNorth)
        self.scale = s

        let centreEast = (bounds.centre.longitude - bounds.minLon) * perLon
        let centreNorth = (bounds.centre.latitude - bounds.minLat) * perLat

        self.minEast = centreEast - (usableW / 2.0) / s
        self.minNorth = centreNorth - (usableH / 2.0) / s
    }

    /// Projects a coordinate onto the canvas.
    public func toCanvas(_ point: LatLng) -> CanvasPoint {
        let east = (point.longitude - bounds.minLon) * metresPerDegLon
        let north = (point.latitude - bounds.minLat) * metresPerDegLat

        let x = paddingPx + Float((east - minEast) * scale)
        // Canvas y grows downwards, but north grows upwards, so this is
        // inverted. Forgetting that flips the whole map vertically.
        let y = canvasHeight - paddingPx - Float((north - minNorth) * scale)
        return CanvasPoint(x: x, y: y)
    }

    /// Metres per pixel, for drawing a scale bar.
    public func metersPerPixel() -> Double { scale > 0 ? 1.0 / scale : 0.0 }

    /// A round scale-bar length that fits comfortably on screen.
    public func scaleBarMeters() -> Int {
        let target = metersPerPixel() * Double(canvasWidth * 0.25)
        let candidates = [10, 20, 50, 100, 200, 500, 1000]
        return candidates.first(where: { Double($0) >= target }) ?? 1000
    }
}
