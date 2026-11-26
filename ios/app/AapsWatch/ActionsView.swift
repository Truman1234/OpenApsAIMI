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
    @State private var insulin = 0.0

    private var maxBolus: Double { session.preferences?.maxBolus ?? 10 }

    var body: some View {
        VStack(spacing: 6) {
            Text(String(format: "%.2f U", insulin))
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .focusable()
                .digitalCrownRotation($insulin, from: 0, through: maxBolus, by: 0.05, sensitivity: .low)
            HStack {
                ForEach(increments, id: \.self) { step in
                    Button("+\(String(format: "%g", step))") { insulin = min(maxBolus, insulin + step) }
                }
            }
            Button("Check on iPhone") {
                session.bolus(insulin: rounded(insulin), carbs: 0)
                dismiss()
            }
            .tint(.blue)
            .disabled(insulin <= 0)
        }
        .navigationTitle("Bolus")
    }

    private var increments: [Double] {
        guard let p = session.preferences else { return [0.5, 1] }
        return [p.insulinButtonIncrement1, p.insulinButtonIncrement2].filter { $0 > 0 }
    }

    /// The crown moves in steps of 0.05, but a Double adds up small errors. The phone rounds to the pump step.
    private func rounded(_ value: Double) -> Double { (value * 100).rounded() / 100 }
}

// MARK: Wizard

private struct WizardView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    @State private var carbs = 0.0

    private var maxCarbs: Double { Double(session.preferences?.maxCarbs ?? 100) }

    var body: some View {
        VStack(spacing: 6) {
            Text("\(Int(carbs)) g")
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .focusable()
                .digitalCrownRotation($carbs, from: 0, through: maxCarbs, by: 1, sensitivity: .medium)
            HStack {
                ForEach(increments, id: \.self) { step in
                    Button("+\(step)") { carbs = min(maxCarbs, carbs + Double(step)) }
                }
            }
            Button("Calculate on iPhone") {
                session.wizard(carbs: Int(carbs), percentage: session.preferences?.bolusPercentage ?? 100)
                dismiss()
            }
            .tint(.blue)
        }
        .navigationTitle("Wizard")
    }

    private var increments: [Int] {
        guard let p = session.preferences else { return [10, 20] }
        return [p.carbsButtonIncrement1, p.carbsButtonIncrement2].filter { $0 > 0 }
    }
}

// MARK: AIMI modes

/// The AIMI modes: the meals start the P1/P2 prebolus, sport and sleep change how AIMI doses, stop ends
/// them. The phone uses each mode's own duration and shows a confirmation before it stores anything.
private struct AimiModeView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss

    /// One AIMI mode: the `ModePreset.id` of the phone, its label and icon.
    private struct Mode: Identifiable {
        let id: String
        let title: LocalizedStringKey
        let icon: String
    }

    private let modes = [
        Mode(id: "breakfast", title: "Breakfast", icon: "sunrise"),
        Mode(id: "lunch", title: "Lunch", icon: "sun.max"),
        Mode(id: "dinner", title: "Dinner", icon: "moon"),
        Mode(id: "snack", title: "Snack", icon: "carrot"),
        Mode(id: "highcarb", title: "High carb", icon: "birthday.cake"),
        Mode(id: "meal", title: "Meal", icon: "fork.knife"),
        Mode(id: "sport", title: "Sport", icon: "figure.run"),
        Mode(id: "sleep", title: "Sleep", icon: "bed.double"),
        Mode(id: "stop", title: "Stop modes", icon: "stop.circle"),
    ]

    var body: some View {
        List(modes) { mode in
            Button {
                session.aimiMode(mode.id)
                dismiss()
            } label: {
                Label(mode.title, systemImage: mode.icon)
            }
        }
        .navigationTitle("AIMI modes")
    }
}

// MARK: Temp target

private struct TempTargetView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            button("Activity", "PRESET_ACTIVITY", "figure.walk")
            button("Eating soon", "PRESET_EATING", "fork.knife")
            button("Hypo", "PRESET_HYPO", "exclamationmark.triangle")
            button("Cancel temp target", "CANCEL", "xmark.circle")
        }
        .navigationTitle("Temp target")
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
