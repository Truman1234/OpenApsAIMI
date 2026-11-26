import SwiftUI

/// The third page: what the watch can ask the phone to do. Each action is only a request. The phone
/// checks it and sends back a confirmation screen; nothing happens before the user confirms that.
///
/// Each action opens as a sheet. watchOS draws a close button (×) on a sheet, so the way back is
/// always visible, and the sheet closes by itself once the request is sent.
struct ActionsPage: View {

    @EnvironmentObject private var session: WatchSession
    @State private var open: Action?

    private var controlOn: Bool { session.preferences?.wearControl ?? false }

    enum Action: String, Identifiable {
        case bolus, wizard, aimiMode, tempTarget, runningMode
        var id: String { rawValue }
    }

    var body: some View {
        List {
            Button { open = .bolus } label: { Label("Bolus", systemImage: "drop.fill") }
            Button { open = .wizard } label: { Label("Wizard", systemImage: "fork.knife") }
            Button { open = .aimiMode } label: { Label("AIMI modes", systemImage: "sparkles") }
            Button { open = .tempTarget } label: { Label("Temp target", systemImage: "target") }
            Button { open = .runningMode } label: { Label("Running mode", systemImage: "arrow.triangle.2.circlepath") }
            if !controlOn {
                Text("Wear control is off on the iPhone. The phone will refuse these actions.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .sheet(item: $open) { action in
            NavigationStack {
                switch action {
                case .bolus: BolusView()
                case .wizard: WizardView()
                case .aimiMode: AimiModeView()
                case .tempTarget: TempTargetView()
                case .runningMode: RunningModeView()
                }
            }
        }
    }
}

// MARK: Bolus

private struct BolusView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    /// The amount in hundredths of a unit, so the wheel has no rounding problems.
    @State private var hundredths = 0

    /// 0 to the phone's max bolus in steps of 0.05 U. The phone rounds to the pump step and caps again.
    private var amounts: [Int] {
        Array(stride(from: 0, through: Int(((session.preferences?.maxBolus ?? 10) * 100).rounded()), by: 5))
    }

    var body: some View {
        Picker("Bolus", selection: $hundredths) {
            ForEach(amounts, id: \.self) { Text(insulinText($0)).tag($0) }
        }
        .pickerStyle(.wheel)
        .labelsHidden()
        .navigationTitle("Bolus")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    session.bolus(insulin: Double(hundredths) / 100, carbs: 0)
                    dismiss()
                } label: {
                    Image(systemName: "checkmark")
                }
                .accessibilityLabel("Check on iPhone")
                .disabled(hundredths <= 0)
            }
        }
    }

    private func insulinText(_ value: Int) -> String {
        String(localized: "\((Double(value) / 100).formatted(.number.precision(.fractionLength(2)))) U")
    }
}

// MARK: Wizard

private struct WizardView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    @State private var carbs = 0

    /// 0 to the phone's max carbs in steps of 1 g.
    private var amounts: [Int] { Array(0...max(0, session.preferences?.maxCarbs ?? 100)) }

    var body: some View {
        Picker("Carbs", selection: $carbs) {
            ForEach(amounts, id: \.self) { Text("\($0) g").tag($0) }
        }
        .pickerStyle(.wheel)
        .labelsHidden()
        .navigationTitle("Wizard")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    session.wizard(carbs: carbs, percentage: session.preferences?.bolusPercentage ?? 100)
                    dismiss()
                } label: {
                    Image(systemName: "checkmark")
                }
                .accessibilityLabel("Calculate on iPhone")
            }
        }
    }
}

// MARK: AIMI modes

/// One AIMI mode: the `ModePreset.id` of the phone, its label, icon and preset duration.
/// `presetMinutes` is a copy of `ModePreset.defaultDurationMin`. It is only the start value of the
/// custom wheel; the preset buttons let the phone use its own value.
private struct AimiMode: Identifiable {
    let id: String
    let title: LocalizedStringKey
    let icon: String
    let presetMinutes: Int
}

private let aimiModes = [
    AimiMode(id: "breakfast", title: "Breakfast", icon: "sunrise", presetMinutes: 60),
    AimiMode(id: "lunch", title: "Lunch", icon: "sun.max", presetMinutes: 60),
    AimiMode(id: "dinner", title: "Dinner", icon: "moon", presetMinutes: 60),
    AimiMode(id: "snack", title: "Snack", icon: "carrot", presetMinutes: 30),
    AimiMode(id: "highcarb", title: "High carb", icon: "birthday.cake", presetMinutes: 90),
    AimiMode(id: "meal", title: "Meal", icon: "fork.knife", presetMinutes: 60),
    AimiMode(id: "sport", title: "Sport", icon: "figure.run", presetMinutes: 120),
    AimiMode(id: "sleep", title: "Sleep", icon: "bed.double", presetMinutes: 480),
    AimiMode(id: "stop", title: "Stop modes", icon: "stop.circle", presetMinutes: 0),
]

