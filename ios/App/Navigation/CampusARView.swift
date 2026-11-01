import ARKit
import MutawwifCore
import SceneKit
import SwiftUI
import UIKit

/// How close the destination must be before its marker is drawn. Far out the
/// chevrons carry the route; a cube at 180 m is a speck that reads as a
/// rendering glitch rather than a destination.
private let MARKER_VISIBLE_M: Double = 40

/// Assumed camera height above the floor until ARKit finds a plane, so the
/// path still appears on ground ARKit cannot resolve (wide plain roads).
private let ASSUMED_EYE_HEIGHT_M: Float = 1.4

/// Rebuild thresholds. Rebuilding every frame re-creates geometry 60 times a
/// second for a path that barely changed, which is what overheated the
/// Android build before the same gating was added there.
private let REBUILD_ANGLE_DEG: Double = 1.5
private let REBUILD_MOVE_M: Float = 0.75

private let RIBBON_WIDTH_M: Float = 0.5
private let CHEVRON_SPACING_M: Float = 2.0

/// What the AR view wants the UI to say.
enum CampusArStatus: Equatable {
    case initialising
    case waitingForGps
    case navigating
    case unsupported
}

/// GPS-aimed ground path, rendered with ARKit + SceneKit.
///
/// Same division of labour as Android's CampusArRenderer: the DIRECTION comes
/// from GPS bearing and the compass, which never drift, and ARKit supplies
/// only the floor and short-range camera motion. The path is deliberately a
/// short visible stub; distance is carried by the marker and the HUD.
///
/// Difference from Android: ARKit runs with `.gravity` alignment and the
/// coordinator measures the compass bearing of world -Z on every rebuild
/// (compass heading minus the camera's yaw in world space). That keeps the
/// path nailed to the world when the user turns, instead of re-aiming it
/// relative to wherever the camera points now. `.gravityAndHeading` was
/// rejected: ARKit samples the compass only at session start and never
/// corrects it, so one bad reading near a steel structure would skew the
/// whole session, whereas the live heading recovers.
struct CampusARView: UIViewRepresentable {
    var active: Bool
    var userPosition: LatLng?
    var headingDeg: Double?
    var targetBearingDeg: Double?
    var targetDistanceM: Double?
    var targetName: String?
    var routePoints: [LatLng]
    var onStatus: (CampusArStatus) -> Void

    static var isSupported: Bool { ARWorldTrackingConfiguration.isSupported }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> ARSCNView {
        let view = ARSCNView(frame: .zero)
        view.scene = SCNScene()
        view.automaticallyUpdatesLighting = false
        view.antialiasingMode = .multisampling4X
        view.preferredFramesPerSecond = 60
        view.session.delegate = context.coordinator
        context.coordinator.view = view
        context.coordinator.installNodes()
        return view
    }

    func updateUIView(_ uiView: ARSCNView, context: Context) {
        let c = context.coordinator
        c.onStatus = onStatus
        c.userPosition = userPosition
        c.headingDeg = headingDeg
        c.targetBearingDeg = targetBearingDeg
        c.targetDistanceM = targetDistanceM ?? 0
        c.targetName = targetName
        if c.routePoints != routePoints {
            c.routePoints = routePoints
            c.forceRebuild()
        }
        c.setRunning(active)
    }

    static func dismantleUIView(_ uiView: ARSCNView, coordinator: Coordinator) {
        coordinator.setRunning(false)
    }

    final class Coordinator: NSObject, ARSessionDelegate {
        weak var view: ARSCNView?
        var onStatus: ((CampusArStatus) -> Void)?

        var userPosition: LatLng?
        var headingDeg: Double?
        var targetBearingDeg: Double?
        var targetDistanceM: Double = 0
        var targetName: String?
        var routePoints: [LatLng] = []

        private var running = false
        private var floorY: Float?
        private var lastStatus: CampusArStatus?

