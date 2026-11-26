import Charts
import SwiftUI

/// Three pages, swiped left and right: glucose, details, actions. The Digital Crown stays free for
/// scrolling a page and for setting amounts. The layout follows the iAPS watch app (one glance page,
/// then the rest); the code is our own.
struct MainView: View {

    @EnvironmentObject private var session: WatchSession

    var body: some View {
        NavigationStack {
            TabView {
                GlucosePage()
                DetailsPage()
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
