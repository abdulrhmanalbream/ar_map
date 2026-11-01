import SwiftUI
import UIKit

@main
struct SmartMutawwifApp: App {
    /// Supplies the external-display scene for USB-C glasses; the phone's own
    /// scene stays SwiftUI-managed.
    @UIApplicationDelegateAdaptor(SmartMutawwifAppDelegate.self) private var appDelegate

    init() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = NavBrand.uiGreen
        let item = UITabBarItemAppearance()
        item.normal.iconColor = UIColor(white: 1, alpha: 0.6)
        item.normal.titleTextAttributes = [
            .foregroundColor: UIColor(white: 1, alpha: 0.6),
            .font: NavBrand.uiFont(11, bold: false)
        ]
        item.selected.iconColor = NavBrand.uiGold
        item.selected.titleTextAttributes = [
            .foregroundColor: NavBrand.uiGold,
            .font: NavBrand.uiFont(11, bold: true)
        ]
        appearance.stackedLayoutAppearance = item
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }

    var body: some Scene {
        WindowGroup {
            RootTabs()
        }
    }
}

enum AppTab: Hashable {
    case navigation, companion, ritual
}

/// The selected tab is shared with the glasses scene, which lives in a separate
/// UIWindow and cannot read the phone's SwiftUI state directly.
final class AppTabState: ObservableObject {
    static let shared = AppTabState()
    @Published var selected: AppTab = .navigation
}

private struct RootTabs: View {
    @ObservedObject private var tabs = AppTabState.shared

    var body: some View {
        TabView(selection: $tabs.selected) {
            NavigationRootView()
                .tabItem { Label("الملاحة", systemImage: "location.north.line.fill") }
                .tag(AppTab.navigation)
            CompanionRootView()
                .tabItem { Label("المرافق", systemImage: "camera.viewfinder") }
                .tag(AppTab.companion)
            RitualCounterView()
                .tabItem { Label("الطواف والسعي", systemImage: "arrow.triangle.2.circlepath") }
                .tag(AppTab.ritual)
        }
        .tint(NavBrand.gold)
        .environment(\.layoutDirection, .rightToLeft)
        .preferredColorScheme(.dark)
    }
}
