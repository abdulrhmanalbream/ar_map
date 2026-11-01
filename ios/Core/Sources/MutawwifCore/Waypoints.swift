import Foundation

// Campus destinations and their waypoint routes (the V2 printed-marker mode).
//
// ## Coordinate system
//
// Waypoints are expressed in **image-local space**, relative to the tracked
// reference image (the map board), which acts as the world origin.
//
// ARCore's augmented image pose is defined with:
//   +X = to the right along the image
//   +Y = out of the image face (the direction the board is looking)
//   +Z = down the image
// (An ARKit ARImageAnchor uses a different convention; the app layer converts.)
//
// A map board is normally mounted **vertically on a wall**, so the direction
// that means "walk forwards away from the board" is **+Y**, and the floor
// drops away along **+Z**. That is why routes below grow mainly in Y, with Z
// used for the small drop from board height down to the floor.
//
// If your board is instead lying **flat on the ground** (a floor decal), set
// `ImageMounting.flat` in `CampusMap.mounting` and the same numbers are
// reinterpreted so +Y still means "forwards along the floor".

/// How the physical reference image is mounted in the real world.
public enum ImageMounting: String, CaseIterable {
    /// Board hangs vertically on a wall (the usual case for a map board).
    case vertical = "VERTICAL"
    /// Marker lies flat on the floor, facing the ceiling.
    case flat = "FLAT"
}

/// A campus destination and the route to reach it.
///
/// `waypoints`: route in image-local metres, ordered from the board to the
/// destination. The first point should start slightly in front of the board
/// so the path is not drawn inside the wall.
public struct Destination: Equatable, Identifiable {
    public var id: String
    public var name: String
    public var category: String
    public var detail: String
    public var waypoints: [Vec3]
    /// Opening hours, shown on the detail card.
    public var hours: String
    /// Short labelled facts rendered as a two-column grid on the card.
    public var facts: [Fact]
    /// Amenity chips (step-free access, wifi, ...).
    public var amenities: [String]

    public init(
        id: String,
        name: String,
        category: String,
        detail: String,
        waypoints: [Vec3],
        hours: String = "",
        facts: [Fact] = [],
        amenities: [String] = []
    ) {
        self.id = id
        self.name = name
        self.category = category
        self.detail = detail
        self.waypoints = waypoints
        self.hours = hours
        self.facts = facts
        self.amenities = amenities
    }

    /// Total walking distance along the route, in metres.
    public var routeLengthMeters: Float {
        var acc: Float = 0
        if waypoints.count > 1 {
            for i in 1..<waypoints.count { acc += (waypoints[i] - waypoints[i - 1]).length() }
        }
        return acc
    }

    /// Rough walking time at a normal indoor pace (~1.3 m/s), in minutes.
    /// Always at least 1 so the card never reads "0 min".
    public var walkMinutes: Int {
        return max(1, kToInt((routeLengthMeters / 1.3 / 60).rounded(.up)))
    }
}

/// A single labelled fact on the destination detail card.
public struct Fact: Equatable, Hashable {
    public var label: String
    public var value: String

    public init(_ label: String, _ value: String) {
        self.label = label
        self.value = value
    }

    public init(label: String, value: String) {
        self.label = label
        self.value = value
    }
}

/// The offline campus map.
///
/// Hard-coded by design: V2 is fully offline with no backend. Swapping in a
/// real survey later means replacing these lists, nothing else.
public enum CampusMap {
    /// Set this to match how you physically mount the reference image.
    /// Defaults to VERTICAL because a real campus map board hangs on a wall.
    /// If you are testing with a marker lying flat on a desk or the floor,
    /// switch this to `.flat` and set `boardHeightMeters` to 0.
    public static var mounting: ImageMounting = .vertical

    /// How high the board's centre sits above the floor, in metres.
    ///
    /// Used to drop the route from board height down to ground level so the
    /// path visually lies on the floor rather than floating at chest height.
    /// Only meaningful when `mounting` is `.vertical`.
    public static var boardHeightMeters: Float = 1.5

    public static let STADIUM = Destination(
        id: "dest-stadium",
        name: "Stadium",
        category: "Sports · 350 seats",
        detail: "Main athletics field and grandstand, with an eight-lane " +
            "running track and a full-size pitch. Step-free access via " +
            "the north gate; the ticket desk sits under the west stand.",
        waypoints: [
            Vec3(0.0, 1.0, 0),
            Vec3(0.0, 4.0, 0),
            Vec3(3.0, 7.0, 0),
            Vec3(3.0, 12.0, 0),
            Vec3(6.5, 15.0, 0),
        ],
        hours: "Open 06:00 – 22:00 · Events until 23:30",
        facts: [
            Fact("Building", "Outdoor · North campus"),
            Fact("Capacity", "350 seated"),
            Fact("Track", "8 lanes · 400 m"),
            Fact("Entrance", "North gate"),
        ],
        amenities: ["Step-free", "Parking", "Water", "Changing rooms"]
    )

