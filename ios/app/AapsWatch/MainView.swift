import Charts
import SwiftUI

/// Four pages, swiped left and right: glucose, details, device ages, actions. The Digital Crown stays free for
/// scrolling a page and for setting amounts. The layout follows the iAPS watch app (one glance page,
/// then the rest); the code is our own.
struct MainView: View {

    @EnvironmentObject private var session: WatchSession

    var body: some View {
        NavigationStack {
            TabView {
                GlucosePage()
                DetailsPage()
                DevicesPage()
                ActionsPage()
            }
            .tabViewStyle(.page)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if session.isWaiting { ProgressView() }
                    else if !session.reachable { Image(systemName: "iphone.slash").foregroundStyle(.secondary) }
                }
            }
        }
        .sheet(item: $session.confirm) { request in
            ConfirmView(request: request)
        }
        .overlay {
            if let progress = session.bolusProgress { BolusProgressView(progress: progress) }
        }
        .overlay {
            if session.showDone { DoneView() }
        }
    }
}

/// Shown for 2 seconds when the phone has stored a confirmed command.
private struct DoneView: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.green)
            Text("Done").font(.headline)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black.opacity(0.85))
        .transition(.opacity)
    }
}

extension WatchSession {
    /// A command was sent less than 30 seconds ago and the phone has not answered yet.
    var isWaiting: Bool {
        guard let since = waitingSince else { return false }
        return Date().timeIntervalSince(since) < 30
    }
}

// MARK: Glucose

private struct GlucosePage: View {

    @EnvironmentObject private var session: WatchSession

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(spacing: 4) {
                if let bg = session.bg {
                    let stale = context.date.timeIntervalSince(bg.date) > 10 * 60
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(bg.sgvString ?? "---")
                            .font(.system(size: 52, weight: .bold, design: .rounded))
                            .strikethrough(stale)
                            .foregroundStyle(stale ? .gray : bgColor(bg))
                        Text(bg.slopeArrow ?? "")
                            .font(.title2)
                            .foregroundStyle(stale ? .gray : bgColor(bg))
                    }
                    HStack {
                        Text(bg.delta ?? "")
                        Spacer()
                        Text(minutesAgo(bg.date, now: context.date))
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                } else {
                    Text("---").font(.system(size: 52, weight: .bold, design: .rounded))
                    Text("Waiting for the iPhone").font(.footnote).foregroundStyle(.secondary)
                }
                if let status = session.status {
                    HStack {
                        Label(status.iobSum, systemImage: "drop.fill")
                        Spacer()
                        Label(status.cob, systemImage: "fork.knife")
                    }
                    .font(.footnote)
                    HStack {
                        Text(modeText(status.loopMode))
                        Spacer()
                        if let loop = status.lastLoop { Text(minutesAgo(loop, now: context.date)) }
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    if let aimi = status.aimiModeText(now: context.date) {
                        Label(aimi, systemImage: "figure.walk.motion")
                            .font(.caption2)
                            .foregroundStyle(.orange)
                    }
                    if let tt = status.tempTargetText(now: context.date, receivedAt: session.statusReceivedAt) {
                        Label(tt, systemImage: "target")
                            .font(.caption2)
                            .foregroundStyle(.yellow)
                    }
                    if status.dkaRaised { DkaBanner(status: status) }
                }
            }
            .padding(.horizontal, 4)
        }
    }

    private func bgColor(_ bg: SingleBg) -> Color {
        switch bg.sgvLevel ?? 0 {
        case 1: return .yellow
        case -1: return .red
        default: return .green
        }
    }
}

/// The AIMI DKA guard's level. The phone decides what is allowed; this only shows the risk.
private struct DkaBanner: View {

    let status: Status

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("DKA: \(status.dkaRisk ?? "")").font(.footnote.bold())
            if let reason = status.dkaReason { Text(reason).font(.caption2).lineLimit(2) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(4)
        .background(color.opacity(0.35), in: RoundedRectangle(cornerRadius: 6))
    }

    private var color: Color {
        switch status.dkaRisk {
        case "CRITICAL": return .red
        case "WARNING": return .orange
        default: return .yellow
        }
    }
}

// MARK: Details

