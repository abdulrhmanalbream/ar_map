# MutawwifCore — public API

Swift transliteration of Android `app/src/main/java/com/sarab/vision/core/`.
Foundation only (no UIKit/CoreLocation/simd). `import MutawwifCore`.

Conventions: Kotlin names kept; enum cases lowerCamel (`GuidanceMode.Compass` → `.compass`),
enum `rawValue` = Kotlin enum name (JSON-compatible). Kotlin sealed classes → enums with
associated values labelled like the Kotlin properties. Kotlin `Long` → `Int64`, `Float` kept as
`Float` (Vec3, accuracy, tilt, screen coords). Kotlin `Pair` → tuple.

## Geometry / Poi
```swift
struct Vec3: Equatable, Hashable, Codable { var x, y, z: Float
  init(_ x: Float, _ y: Float, _ z: Float); init(x:y:z:)
  static + - (Vec3, Vec3); static * (Vec3, Float); func dot(_:) -> Float; func length() -> Float
  func normalized() -> Vec3; static let ZERO, UP }
struct Ray { var origin, direction: Vec3; init(origin:direction:) }
func intersectAabb(ray: Ray, center: Vec3, halfExtent: Float) -> Float?
func buildPathPoints(start: Vec3, forward: Vec3, length: Float, segments: Int = 24) -> [Vec3]
```

## Geo
```swift
struct LatLng: Equatable, Hashable, Codable { var latitude, longitude: Double
  init(_ lat: Double, _ lon: Double); init(latitude:longitude:); var isValid: Bool }  // 0,0 = invalid
func distanceMeters(_ from: LatLng, _ to: LatLng) -> Double      // also (from:to:)
func bearingDegrees(_ from: LatLng, _ to: LatLng) -> Double      // [0,360), also (from:to:)
func relativeBearing(_ fromDeg: Double, _ toDeg: Double) -> Double  // (-180,180], also labelled
func toLocalMeters(_ origin: LatLng, _ point: LatLng) -> (Double, Double)  // (east, north)
func formatDistance(_ m: Double) -> String        // "12 m" / "1.2 km"
func walkMinutes(_ m: Double) -> Int
func compassPoint(_ bearingDeg: Double) -> String // "N".."NW"
func turnInstruction(_ relativeDeg: Double) -> String
func averageFixes(_ fixes: [GpsFix]) -> LatLng?
struct GpsFix { var position: LatLng; var accuracyMeters: Float; var timestampMs: Int64; init(position:accuracyMeters:timestampMs:) }
```

## Landmark
```swift
struct Landmark: Equatable, Hashable, Identifiable {
  var id, name: String; var category: LandmarkCategory; var position: LatLng
  var detail = "", hours = ""; var amenities: [String] = []; var photos: [LandmarkPhoto] = []
  var capturedAccuracyM: Float = 0; var placedManually = false; var signTexts: [String] = []; var entrances: [Entrance] = []
  init(id:name:category:position:detail:hours:amenities:photos:capturedAccuracyM:placedManually:signTexts:entrances:)
  var primaryPhoto: LandmarkPhoto?
  func nearestEntrance(_ from: LatLng) -> Entrance?
  func approachPoint(_ from: LatLng) -> LatLng          // nearest door, else position
  static func decodeStore(_ data: Data) throws -> [Landmark]   // reads assets/landmarks.json format
  static func encodeStore(_ landmarks: [Landmark]) throws -> Data }
let LANDMARK_SEED_VERSION: Int   // = Android CURRENT_SEED_VERSION (4)
struct Entrance: Codable, Identifiable { var id, nameAr: String; var position: LatLng; init(id:nameAr:position:) }
struct LandmarkPhoto: Codable { var file: String; var viewpoint: Viewpoint; var takenFrom: LatLng?; var bearingDegrees: Double?; var distanceMeters: Double? }
enum Viewpoint: String { case entrance, side, far, other; var label: String; var name: String }
enum LandmarkCategory: String, CaseIterable { case housing, sports, faculty, services, gate, other
  var label, labelAr, letter, name: String; static func fromName(_:) -> LandmarkCategory }
struct LandmarkFix { var landmark: Landmark; var distanceMeters, bearingDegrees: Double }
func rankByDistance(_ landmarks: [Landmark], _ from: LatLng) -> [LandmarkFix]  // NaN distances when no fix
let AR_HANDOFF_DISTANCE_M = 30.0, ARRIVAL_DISTANCE_M = 8.0
func normaliseForSearch(_ input: String) -> String
func matchesQuery(_ landmark: Landmark, _ query: String) -> Bool
```