        private var lastBuiltBearing = Double.nan
        private var lastBuiltFrameBearing = Double.nan
        private var lastBuiltCamera = SIMD3<Float>(0, 0, 0)
        private var lastBuiltDistance = Double.nan
        private var lastLabelText: String?

        private let routeNode = SCNNode()
        private let ribbonNode = SCNNode()
        private let chevronsNode = SCNNode()
        private let markerNode = SCNNode()
        private let labelNode = SCNNode()

        func installNodes() {
            guard let scene = view?.scene else { return }
            routeNode.addChildNode(ribbonNode)
            routeNode.addChildNode(chevronsNode)
            scene.rootNode.addChildNode(routeNode)

            let box = SCNBox(width: 0.35, height: 0.35, length: 0.35, chamferRadius: 0.04)
            box.firstMaterial = Coordinator.glowMaterial(NavBrand.uiGold)
            markerNode.geometry = box
            let spin = SCNAction.repeatForever(SCNAction.rotateBy(x: 0, y: CGFloat.pi * 2, z: 0, duration: 6))
            markerNode.runAction(spin)
            markerNode.isHidden = true
            scene.rootNode.addChildNode(markerNode)

            labelNode.constraints = [SCNBillboardConstraint()]
            labelNode.isHidden = true
            scene.rootNode.addChildNode(labelNode)
        }

        func setRunning(_ shouldRun: Bool) {
            guard let view = view else { return }
            if shouldRun == running { return }
            running = shouldRun
            if shouldRun {
                guard ARWorldTrackingConfiguration.isSupported else {
                    report(.unsupported)
                    return
                }
                let config = ARWorldTrackingConfiguration()
                config.worldAlignment = .gravity
                config.planeDetection = [.horizontal]
                config.isAutoFocusEnabled = true
                floorY = nil
                forceRebuild()
                view.session.run(config, options: [.resetTracking, .removeExistingAnchors])
                report(.initialising)
            } else {
                // The companion tab opens its own AVCaptureSession; iOS gives
                // the camera to one owner at a time, so AR must let go.
                view.session.pause()
            }
        }

        func forceRebuild() {
            lastBuiltBearing = .nan
            lastBuiltFrameBearing = .nan
        }

        // MARK: ARSessionDelegate (main queue: delegateQueue is nil)

        func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            updateFloor(anchors, cameraY: session.currentFrame?.camera.transform.columns.3.y)
        }

