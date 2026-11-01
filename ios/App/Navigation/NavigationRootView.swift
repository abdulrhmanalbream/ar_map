import MutawwifCore
import SwiftUI

/// Camera-first navigation screen: port of Android's ArNavActivity (phone
/// path only; Eye camera, glasses IMU, sign OCR and the tour are not here).
struct NavigationRootView: View {
    @ObservedObject private var campus = CampusState.shared
    @StateObject private var voice = VoiceGuide()
    @Environment(\.scenePhase) private var scenePhase

    @State private var visible = false
    @State private var pickerVisible = false
    @State private var arStatus: CampusArStatus = .initialising
    @State private var voiceState = CoreBridge.freshVoiceState()
    @State private var voiceTargetId: String?

    private var foreground: Bool { visible && scenePhase == .active }

    var body: some View {
        ZStack {
            if CampusARView.isSupported {
                CampusARView(
                    active: foreground,
                    userPosition: campus.fix?.position,
                    headingDeg: campus.headingDegrees,
                    targetBearingDeg: campus.targetBearing,
                    targetDistanceM: campus.targetDistance,
                    targetName: campus.target?.name,
                    routePoints: campus.routePoints,
                    onStatus: { arStatus = $0 }
                )
                .ignoresSafeArea()
            } else {
                // No ARKit (very old devices): compass guidance still works,
                // because direction never depended on AR in the first place.
                LinearGradient(colors: [NavBrand.green, Color.black], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
                CompassFallbackView(campus: campus)
            }

            VStack(spacing: 8) {
                CompassBar(heading: campus.headingDegrees, targetBearing: campus.targetBearing)
                    .padding(.horizontal, 14)
                    .padding(.top, 6)
                StatusChips(
                    campus: campus,
                    arStatus: CampusARView.isSupported ? arStatus : nil
                )
                .padding(.horizontal, 14)
                Spacer()
                bottomStack
            }
        }
        .environment(\.layoutDirection, .rightToLeft)
        .sheet(isPresented: $pickerVisible) {
            DestinationSheet(campus: campus) { landmark in
                campus.selectTarget(landmark)
                pickerVisible = false
                if foreground && !voice.muted { voice.speakSelected(landmark.name) }
            }
        }
        .onAppear {
            visible = true
            campus.load()
            campus.startSensors()
        }
        .onDisappear {
            visible = false
            resetVoice()
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { resetVoice() }
        }
        .onChange(of: campus.guidance) { _ in onGuidanceTick() }
        .onChange(of: campus.target?.id) { _ in onGuidanceTick() }
    }

    // MARK: Bottom

    @ViewBuilder
    private var bottomStack: some View {
        VStack(spacing: 10) {
            if campus.demoActive {
                DemoControls(campus: campus)
                    .padding(.horizontal, 14)
            }
            if case let .ambiguous(_, candidates) = campus.guidance {
                AmbiguityPrompt(candidates: candidates) { campus.selectTarget($0) }
            } else {
                routeCard
            }
        }
        .padding(.bottom, 10)
    }

    private var routeCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let target = campus.target {
                Text(target.name)
                    .font(NavBrand.font(18, weight: .semibold))
                    .foregroundColor(.white)
                Text(instructionAr(campus.guidance, target.name))
                    .font(NavBrand.font(14))
                    .foregroundColor(NavBrand.gold)
                if let entrance = campus.targetEntrance {
                    Text("عبر \(entrance.nameAr)")
                        .font(NavBrand.font(12))
                        .foregroundColor(NavBrand.muted)
                }
                if let d = campus.targetDistance, d > ARRIVAL_DISTANCE_M {
                    Text("\(formatDistanceAr(d)) · \(walkMinutes(d)) د مشياً")
                        .font(NavBrand.font(12))
                        .foregroundColor(NavBrand.muted)
                }
            } else {
                Text("اختر وجهة للبدء")
                    .font(NavBrand.font(17, weight: .semibold))
                    .foregroundColor(.white)
                Text("الكاميرا والبوصلة تعملان الآن")
                    .font(NavBrand.font(13))
                    .foregroundColor(NavBrand.muted)
            }

            HStack(spacing: 10) {
                ActionChip(campus.target == nil ? "اختر وجهة" : "تغيير الوجهة") { pickerVisible = true }
                if campus.target != nil {
                    ActionChip(voice.muted ? "تشغيل صوت التوجيه" : "كتم التوجيه") {
                        voice.setMuted(!voice.muted)
                        if voice.muted { voiceState = CoreBridge.freshVoiceState() }
                    }
                    ActionChip("إعادة التوجيه") { repeatInstruction() }
                }
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(NavBrand.hudPanel))
        .padding(.horizontal, 14)
    }