/// The age of the sensor, the cannula, the reservoir and the pump battery, counted on the watch from the
/// time of the last change. Orange and red use the user's own limits from the phone.
private struct DevicesPage: View {

    @EnvironmentObject private var session: WatchSession

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let status = session.status
            Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                GridRow {
                    DeviceTile(title: "Sensor", icon: "sensor", age: status?.sensorAge, extra: nil, now: context.date)
                    DeviceTile(title: "Cannula", icon: "syringe", age: status?.cannulaAge, extra: nil, now: context.date)
                }
                GridRow {
                    DeviceTile(title: "Reservoir", icon: "cross.vial", age: status?.reservoirAge,
                               extra: status?.reservoirString, now: context.date)
                    DeviceTile(title: "Battery", icon: "battery.75", age: status?.batteryAge,
                               extra: status?.battery, now: context.date)
                }
            }
        }
    }
}

private struct DeviceTile: View {

    let title: LocalizedStringKey
    let icon: String
    let age: DeviceAge?
    /// A second value under the age, like the reservoir level. Empty or nil shows nothing.
    let extra: String?
    let now: Date

    var body: some View {
        VStack(spacing: 2) {
            Label(title, systemImage: icon)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(age?.text(now: now) ?? "–")
                .font(.title3.bold())
                .foregroundStyle(color)
            if let extra, !extra.isEmpty {
                Text(extra).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(4)
        .background(.gray.opacity(0.2), in: RoundedRectangle(cornerRadius: 10))
    }

    private var color: Color {
        switch age?.level(now: now) {
        case .critical: return .red
        case .warning: return .orange
        default: return .white
        }
    }
}

private struct DetailsPage: View {

    @EnvironmentObject private var session: WatchSession

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                if !session.graph.isEmpty { GlucoseChart(entries: session.graph) }
                if let status = session.status {
                    row("Basal", status.currentBasal)
                    if !status.tempTarget.isEmpty { row("Temp target", status.tempTarget) }
                    row("IOB", status.iobDetail.isEmpty ? status.iobSum : status.iobDetail)
                    row("COB", status.cob)
                    row("BGI", status.bgi)
                    row("Reservoir", status.reservoirString)
                    row("Battery", status.battery)
                } else {
                    Text("No status yet").foregroundStyle(.secondary)
                }
            }
            .font(.footnote)
        }
    }

    private func row(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
    }
}

/// The last 3 hours of glucose, with the low and high lines of the phone.
private struct GlucoseChart: View {

    let entries: [SingleBg]

    var body: some View {
        let from = Date().addingTimeInterval(-3 * 3600)
        let shown = entries.filter { $0.date >= from }
        Chart {
            ForEach(shown, id: \.timeStamp) { bg in
                PointMark(x: .value("Time", bg.date), y: .value("BG", bg.sgv))
                    .symbolSize(8)
                    .foregroundStyle(bg.sgv > bg.high ? .yellow : bg.sgv < bg.low ? .red : .green)
            }
            if let last = shown.last {
                RuleMark(y: .value("High", last.high)).foregroundStyle(.yellow.opacity(0.4))
                RuleMark(y: .value("Low", last.low)).foregroundStyle(.red.opacity(0.4))
            }
        }
        .chartXAxis(.hidden)
        .frame(height: 70)
    }
}

// MARK: Helpers

func minutesAgo(_ date: Date, now: Date) -> String {
    let minutes = max(0, Int(now.timeIntervalSince(date) / 60))
    return String(localized: "\(minutes) min ago")
}

/// The running mode of `Status.loopMode` (Kotlin `LoopStatusData.LoopMode`).
func modeText(_ mode: String?) -> String {
    switch mode {
    case "CLOSED": return String(localized: "Closed loop")
    case "OPEN": return String(localized: "Open loop")
    case "LGS": return String(localized: "Low glucose suspend")
    case "DISABLED": return String(localized: "Loop disabled")
    case "SUSPENDED": return String(localized: "Loop suspended")
    case "PUMP_SUSPENDED": return String(localized: "Pump suspended")
    case "DST_SUSPENDED": return String(localized: "Suspended (DST)")
    case "DISCONNECTED": return String(localized: "Pump disconnected")
    case "SUPERBOLUS": return String(localized: "Superbolus")
    default: return ""
    }
}