        func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
            updateFloor(anchors, cameraY: session.currentFrame?.camera.transform.columns.3.y)
        }

        func session(_ session: ARSession, didUpdate frame: ARFrame) {
            guard running else { return }
            let camera = frame.camera
            let transform = camera.transform
            let camPos = SIMD3<Float>(transform.columns.3.x, transform.columns.3.y, transform.columns.3.z)

            var tracking = false
            if case .normal = camera.trackingState { tracking = true }

            guard let bearing = targetBearingDeg, let heading = headingDeg, targetName != nil else {
                routeNode.isHidden = true
                markerNode.isHidden = true
                labelNode.isHidden = true
                report(tracking ? .waitingForGps : .initialising)
                return
            }

            // Camera yaw in world space, clockwise from -Z (looking down from
            // above, turning right is positive, matching compass bearings).
            let forward = SIMD3<Float>(-transform.columns.2.x, -transform.columns.2.y, -transform.columns.2.z)
            let yawDeg = Double(atan2(forward.x, -forward.z)) * 180 / Double.pi
            let frameBearing = normalise(heading - yawDeg)

            let moved = simd_distance(SIMD3<Float>(camPos.x, 0, camPos.z),
                                      SIMD3<Float>(lastBuiltCamera.x, 0, lastBuiltCamera.z))
            let needsRebuild = lastBuiltBearing.isNaN ||
                abs(shortestDelta(lastBuiltBearing, bearing)) > REBUILD_ANGLE_DEG ||
                lastBuiltFrameBearing.isNaN ||
                abs(shortestDelta(lastBuiltFrameBearing, frameBearing)) > REBUILD_ANGLE_DEG ||
                moved > REBUILD_MOVE_M ||
                abs(targetDistanceM - lastBuiltDistance) > 1.0
            if needsRebuild {
                rebuild(bearing: bearing, frameBearing: frameBearing, camPos: camPos)
                lastBuiltBearing = bearing
                lastBuiltFrameBearing = frameBearing
                lastBuiltCamera = camPos
                lastBuiltDistance = targetDistanceM
            }

            // Faded while tracking is lost: still visible, but honest that
            // the placement is approximate.
            let dim: CGFloat = tracking ? 1.0 : 0.55
            routeNode.opacity = dim
            markerNode.opacity = dim
            report(tracking ? .navigating : .initialising)
        }

        func session(_ session: ARSession, didFailWithError error: Error) {
            report(.initialising)
        }

        // MARK: Geometry

        private func updateFloor(_ anchors: [ARAnchor], cameraY: Float?) {
            guard floorY == nil else { return }
            for anchor in anchors {
                guard let plane = anchor as? ARPlaneAnchor, plane.alignment == .horizontal else { continue }
                let y = plane.transform.columns.3.y
                // A table or bench is a horizontal plane too; the floor is
                // the one well below the camera.
                if let cy = cameraY, y > cy - 0.8 { continue }
                floorY = y
                forceRebuild()
                return
            }
        }

        private func rebuild(bearing: Double, frameBearing: Double, camPos: SIMD3<Float>) {
            let y = (floorY ?? (camPos.y - ASSUMED_EYE_HEIGHT_M)) + 0.02
            let length = CoreBridge.pathLength(targetDistanceM)
            guard length > 0 else {
                routeNode.isHidden = true
                return
            }

            // Prefer the real route: a straight ribbon along the bearing is a
            // pointer, not a path, and ignores every turn.
            var offsets: [SIMD3<Float>] = []
            if let here = userPosition, routePoints.count >= 2 {
                let path = CoreBridge.groundPath(
                    userPosition: here,
                    routePoints: routePoints,
                    frameBearingDeg: frameBearing,
                    maxVisibleMeters: length
                )
                offsets = path.map { SIMD3<Float>($0.x, 0, $0.z) }
            }
            let relRad = shortestDelta(frameBearing, bearing) * Double.pi / 180
            let dirX = Float(sin(relRad))
            let dirZ = Float(-cos(relRad))
            if offsets.count < 2 {
                // No routable network: a straight stub still says which way.
                let start: Float = 0.8
                let total = Float(length)
                let steps = 24
                offsets = (0...steps).map { i in
                    let t = start + (total - start) * (Float(i) / Float(steps))
                    return SIMD3<Float>(dirX * t, 0, dirZ * t)
                }
            }

            let hazard = CoreBridge.hazard(offsets.map { Vec3($0.x, 0, $0.z) }, stairsAhead: false)
            let (ribbonColour, arrowColour) = Coordinator.colours(hazard)

            let world = offsets.map { SIMD3<Float>(camPos.x + $0.x, y, camPos.z + $0.z) }
            ribbonNode.geometry = Coordinator.ribbon(world, width: RIBBON_WIDTH_M, colour: ribbonColour)
            rebuildChevrons(world, colour: arrowColour)
            routeNode.isHidden = false

            // The marker sits on the destination itself, not the end of the
            // stub; a marker that slides forward with every step marks nothing.
            let d = Float(targetDistanceM)
            let markerVisible = targetDistanceM >= 0.5 && targetDistanceM <= MARKER_VISIBLE_M
            let tx = camPos.x + dirX * d
            let tz = camPos.z + dirZ * d
            markerNode.position = SCNVector3(tx, y + 0.5, tz)
            markerNode.isHidden = !markerVisible
            labelNode.position = SCNVector3(tx, y + 1.25, tz)
            labelNode.isHidden = !markerVisible
            if markerVisible { refreshLabel() }
        }

        private func rebuildChevrons(_ points: [SIMD3<Float>], colour: UIColor) {
            chevronsNode.childNodes.forEach { $0.removeFromParentNode() }
            guard points.count >= 2 else { return }
            let geometry = Coordinator.chevronGeometry(colour)
            var travelled: Float = 0
            var nextAt: Float = 1.2
            for i in 1..<points.count {
                let a = points[i - 1]
                let b = points[i]
                let seg = simd_distance(a, b)
                guard seg > 0.0001 else { continue }
                while travelled + seg >= nextAt {
                    let f = (nextAt - travelled) / seg
                    let p = a + (b - a) * f
                    let tangent = (b - a) / seg
                    let yaw = atan2(-tangent.x, -tangent.z)
                    let holder = SCNNode()
                    holder.position = SCNVector3(p.x, p.y + 0.01, p.z)
                    holder.eulerAngles = SCNVector3(0, yaw, 0)
                    let chevron = SCNNode(geometry: geometry)
                    // SCNShape is drawn in XY; lay it flat so its +Y points
                    // along -Z, i.e. forward along the path.
                    chevron.eulerAngles = SCNVector3(-Float.pi / 2, 0, 0)
                    holder.addChildNode(chevron)
                    chevronsNode.addChildNode(holder)
                    nextAt += CHEVRON_SPACING_M
                }
                travelled += seg
            }
        }

        private func refreshLabel() {
            guard let name = targetName else { return }
            let distanceText = "\(Int(targetDistanceM)) م"
            let key = name + "|" + distanceText
            guard key != lastLabelText else { return }
            lastLabelText = key
            guard let image = Coordinator.labelImage(name: name, distance: distanceText) else { return }
            let aspect = image.size.height / max(image.size.width, 1)
            // Grow with distance so the label stays legible as the marker
            // recedes, instead of shrinking with true perspective.
            let width = CGFloat(max(1.2, min(4.0, targetDistanceM * 0.12)))
            let plane = SCNPlane(width: width, height: width * aspect)
            let material = SCNMaterial()
            material.diffuse.contents = image
            material.lightingModel = .constant
            material.isDoubleSided = true
            material.writesToDepthBuffer = false
            plane.firstMaterial = material
            labelNode.geometry = plane
        }

        private func report(_ status: CampusArStatus) {
            guard status != lastStatus else { return }
            lastStatus = status
            // Deferred: report can fire from inside updateUIView, and writing
            // SwiftUI state during a view update is undefined behaviour.
            let callback = onStatus
            DispatchQueue.main.async { callback?(status) }
        }

        // MARK: Static builders

        static func colours(_ hazard: CoreBridge.Hazard) -> (UIColor, UIColor) {
            switch hazard {
            case .clear:
                return (UIColor(red: 1.00, green: 0.84, blue: 0.10, alpha: 0.92),
                        UIColor(red: 1.00, green: 0.97, blue: 0.75, alpha: 0.98))
            case .turn:
                return (UIColor(red: 1.00, green: 0.55, blue: 0.05, alpha: 0.94),
                        UIColor(red: 1.00, green: 0.80, blue: 0.45, alpha: 0.98))
            case .stairs:
                return (UIColor(red: 0.95, green: 0.24, blue: 0.20, alpha: 0.95),
                        UIColor(red: 1.00, green: 0.72, blue: 0.68, alpha: 0.98))
            }
        }

        static func glowMaterial(_ colour: UIColor) -> SCNMaterial {
            let m = SCNMaterial()
            m.diffuse.contents = colour
            m.emission.contents = colour
            m.lightingModel = .constant
            m.isDoubleSided = true
            m.blendMode = .alpha
            return m
        }

        /// Triangle strip with the cross-section perpendicular to the averaged
        /// tangent, which mitres corners on the 0.8 m resampled path.
        static func ribbon(_ points: [SIMD3<Float>], width: Float, colour: UIColor) -> SCNGeometry? {
            guard points.count >= 2 else { return nil }
            var vertices: [SCNVector3] = []
            vertices.reserveCapacity(points.count * 2)
            let half = width / 2
            for i in 0..<points.count {
                let prev = points[max(i - 1, 0)]
                let next = points[min(i + 1, points.count - 1)]
                var tx = next.x - prev.x
                var tz = next.z - prev.z
                let len = (tx * tx + tz * tz).squareRoot()
                if len < 0.0001 { tx = 0; tz = -1 } else { tx /= len; tz /= len }
                let px = -tz * half
                let pz = tx * half
                let p = points[i]
                vertices.append(SCNVector3(p.x + px, p.y, p.z + pz))
                vertices.append(SCNVector3(p.x - px, p.y, p.z - pz))
            }
            let source = SCNGeometrySource(vertices: vertices)
            let indices: [Int32] = (0..<Int32(vertices.count)).map { $0 }
            let element = SCNGeometryElement(indices: indices, primitiveType: .triangleStrip)
            let geometry = SCNGeometry(sources: [source], elements: [element])
            geometry.firstMaterial = glowMaterial(colour)
            return geometry
        }

        static func chevronGeometry(_ colour: UIColor) -> SCNGeometry {
            let path = UIBezierPath()
            path.move(to: CGPoint(x: -0.18, y: -0.06))
            path.addLine(to: CGPoint(x: 0, y: 0.12))
            path.addLine(to: CGPoint(x: 0.18, y: -0.06))
            path.addLine(to: CGPoint(x: 0.11, y: -0.06))
            path.addLine(to: CGPoint(x: 0, y: 0.05))
            path.addLine(to: CGPoint(x: -0.11, y: -0.06))
            path.close()
            let shape = SCNShape(path: path, extrusionDepth: 0.01)
            shape.firstMaterial = glowMaterial(colour)
            return shape
        }

        /// Arabic needs real text shaping, which SCNText does not do reliably;
        /// rendering through UIKit gets ligatures and RTL right.
        static func labelImage(name: String, distance: String) -> UIImage? {
            let paragraph = NSMutableParagraphStyle()
            paragraph.alignment = .center
            paragraph.baseWritingDirection = .rightToLeft
            let text = NSMutableAttributedString(string: name + "\n", attributes: [
                .font: NavBrand.uiFont(44, bold: true),
                .foregroundColor: UIColor.white,
                .paragraphStyle: paragraph
            ])
            text.append(NSAttributedString(string: distance, attributes: [
                .font: NavBrand.uiFont(38, bold: false),
                .foregroundColor: NavBrand.uiGold,
                .paragraphStyle: paragraph
            ]))
            let maxWidth: CGFloat = 560
            let bounds = text.boundingRect(
                with: CGSize(width: maxWidth, height: 400),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                context: nil
            )
            let padding: CGFloat = 28
            let size = CGSize(width: ceil(bounds.width) + padding * 2, height: ceil(bounds.height) + padding * 2)
            let renderer = UIGraphicsImageRenderer(size: size)
            return renderer.image { _ in
                let rect = CGRect(origin: .zero, size: size)
                let bg = UIBezierPath(roundedRect: rect, cornerRadius: 26)
                NavBrand.uiGreen.withAlphaComponent(0.88).setFill()
                bg.fill()
                NavBrand.uiGold.setStroke()
                bg.lineWidth = 3
                bg.stroke()
                text.draw(with: CGRect(x: padding, y: padding, width: ceil(bounds.width), height: ceil(bounds.height)),
                          options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
            }
        }

        private func shortestDelta(_ from: Double, _ to: Double) -> Double {
            var d = (to - from).truncatingRemainder(dividingBy: 360)
            if d > 180 { d -= 360 }
            if d <= -180 { d += 360 }
            return d
        }

        private func normalise(_ deg: Double) -> Double {
            let r = deg.truncatingRemainder(dividingBy: 360)
            return r < 0 ? r + 360 : r
        }
    }
}