    // MARK: Voice (port of onGuidanceTick / repeatNavigationInstruction)

    private func onGuidanceTick() {
        let guidance = campus.guidance
        let target = campus.target
        if target?.id != voiceTargetId {
            resetVoice()
            voiceTargetId = target?.id
        }
        guard foreground, let t = target, !voice.muted else { return }
        let cue = CoreBridge.voiceCue(guidance, t.name)
        var urgent = false
        switch guidance {
        case .arrived, .ambiguous: urgent = true
        default: urgent = false
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let (next, toSpeak) = CoreBridge.nextCue(voiceState, cue, urgent: urgent, nowMs: now, playbackBusy: voice.isSpeaking)
        voiceState = next
        if toSpeak != nil {
            voice.speakGuidance(guidance, t.name)
        }
    }

    private func repeatInstruction() {
        guard let t = campus.target, foreground, !voice.muted else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        voiceState = CoreBridge.voiceState(lastText: CoreBridge.voiceCue(campus.guidance, t.name), lastAtMs: now)
        voice.speakGuidance(campus.guidance, t.name, manualRepeat: true)
    }

    private func resetVoice() {
        voice.stop()
        voiceState = CoreBridge.freshVoiceState()
        voiceTargetId = nil
    }
}

// MARK: - Components

struct ActionChip: View {
    let label: String
    let action: () -> Void

    init(_ label: String, action: @escaping () -> Void) {
        self.label = label
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(NavBrand.font(13, weight: .medium))
                .foregroundColor(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.12)))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(NavBrand.gold.opacity(0.45), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// Heading readout plus an arrow to the target, relative to where the phone
/// points. Kept to one line so it never covers the path on the ground.
struct CompassBar: View {
    let heading: Double?
    let targetBearing: Double?

    var body: some View {
        HStack(spacing: 12) {
            if let h = heading {
                Text("\(Int(h.rounded()))° \(compassPoint(h))")
                    .font(NavBrand.font(14, weight: .semibold))
                    .foregroundColor(.white)
                    .environment(\.layoutDirection, .leftToRight)
            } else {
                Text("جاري قراءة البوصلة…")
                    .font(NavBrand.font(13))
                    .foregroundColor(NavBrand.muted)
            }
            Spacer()
            if let h = heading, let b = targetBearing {
                Image(systemName: "location.north.fill")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(NavBrand.gold)
                    .rotationEffect(.degrees(relativeBearing(h, b)))
                    .animation(.easeOut(duration: 0.2), value: relativeBearing(h, b))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(NavBrand.hudPanel))
    }
}

struct StatusChips: View {
    @ObservedObject var campus: CampusState
    let arStatus: CampusArStatus?

    var body: some View {
        VStack(spacing: 6) {
            GlassesConnectedBadge()
            if campus.locationDenied {
                chip("الموقع غير مسموح — فعّله من الإعدادات", NavBrand.gold)
            } else if campus.fix == nil {
                chip("جاري تحديد موقعك…", NavBrand.muted)
            }
            if campus.compassNeedsCalibration {
                chip("البوصلة تحتاج معايرة — حرّك الجوال على شكل ٨", NavBrand.gold)
            }
            if let status = arStatus, campus.target != nil {
                switch status {
                case .initialising:
                    chip("حرّك الجوال ببطء ليتعرّف على الأرض", NavBrand.muted)
                case .unsupported:
                    chip("الواقع المعزز غير مدعوم — التوجيه بالبوصلة", NavBrand.muted)
                case .waitingForGps, .navigating:
                    EmptyView()
                }
            }
            if campus.demoActive {
                chip("وضع تجريبي — موقع محاكى", NavBrand.gold)
            }
        }
    }

    private func chip(_ text: String, _ colour: Color) -> some View {
        Text(text)
            .font(NavBrand.font(12, weight: .medium))
            .foregroundColor(colour)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color.black.opacity(0.55)))
    }
}

/// GPS cannot tell these buildings apart, so the user decides by looking.
struct AmbiguityPrompt: View {
    let candidates: [Landmark]
    let onPick: (Landmark) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("أنت قريب من عدة مبانٍ — أيّها أمامك؟")
                .font(NavBrand.font(16, weight: .semibold))
                .foregroundColor(.white)
            Text("دقة GPS لا تكفي للتمييز بينها، تأكد بالنظر")
                .font(NavBrand.font(12))
                .foregroundColor(NavBrand.muted)
            ForEach(candidates, id: \.id) { landmark in
                Button { onPick(landmark) } label: {
                    HStack {
                        Text(landmark.name)
                            .font(NavBrand.font(15, weight: .medium))
                            .foregroundColor(.white)
                        Spacer()
                        Text(landmark.category.labelAr)
                            .font(NavBrand.font(12))
                            .foregroundColor(NavBrand.gold)
                    }
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.1)))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(NavBrand.hudPanel))
        .padding(.horizontal, 14)
    }
}