## Guidance
```swift
enum GuidanceMode: Equatable {
  case compass(distanceMeters: Double, bearingDegrees: Double, relativeDegrees: Double)
  case arApproach(distanceMeters: Double)
  case ambiguous(distanceMeters: Double, candidates: [Landmark])
  case arrived(distanceMeters: Double)
  case noFix
  var distanceMeters: Double? }
let GPS_DISTINGUISHABLE_M = 25.0
func guidanceFor(userPosition: LatLng, userHeadingDeg: Double?, target: Landmark, others: [Landmark]) -> GuidanceMode
func findAmbiguousPairs(_ landmarks: [Landmark]) -> [(Landmark, Landmark)]
func instructionAr(_ mode: GuidanceMode, _ targetName: String) -> String
func formatDistanceAr(_ meters: Double) -> String   // "250 م" / "1.5 كم"
```

## Heading
```swift
enum HeadingSource: String { case camera, screenUp }
struct HeadingResult { var degrees: Double?; var source: HeadingSource; var tilt: Float }
func cameraDirection(_ r: [Float]) -> (Float, Float, Float)
func screenUpDirection(_ r: [Float], _ displayRotationDeg: Int) -> (Float, Float, Float)
func bearingOf(_ direction: (Float, Float, Float)) -> Double?
func computeHeading(rotationMatrix: [Float], displayRotationDeg: Int, wasFlat: Bool = false) -> HeadingResult
final class CircularSmoother { init(alpha: Double = 0.18); func reset(); func next(_ degrees: Double) -> Double }
```

## AR projection / ground path
```swift
struct ScreenPlacement { var x, y: Float; var visible: Bool; var offScreenDirection: Int; var distanceMeters: Double }
func projectLandmark(userPosition: LatLng, userHeadingDeg: Double, pitchDeg: Double, target: LatLng,
                     horizontalFovDeg: Double = 65, verticalFovDeg: Double = 50) -> ScreenPlacement?
func markerScaleFor(_ distanceMeters: Double) -> Float
func groundPathLength(_ distanceToTargetMeters: Double) -> Double
// Camera frame: +X right, Y=0 ground, -Z forward (relative to heading).
func routeGroundPath(userPosition: LatLng, routePoints: [LatLng], headingDeg: Double,
                     maxVisibleMeters: Double = 25, startOffsetMeters: Double = 0.8) -> [Vec3]
func resamplePath(_ points: [Vec3], spacingMeters: Float = 1.0) -> [Vec3]
func groundPathTurnDegrees(_ points: [Vec3]) -> Double
enum PathHazard: String, CaseIterable { case clear, turn, stairs; var ribbonColour, arrowColour: [Float] /*RGBA*/; var warningAr: String? }
func pathHazardFor(_ groundPath: [Vec3], _ hasStairsAhead: Bool) -> PathHazard
func stairsAhead(_ route: Route?, _ network: PathNetwork, _ from: LatLng, withinMeters: Double = 30) -> Bool
```

## Compass strip / travel mode
```swift
struct CompassTick { var bearingDeg: Double; var x: Float; var label: String; var isCardinal: Bool }
struct CompassTarget { var x: Float; var visible: Bool; var offScreenDirection: Int; var distanceMeters: Double }
let COMPASS_STRIP_SPAN_DEG = 140.0
func compassTicks(headingDeg: Double, stepDeg: Double = 15, spanDeg: Double = COMPASS_STRIP_SPAN_DEG) -> [CompassTick]
func compassTarget(headingDeg: Double, targetBearingDeg: Double, distanceMeters: Double, spanDeg: Double = …) -> CompassTarget
enum TravelMode: String, CaseIterable, Codable { case walk, bike, car; var labelAr: String; var speedMps: Double
  func canUse(_ foot: Bool, _ bike: Bool, _ car: Bool) -> Bool }
func travelMinutes(_ distanceMeters: Double, _ mode: TravelMode) -> Int
```