/// The durations of the custom sheets: 15 min to 12 h in 15 min steps.
private let customDurations = Array(stride(from: 15, through: 720, by: 15))

/// "45 min", "2 h" or "1 h 30".
private func durationText(_ minutes: Int) -> String {
    let hours = minutes / 60
    let rest = minutes % 60
    if hours == 0 { return String(localized: "\(rest) min") }
    if rest == 0 { return String(localized: "\(hours) h") }
    return String(localized: "\(hours) h \(rest)")
}

/// The AIMI modes: the meals start the P1/P2 prebolus, sport and sleep change how AIMI doses, stop ends
/// them. A preset button lets the phone use the mode's own duration; "Custom" lets the user choose it.
/// The phone shows a confirmation before it stores anything.
private struct AimiModeView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    @State private var custom = false

    var body: some View {
        List {
            Button {
                custom = true
            } label: {
                Label("Custom", systemImage: "slider.horizontal.3")
            }
            ForEach(aimiModes) { mode in
                Button {
                    session.aimiMode(mode.id)
                    dismiss()
                } label: {
                    Label(mode.title, systemImage: mode.icon)
                }
            }
        }
        .navigationTitle("AIMI modes")
        .sheet(isPresented: $custom) {
            NavigationStack {
                CustomAimiModeView { modeId, minutes in
                    session.aimiMode(modeId, minutes: minutes)
                    custom = false
                    dismiss()
                }
            }
        }
    }
}

/// Swipe left and right to pick the mode, scroll the wheel up and down for the duration.
/// X (top left, from the system) cancels, ✓ (top right) sends.
private struct CustomAimiModeView: View {

    let send: (String, Int) -> Void

    /// "stop" has no duration, so it is not here.
    private let modes = aimiModes.filter { $0.presetMinutes > 0 }
    @State private var selected = aimiModes[0].id
    @State private var minutes: [String: Int] = Dictionary(uniqueKeysWithValues: aimiModes.map { ($0.id, $0.presetMinutes) })

    var body: some View {
        TabView(selection: $selected) {
            ForEach(modes) { mode in
                VStack(spacing: 2) {
                    Label(mode.title, systemImage: mode.icon)
                        .font(.title3)
                    Picker("Duration", selection: duration(of: mode.id)) {
                        ForEach(customDurations, id: \.self) { Text(durationText($0)).tag($0) }
                    }
                    .pickerStyle(.wheel)
                    .labelsHidden()
                }
                .tag(mode.id)
            }
        }
        .tabViewStyle(.page)
        .navigationTitle("Custom")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    send(selected, minutes[selected] ?? 60)
                } label: {
                    Image(systemName: "checkmark")
                }
                .accessibilityLabel("Send")
            }
        }
    }

    private func duration(of modeId: String) -> Binding<Int> {
        Binding(get: { minutes[modeId] ?? 60 }, set: { minutes[modeId] = $0 })
    }
}

// MARK: Temp target

private struct TempTargetView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    @State private var custom = false

    var body: some View {
        List {
            Button {
                custom = true
            } label: {
                Label("Custom", systemImage: "slider.horizontal.3")
            }
            button("Activity", "PRESET_ACTIVITY", "figure.walk")
            button("Eating soon", "PRESET_EATING", "fork.knife")
            button("Hypo", "PRESET_HYPO", "exclamationmark.triangle")
            button("Cancel temp target", "CANCEL", "xmark.circle")
        }
        .navigationTitle("Temp target")
        .sheet(isPresented: $custom) {
            NavigationStack {
                CustomTempTargetView(isMgdl: session.preferences?.unitsMgdl ?? true) { target, isMgdl, minutes in
                    session.tempTarget(target: target, isMgdl: isMgdl, minutes: minutes)
                    custom = false
                    dismiss()
                }
            }
        }
    }

    /// `command` is a Kotlin `ActionTempTargetPreCheck.TempTargetCommand` name. The phone uses its own
    /// preset values for target and duration.
    private func button(_ title: LocalizedStringKey, _ command: String, _ icon: String) -> some View {
        Button {
            session.tempTarget(command)
            dismiss()
        } label: {
            Label(title, systemImage: icon)
        }
    }
}

/// Two wheels side by side, so both values are in view before ✓: the target on the left,
/// the duration on the right. X (top left, from the system) cancels, ✓ (top right) sends.
/// The phone checks the target again (`HardLimits.LIMIT_TEMP_MIN_BG`) and shows its confirmation.
private struct CustomTempTargetView: View {

    let isMgdl: Bool
    let send: (Double, Bool, Int) -> Void