struct DemoControls: View {
    @ObservedObject var campus: CampusState

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                ActionChip("تقدّم ١٠ م") { campus.demoWalk(10) }
                ActionChip("تراجع ١٠ م") { campus.demoWalk(-10) }
                ActionChip("عند الهدف") { campus.demoTeleportToTarget() }
            }
            HStack(spacing: 8) {
                ActionChip("استدر يميناً") { campus.demoTurn(30) }
                ActionChip("استدر يساراً") { campus.demoTurn(-30) }
                ActionChip(campus.useRealHeading ? "بوصلة محاكاة" : "البوصلة الحقيقية") { campus.toggleRealHeading() }
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.black.opacity(0.45)))
    }
}

/// Shown instead of the camera on devices without ARKit.
struct CompassFallbackView: View {
    @ObservedObject var campus: CampusState

    var body: some View {
        VStack(spacing: 18) {
            if let h = campus.headingDegrees, let b = campus.targetBearing {
                Image(systemName: "arrow.up")
                    .font(.system(size: 140, weight: .bold))
                    .foregroundColor(NavBrand.gold)
                    .rotationEffect(.degrees(relativeBearing(h, b)))
            }
            if let d = campus.targetDistance {
                Text(formatDistanceAr(d))
                    .font(NavBrand.font(48, weight: .bold))
                    .foregroundColor(NavBrand.ivory)
            }
        }
    }
}

// MARK: - Destination picker

struct DestinationSheet: View {
    @ObservedObject var campus: CampusState
    let onPick: (Landmark) -> Void
    @State private var query = ""

    var body: some View {
        NavigationView {
            List {
                ForEach(filtered, id: \.landmark.id) { item in
                    Button { onPick(item.landmark) } label: {
                        DestinationRow(item: item, selected: item.landmark.id == campus.target?.id)
                    }
                }
            }
            .listStyle(.plain)
            .searchable(text: $query, prompt: "ابحث عن مبنى أو رقم")
            .navigationTitle("اختر وجهتك")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(campus.demoActive ? "إنهاء التجربة" : "وضع تجريبي") {
                        if campus.demoActive { campus.stopDemo() } else { campus.startDemo() }
                    }
                }
            }
        }
        .environment(\.layoutDirection, .rightToLeft)
    }

    /// Arabic-aware matching from core: digits as whole words, alef/ta
    /// marbuta folding, tashkeel ignored.
    private var filtered: [LandmarkFix] {
        campus.ranked().filter { matchesQuery($0.landmark, query) }
    }
}

struct DestinationRow: View {
    let item: LandmarkFix
    let selected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text(item.landmark.category.letter)
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(NavBrand.ivory)
                .frame(width: 34, height: 34)
                .background(Circle().fill(NavBrand.green))
            VStack(alignment: .leading, spacing: 2) {
                Text(item.landmark.name)
                    .font(NavBrand.font(16, weight: selected ? .bold : .medium))
                    .foregroundColor(.primary)
                Text(item.landmark.category.labelAr)
                    .font(NavBrand.font(12))
                    .foregroundColor(.secondary)
            }
            Spacer()
            if item.distanceMeters.isFinite {
                Text(formatDistanceAr(item.distanceMeters))
                    .font(NavBrand.font(13))
                    .foregroundColor(NavBrand.gold)
            }
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(NavBrand.green)
            }
        }
        .padding(.vertical, 4)
    }
}