## Path network / routing / OSM
```swift
struct PathNode: Codable, Identifiable { var id: String; var position: LatLng; init(id:position:) }
struct PathEdge: Codable, Identifiable { var id, fromNodeId, toNodeId: String
  var allowsFoot = true, allowsBike = true, allowsCar = false, hasStairs = false; var name = ""
  init(id:fromNodeId:toNodeId:allowsFoot:allowsBike:allowsCar:hasStairs:name:) }
struct PathNetwork: Equatable, Codable {   // immutable; JSON {"nodes":[…],"edges":[…]}
  let nodes: [PathNode]; let edges: [PathEdge]; init(nodes: = [], edges: = [])
  func copy(nodes:edges:) -> PathNetwork; func node(_ id:) -> PathNode?; func edgesFrom(_ nodeId:) -> [PathEdge]
  func otherEnd(_ edge:, _ nodeId:) -> String; func edgeLength(_:) -> Double
  func nearestNode(_ position: LatLng, maxDistanceM: Double = 60) -> PathNode?; var isEmpty: Bool }
struct Route { var points: [LatLng]; var distanceMeters: Double; var mode: TravelMode; var nodeIds: [String]; var minutes: Int }
func findRoute(_ network:, _ fromNodeId:, _ toNodeId:, _ mode:) -> Route?        // A*; also labelled
func routeTo(_ network:, _ from: LatLng, _ to: LatLng, _ mode:) -> Route           // straight 2-point fallback
struct RouteStep { var instructionAr: String; var distanceMeters: Double; var position: LatLng }
func routeSteps(_ route: Route) -> [RouteStep]
struct OsmWayTags { highway: String; foot, bicycle, motorVehicle, access: String?; name: String }
struct WayPermissions { allowsFoot, allowsBike, allowsCar, hasStairs: Bool; var isUseless: Bool }
struct OsmWay { var id: Int64; var tags: OsmWayTags; var geometry: [LatLng] }
func permissionsFor(_ tags: OsmWayTags) -> WayPermissions?
func overpassQuery(south:west:north:east:timeoutSeconds: = 30) -> String
func buildNetworkFromWays(_ ways: [OsmWay]) -> PathNetwork
func mergeNetworks(_ existing:, _ imported:, snapMetres: Double = 6) -> PathNetwork
```

## Map projection
```swift
struct GeoBounds { minLat, maxLat, minLon, maxLon: Double; var centre: LatLng; static func of(_ points: [LatLng]) -> GeoBounds? }
struct CanvasPoint { var x, y: Float }
final class MapTransform { init(bounds:canvasWidth:canvasHeight:paddingPx: = 60, marginFraction: = 0.15)
  func toCanvas(_ p: LatLng) -> CanvasPoint; func metersPerPixel() -> Double; func scaleBarMeters() -> Int }
func googleMapsLinkFor(_ p: LatLng) -> String
enum MapsLinkResult: Equatable { case found(position: LatLng), needsNetwork, notFound(reason: String) }
func isShortLink(_ raw: String) -> Bool
func parseGoogleMapsLink(_ raw: String) -> MapsLinkResult
```

## Voice
```swift
let VOICE_MIN_GAP_MS: Int64 = 6000
func voiceDistanceBucket(_ meters: Double) -> Int
func voiceCueAr(_ mode: GuidanceMode, _ targetName: String) -> String?
struct VoiceState: Equatable { var lastText: String?; var lastAtMs: Int64; init(lastText: = nil, lastAtMs: = 0) }
func nextVoiceCue(state: VoiceState, cue: String?, urgent: Bool, nowMs: Int64, playbackBusy: Bool = false) -> (VoiceState, String?)
enum NavigationSpeechSegment: Equatable { case clip(id: String, fallbackText: String), text(String) }
// clip id = bundled WAV basename in Android assets/navigation/ar/ (e.g. "nav_ar_distance_150")
enum NavigationDirection: String, CaseIterable { case forward, right, left, back, slightRight, slightLeft; var clipId, spokenText: String }
func navigationDirection(_ relativeDegrees: Double) -> NavigationDirection
let NAVIGATION_DISTANCE_CLIPS: Set<Int>; let NAVIGATION_CLIP_IDS: Set<String>   // 25 / 36
func navigationDistanceSpeech(_ meters: Double) -> NavigationSpeechSegment
func navigationSpeechPlan(_ mode: GuidanceMode, _ targetName: String, manualRepeat: Bool = false) -> [NavigationSpeechSegment]
func navigationSelectionSpeech(_ targetName: String) -> [NavigationSpeechSegment]
func navigationVoiceEligible(foreground:recognitionMode:hasTarget:eyeSource:glassesConnected:muted:) -> Bool
struct NavigationVoiceOption { id, language, country: String; quality: Int; requiresNetwork, installed: Bool }
func selectNavigationVoice(_ voices: [NavigationVoiceOption]) -> NavigationVoiceOption?
```

