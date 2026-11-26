import SwiftUI
import WidgetKit

/// The widget parts used by more than one widget: the timeline (entry and provider) and the trend
/// arrow and colours. Target membership: both Live Activity extensions (the Live Activity, the home and
/// lock screen widgets) and AapsWatchWidget (the watch complications). One copy, so the iPhone widgets
/// and the watch complications read the file and judge the age the same way.
///
/// The stale rule itself is `AapsActivityAttributes.ContentState.isStale(at:)`.

// MARK: - Data

/// What only the home widgets show: the chips of the Android widget. Decoded from the same file as
/// `AapsActivityAttributes.ContentState`; the keys are listed in the KDoc of `WidgetStateJson`.
/// Every field is optional, so a file without the details still shows the glucose.
struct AapsWidgetDetails: Decodable {
    let targetText: String?
    let targetChip: String?
    let ttReason: String?
    let targetIcon: String?
    let profileText: String?
    let profileModified: Bool?
    let loopIcon: String?
    let sensIcon: String?
    let sensText: String?
    let isfFromText: String?
    let isfToText: String?
    let isfStep: String?
    let isfStepText: String?
    let tbr: String?
    let tbrText: String?
    let iobActive: Bool?
    let cobActive: Bool?
}

struct AapsWidgetEntry: TimelineEntry {
    let date: Date
    /// Nil when there is no usable file.
    let state: AapsActivityAttributes.ContentState?
    let details: AapsWidgetDetails?

    /// True when the value must not look fresh at the time of this entry.
    var isStale: Bool { state?.isStale(at: date) ?? true }
}

struct AapsWidgetProvider: TimelineProvider {

    /// How soon WidgetKit is asked to read the file again by itself. iOS decides the real time. A read
    /// picks up a file the app wrote while its reload was refused.
    private static let rereadAfter: TimeInterval = 15 * 60

    func placeholder(in context: Context) -> AapsWidgetEntry {
        AapsWidgetEntry(date: Date(), state: nil, details: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (AapsWidgetEntry) -> Void) {
        completion(Self.load(at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<AapsWidgetEntry>) -> Void) {
        let now = Date()
        let first = Self.load(at: now)
        var entries = [first]
        // A second entry at the moment the reading turns old, so the widget greys it out on time
        // without a reload.
        if let state = first.state, !first.isStale, let staleAt = state.staleAt {
            let staleDate = Date(timeIntervalSince1970: staleAt / 1000)
            if staleDate > now {
                entries.append(AapsWidgetEntry(date: staleDate, state: state, details: first.details))
            }
        }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(Self.rereadAfter))))
    }

    static func load(at date: Date) -> AapsWidgetEntry {
        guard let data = AapsWidgetStore.readData() else {
            return AapsWidgetEntry(date: date, state: nil, details: nil)
        }
        guard let state = try? JSONDecoder().decode(AapsActivityAttributes.ContentState.self, from: data),
              state.v == AapsWidgetStore.supportedVersion
        else {
            AapsWidgetStore.log.error("Widget state could not be read")
            return AapsWidgetEntry(date: date, state: nil, details: nil)
        }
        let details = try? JSONDecoder().decode(AapsWidgetDetails.self, from: data)
        return AapsWidgetEntry(date: date, state: state, details: details)
    }
}

// MARK: - Style

/// The trend arrow. Kotlin `TrendArrow` names, drawn as one to three SF Symbols.
struct TrendArrowView: View {

    let trend: String?

    var body: some View {
        if let (symbol, count) = Self.symbol(trend) {
            HStack(spacing: -4) {
                ForEach(0..<count, id: \.self) { _ in
                    Image(systemName: symbol)
                }
            }
        }
    }

    /// Kotlin `TrendArrow` names. Unknown or `NONE` shows no arrow.
    static func symbol(_ trend: String?) -> (String, Int)? {
        switch trend {
        case "TRIPLE_UP": return ("arrow.up", 3)
        case "DOUBLE_UP": return ("arrow.up", 2)
        case "SINGLE_UP": return ("arrow.up", 1)
        case "FORTY_FIVE_UP": return ("arrow.up.right", 1)
        case "FLAT": return ("arrow.right", 1)
        case "FORTY_FIVE_DOWN": return ("arrow.down.right", 1)
        case "SINGLE_DOWN": return ("arrow.down", 1)
        case "DOUBLE_DOWN": return ("arrow.down", 2)
        case "TRIPLE_DOWN": return ("arrow.down", 3)
        default: return nil
        }
    }
}

/// The colour of each class. The meaning follows the Android widget colours.
extension AapsActivityAttributes.BgColorClass {

    var color: Color {
        switch self {
        case .low: return .red
        case .inRange: return .green
        case .high: return .yellow
        case .stale: return .gray
        }
    }
}