    public static let DORMS = Destination(
        id: "dest-dorms",
        name: "Dorms",
        category: "Residence · Blocks A–D",
        detail: "Student residence blocks with 24-hour reception in Block A. " +
            "Laundry, study rooms and common areas are on the ground " +
            "floor of each block; visitors register at reception.",
        waypoints: [
            Vec3(0.0, 1.0, 0),
            Vec3(-2.5, 3.5, 0),
            Vec3(-5.0, 6.0, 0),
            Vec3(-5.0, 10.0, 0),
        ],
        hours: "Reception open 24 hours",
        facts: [
            Fact("Blocks", "A, B, C, D"),
            Fact("Floors", "4 per block"),
            Fact("Reception", "Block A · 24h"),
            Fact("Laundry", "Ground floor"),
        ],
        amenities: ["Step-free", "Wi-Fi", "Laundry", "Study rooms"]
    )

    public static let ENGINEERING = Destination(
        id: "dest-engineering",
        name: "Engineering College",
        category: "Faculty · Building E",
        detail: "Lecture halls, the robotics lab and the fabrication " +
            "workshop. The main entrance faces the central courtyard; " +
            "labs are on floors 2 and 3 and need a student card.",
        waypoints: [
            Vec3(0.0, 1.0, 0),
            Vec3(2.0, 3.0, 0),
            Vec3(4.5, 4.5, 0),
            Vec3(8.0, 4.5, 0),
            Vec3(11.0, 7.0, 0),
        ],
        hours: "Sun – Thu 07:30 – 20:00 · Closed Fri",
        facts: [
            Fact("Building", "E · Central campus"),
            Fact("Floors", "4 + basement"),
            Fact("Labs", "Robotics · Fab lab"),
            Fact("Access", "Student card"),
        ],
        amenities: ["Step-free", "Wi-Fi", "Cafeteria", "Prayer room"]
    )

    public static let ALL: [Destination] = [STADIUM, DORMS, ENGINEERING]

    public static func byId(_ id: String) -> Destination? { ALL.first(where: { $0.id == id }) }
}

/// Converts an image-local waypoint into the local space ARCore expects when
/// the pose of the tracked image is applied.
///
/// ARCore image space is (+X right, +Y out of the face, +Z down the image).
/// We author routes as "X = sideways, Y = forwards", which this maps onto the
/// correct axes for the chosen `mounting`.
///
/// - Parameter boardHeight: height of the board centre above the floor, in metres
public func toImageLocal(_ p: Vec3, _ mounting: ImageMounting, _ boardHeight: Float) -> Vec3 {
    switch mounting {
    // Board on a wall: authored +Y (forwards) is the image's +Y (out of the
    // face). The floor is `boardHeight` below the board centre, which in image
    // space is +Z (down the image).
    case .vertical: return Vec3(p.x, p.y, boardHeight)
    // Marker flat on the floor facing up: the image plane already lies on the
    // ground, so authored +Y (forwards) maps to image +Z (down the image, i.e.
    // along the floor) and the route sits on the face itself.
    case .flat: return Vec3(p.x, 0, -p.y)
    }
}

/// Resamples a waypoint route so segments are no longer than `maxSegment`.
///
/// The authored routes have long straight legs. Subdividing them keeps the
/// ribbon's animated pulse smooth and evenly paced along the whole path, and
/// avoids a single huge quad stretching across the scene.
///
/// Corners are preserved exactly: every original waypoint stays in the output.
public func resampleRoute(_ waypoints: [Vec3], maxSegment: Float = 0.5) -> [Vec3] {
    if waypoints.count < 2 || maxSegment <= 0 { return waypoints }

    var out: [Vec3] = []
    out.reserveCapacity(waypoints.count * 4)
    out.append(waypoints[0])

    for k in 1..<waypoints.count {
        let a = waypoints[k - 1]
        let b = waypoints[k]
        let span = b - a
        let len = span.length()
        if len < 1e-6 { continue }

        // Number of sub-segments needed to keep each under maxSegment.
        let steps = max(kToInt((len / maxSegment).rounded(.up)), 1)
        for i in 1...steps {
            out.append(a + span * (Float(i) / Float(steps)))
        }
    }
    return out
}

/// Distance from `from` to the end of the route, following the path.
///
/// Used for the "x m" readout on the destination card. This is walking
/// distance along the waypoints, not straight-line distance, so it reflects
/// what the user will actually travel.
public func remainingRouteDistance(_ route: [Vec3], _ from: Vec3) -> Float {
    if route.count < 2 { return 0 }

    // Find the segment whose closest point is nearest to `from`, then sum the
    // remainder of the route from that point onward.
    var bestSegment = 0
    var bestT: Float = 0
    var bestDistSq = Float.greatestFiniteMagnitude

    for i in 0..<(route.count - 1) {
        let a = route[i]
        let span = route[i + 1] - a
        let lenSq = span.dot(span)
        let t: Float = lenSq < 1e-9 ? 0 : ((from - a).dot(span) / lenSq).coerced(0, 1)
        let closest = a + span * t
        let diff = from - closest
        let dSq = diff.dot(diff)
        if dSq < bestDistSq {
            bestDistSq = dSq
            bestSegment = i
            bestT = t
        }
    }

    // Remainder of the segment the user is currently on...
    let a = route[bestSegment]
    let b = route[bestSegment + 1]
    var total = (b - (a + (b - a) * bestT)).length()

    // ...plus every segment after it.
    var i = bestSegment + 1
    while i < route.count - 1 {
        total += (route[i + 1] - route[i]).length()
        i += 1
    }
    return total
}