## Calibration / sign reading / HUD menu / demo / tour
```swift
enum CalibrationStep: String { case wave, raise, done }
struct CalibrationInput { tracking, compassReliable: Bool; tilt: Float; movedDegrees: Double; elapsedMs: Int64 }
struct CalibrationState { var step: CalibrationStep; var progress: Float; var titleAr, bodyAr: String }
func nextCalibrationState(_ current: CalibrationStep, _ input: CalibrationInput) -> CalibrationState
func initialCalibrationStep(_ input: CalibrationInput) -> CalibrationStep
final class SweepTracker { init(); var totalDegrees: Double { get }; func reset(); func update(_ headingDeg: Double) }

enum SignMatch: Equatable { case found(landmark: Landmark, score: Double, margin: Double)
  case unsure(bestGuess: Landmark?, reason: String); case noText }
let SIGN_MIN_SCORE = 0.34, SIGN_MIN_MARGIN = 0.25
func signTokens(_ text: String) -> [String]
func matchSign(_ recognised: [String], _ candidates: [Landmark]) -> SignMatch

enum HudCommand: String { case next, prev, confirm, back, menu }
enum HudMenuState: Equatable { case closed; case destinationMenu(items: [Landmark], selected: Int)
  case ambiguityMenu(candidates: [Landmark], selected: Int); case nextSuggestion(suggestion: Landmark) }
enum HudMenuAction: Equatable { case none; case select(landmark: Landmark); case repeatInstruction }
struct HudMenuResult { var state: HudMenuState; var action: HudMenuAction }
func hudDestinationItems(_ landmarks: [Landmark], _ userPosition: LatLng?) -> [Landmark]
func advanceHudMenu(_ state:, _ command:, _ landmarks:, _ userPosition: LatLng?) -> HudMenuResult
func syncHudMenu(_ state:, previous: GuidanceMode?, current: GuidanceMode, landmarks:, target: Landmark?, userPosition: LatLng?) -> HudMenuState
struct HudMenuRow { name: String; distanceText: String?; selected: Bool }
struct HudMenuView { title: String; rows: [HudMenuRow]; hint: String }
let HUD_MENU_VISIBLE_ROWS = 5
func hudMenuView(_ state: HudMenuState, _ userPosition: LatLng?) -> HudMenuView?
func visibleWindow(_ size: Int, _ selected: Int, visible: Int = 5) -> Range<Int>

enum DemoCampus { static func generate(_ centre: LatLng, headingDeg: Double = 0) -> [Landmark]; static func isDemo(_:) -> Bool }
func stepTowards(_ from: LatLng, _ to: LatLng, _ metres: Double) -> LatLng
func stepAlongBearing(_ from: LatLng, _ bearingDeg: Double, _ metres: Double) -> LatLng
enum CampusSeed { struct Template { name; category; detail; hours; amenities; signTexts }
  static let TEMPLATES: [Template]; static func remaining(_ captured: [Landmark]) -> [Template] }

enum TourAction: Equatable { case hold, selectTarget(nameFragment:), walk(metresPerSecond:), jumpNear(metresShort:),
  turn(degreesPerSecond:), setMode(mode: TravelMode), showMap, showCamera }
struct TourStep { titleAr, captionAr: String; durationMs: Int64; action: TourAction }
enum PresentationTour { static let STEPS: [TourStep]; static var totalDurationMs: Int64 }
struct TourState: Equatable { var stepIndex: Int; var elapsedInStepMs: Int64; var running: Bool
  var step: TourStep?; var finished: Bool; var overallProgress: Float }
func advanceTour(_ state: TourState, _ deltaMs: Int64) -> TourState
func tourWalkDistance(_ state: TourState, _ deltaMs: Int64) -> Double
func tourTurnDegrees(_ state: TourState, _ deltaMs: Int64) -> Double
```

## V2 printed-marker waypoints
```swift
enum ImageMounting: String { case vertical, flat }
struct Destination: Identifiable { id, name, category, detail: String; waypoints: [Vec3]; hours: String; facts: [Fact]; amenities: [String]
  var routeLengthMeters: Float; var walkMinutes: Int }
struct Fact { label, value: String; init(_:_:) }
enum CampusMap { static var mounting: ImageMounting; static var boardHeightMeters: Float
  static let STADIUM, DORMS, ENGINEERING: Destination; static let ALL; static func byId(_:) -> Destination? }
func toImageLocal(_ p: Vec3, _ mounting: ImageMounting, _ boardHeight: Float) -> Vec3   // ARCore image axes
func resampleRoute(_ waypoints: [Vec3], maxSegment: Float = 0.5) -> [Vec3]
func remainingRouteDistance(_ route: [Vec3], _ from: Vec3) -> Float
```

Not ported: `GlassesProjection.kt` (XREAL-only, no iOS equivalent).