    /// 72 to 180 mg/dL, or 4.0 to 10.0 mmol/L: the range the phone accepts for a single value.
    /// Stored as whole mg/dL or tenths of mmol/L, so the wheel has no rounding problems.
    private var targets: [Int] { isMgdl ? Array(72...180) : Array(40...100) }
    @State private var target: Int
    @State private var minutes = 60

    init(isMgdl: Bool, send: @escaping (Double, Bool, Int) -> Void) {
        self.isMgdl = isMgdl
        self.send = send
        _target = State(initialValue: isMgdl ? 110 : 60)
    }

    var body: some View {
        HStack(spacing: 4) {
            VStack(spacing: 0) {
                Text(isMgdl ? "mg/dL" : "mmol/L").font(.caption2).foregroundStyle(.secondary)
                Picker("Target", selection: $target) {
                    ForEach(targets, id: \.self) { Text(targetText($0)).tag($0) }
                }
                .pickerStyle(.wheel)
                .labelsHidden()
            }
            VStack(spacing: 0) {
                Text("Duration").font(.caption2).foregroundStyle(.secondary)
                Picker("Duration", selection: $minutes) {
                    ForEach(customDurations, id: \.self) { Text(durationText($0)).tag($0) }
                }
                .pickerStyle(.wheel)
                .labelsHidden()
            }
        }
        .navigationTitle("Custom")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    send(isMgdl ? Double(target) : Double(target) / 10, isMgdl, minutes)
                } label: {
                    Image(systemName: "checkmark")
                }
                .accessibilityLabel("Send")
            }
        }
    }

    private func targetText(_ value: Int) -> String {
        isMgdl ? "\(value)" : (Double(value) / 10).formatted(.number.precision(.fractionLength(1)))
    }
}

// MARK: Running mode

private struct RunningModeView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    /// Set when the phone has not answered within 15 seconds.
    @State private var noAnswer = false

    var body: some View {
        List {
            if let list = session.runningModes {
                ForEach(Array(list.states.enumerated()), id: \.offset) { index, mode in
                    if let durations = mode.durations, !durations.isEmpty {
                        NavigationLink(runningModeText(mode)) {
                            DurationList(list: list, index: index, mode: mode, durations: durations, done: { dismiss() })
                        }
                    } else {
                        Button(runningModeText(mode)) {
                            session.selectRunningMode(list: list, index: index, duration: nil)
                            dismiss()
                        }
                    }
                }
            } else if noAnswer {
                Text("No answer from the iPhone.").foregroundStyle(.secondary)
                Button("Try again") { request() }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Running mode")
        // The phone sends only the modes that are allowed now, with the one-time id that must come back.
        .onAppear { request() }
    }

    private func request() {
        noAnswer = false
        session.requestRunningModes()
        Task {
            try? await Task.sleep(for: .seconds(15))
            if session.runningModes == nil { noAnswer = true }
        }
    }
}

private struct DurationList: View {

    @EnvironmentObject private var session: WatchSession
    let list: RunningModeList
    let index: Int
    let mode: AvailableRunningMode
    let durations: [Int]
    let done: () -> Void

    var body: some View {
        List(durations, id: \.self) { minutes in
            Button(durationText(minutes)) {
                session.selectRunningMode(list: list, index: index, duration: minutes)
                done()
            }
        }
        .navigationTitle(runningModeText(mode))
    }

    private func durationText(_ minutes: Int) -> String {
        minutes % 60 == 0 ? String(localized: "\(minutes / 60) h") : String(localized: "\(minutes) min")
    }
}

/// A Kotlin `AvailableRunningMode.RunningMode` name as a label.
func runningModeText(_ mode: AvailableRunningMode) -> String {
    if let title = mode.title { return title }
    switch mode.state {
    case "LOOP_OPEN": return String(localized: "Open loop")
    case "LOOP_LGS": return String(localized: "Low glucose suspend")
    case "LOOP_CLOSED": return String(localized: "Closed loop")
    case "LOOP_DISABLE": return String(localized: "Disable loop")
    case "LOOP_USER_SUSPEND": return String(localized: "Suspend loop")
    case "LOOP_RESUME": return String(localized: "Resume loop")
    case "PUMP_DISCONNECT": return String(localized: "Disconnect pump")
    case "PUMP_RECONNECT": return String(localized: "Reconnect pump")
    default: return mode.state
    }
}

// MARK: Bolus progress

struct BolusProgressView: View {

    @EnvironmentObject private var session: WatchSession
    let progress: BolusProgress

    var body: some View {
        VStack(spacing: 8) {
            ProgressView(value: Double(progress.percent), total: 100)
            Text(progress.status).font(.footnote).multilineTextAlignment(.center)
            if progress.percent < 100 {
                Button("Stop bolus", role: .destructive) { session.cancelBolus() }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black.opacity(0.9))
    }
}
