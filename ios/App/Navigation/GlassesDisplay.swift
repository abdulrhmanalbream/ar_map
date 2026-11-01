import MutawwifCore
import SwiftUI
import UIKit

/// XREAL (and any USB-C display glasses) on iPhone 15 and later.
///
/// iOS exposes the glasses only as an external display: the Eye camera and
/// the glasses IMU are not reachable by apps, so the phone's own camera,
/// ARKit and compass drive navigation and the glasses show a HUD of the
/// result. Plugging in is the whole setup: iOS offers the app a scene for
/// the external screen, and returning our own delegate for it replaces the
/// default mirror with a dedicated HUD.
final class GlassesDisplayMonitor: ObservableObject {
    static let shared = GlassesDisplayMonitor()
    @Published fileprivate(set) var connected = false
}

final class SmartMutawwifAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if connectingSceneSession.role == .windowExternalDisplayNonInteractive {
            let config = UISceneConfiguration(name: "Glasses", sessionRole: connectingSceneSession.role)
            config.delegateClass = GlassesSceneDelegate.self
            return config
        }
        // Nil delegate class: SwiftUI keeps managing the phone's own scene.
        return UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
    }
}

final class GlassesSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let hud = GlassesRootView()
            .environmentObject(CampusState.shared)
        let host = UIHostingController(rootView: hud)
        // Black emits no light on optical see-through glasses, so anything
        // not drawn is simply the real world.
        host.view.backgroundColor = .black
        let window = UIWindow(windowScene: windowScene)
        window.backgroundColor = .black
        window.rootViewController = host
        window.isHidden = false
        self.window = window
        GlassesDisplayMonitor.shared.connected = true
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        window?.isHidden = true
        window = nil
        GlassesDisplayMonitor.shared.connected = false
    }
}

// MARK: - HUD

/// The glasses follow whichever tab the phone shows: route guidance while
/// navigating, recognised landmarks/answers or the lap count otherwise.
struct GlassesRootView: View {
    @ObservedObject private var tabs = AppTabState.shared

    var body: some View {
        switch tabs.selected {
        case .navigation:
            GlassesHudView()
        case .companion, .ritual:
            CompanionGlassesOverlay()
        }
    }
}

/// Mint for "go" and arrival; the phone's translucent panels would render as
/// grey fog over the wearer's view, so this is bright strokes on black only.
private let hudMint = Color(red: 0.49, green: 0.89, blue: 0.55)
private let hudAmber = NavBrand.gold
private let hudMuted = Color(red: 0.72, green: 0.78, blue: 0.75)

/// Mirrors Android's GlassesHud, minus the Eye-camera composition.
struct GlassesHudView: View {
    @EnvironmentObject var campus: CampusState

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            content
                .padding(32)
            if campus.compassNeedsCalibration {
                VStack {
                    Spacer()
                    hudText("البوصلة تحتاج معايرة — حرّك الجوال على شكل ٨", hudAmber, 20)
                        .padding(.bottom, 28)
                }
            }
        }
        .environment(\.layoutDirection, .rightToLeft)
    }

    @ViewBuilder
    private var content: some View {
        if let target = campus.target {
            switch campus.guidance {
            case .noFix:
                VStack(spacing: 14) {
                    hudText(target.name, .white, 30, .semibold)
                    hudText("جاري تحديد موقعك…", hudMuted, 26)
                }
            case .arrived:
                hudText("وصلت إلى \(target.name)", hudMint, 46, .bold)
            case .ambiguous:
                // With buildings closer together than GPS error, asserting an
                // arrival on the glasses would be confidently wrong.
                VStack(spacing: 10) {
                    hudText("أنت قريب من عدة مبانٍ", hudAmber, 38, .semibold)
                    hudText("دقة GPS لا تكفي — تأكد بالنظر ومن الهاتف", hudMuted, 24)
                }
            case let .compass(distance, _, _):
                navigating(target.name, distance: distance)
            case let .arApproach(distance):
                navigating(target.name, distance: distance)
            }
        } else {
            hudText("اختر وجهتك من الهاتف", hudMuted, 30)
        }
    }

    private func navigating(_ name: String, distance: Double) -> some View {
        VStack(spacing: 16) {
            hudText(name, .white, 30, .semibold)
            if let relative = relativeDegrees {
                Image(systemName: "arrow.up")
                    .font(.system(size: 110, weight: .bold))
                    .foregroundColor(hudMint)
                    .rotationEffect(.degrees(relative))
                    .animation(.easeOut(duration: 0.25), value: relative)
            } else {
                hudText("جاري تحديد الاتجاه…", hudMuted, 24)
            }
            hudText(formatDistanceAr(distance), hudAmber, 68, .bold)
            hudText(instructionAr(campus.guidance, name), NavBrand.gold, 26)
        }
    }

    /// Phone heading drives the arrow: the glasses IMU is not available to
    /// iOS apps, so the wearer holds the phone facing where they walk.
    private var relativeDegrees: Double? {
        guard let heading = campus.headingDegrees, let bearing = campus.targetBearing else { return nil }
        return relativeBearing(heading, bearing)
    }

    private func hudText(_ text: String, _ color: Color, _ size: CGFloat, _ weight: Font.Weight = .regular) -> some View {
        Text(text)
            .font(NavBrand.font(size, weight: weight))
            .foregroundColor(color)
            .multilineTextAlignment(.center)
            .lineLimit(3)
            .minimumScaleFactor(0.6)
    }
}

/// Phone-side chip shown while the glasses scene is attached.
struct GlassesConnectedBadge: View {
    @ObservedObject var monitor = GlassesDisplayMonitor.shared

    var body: some View {
        if monitor.connected {
            HStack(spacing: 6) {
                Image(systemName: "eyeglasses")
                Text("النظارة متصلة")
                    .font(NavBrand.font(13, weight: .semibold))
            }
            .foregroundColor(hudMint)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color.black.opacity(0.55)))
        }
    }
}
