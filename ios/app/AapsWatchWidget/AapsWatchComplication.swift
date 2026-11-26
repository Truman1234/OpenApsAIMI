import SwiftUI
import WidgetKit

/// The complications on the watch face: glucose, arrow, delta, age and IOB/COB.
///
/// The watch app (`WatchSession`) writes the widget state it gets from the iPhone to the App Group file
/// (`AapsWidgetStore`) and asks WidgetKit to reload. This extension only reads that file, with the same
/// timeline as the iPhone widgets (`AapsWidgetProvider` in `AapsWidgetShared.swift`).
///
/// A tap on a complication opens the AAPS watch app. watchOS does that by itself for the app the
/// complication belongs to.
///
/// Safety, the same rules as the iPhone widgets: the file may be old, because the iPhone may send new
/// values only a few times an hour (Apple allows only about 50 complication updates a day). So the age is
/// judged here, at the time of each timeline entry, with `ContentState.isStale(at:)`, and the timeline
/// has a second entry at `staleAt`, so the value turns stale on time without a reload. A stale value is
/// struck out, grey, has a warning sign and no arrow and no delta. The age always counts by itself.
/// No file, an unreadable file or an unknown version shows "---", never old numbers.
///
/// Every text comes from Kotlin, already translated. Nothing here is joined into a sentence.
@main
struct AapsWatchComplication: Widget {

    /// The `kind` the watch app reloads after it wrote a new state.
    static let kind = "AapsWatchComplication"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: AapsWidgetProvider()) { entry in
            AapsComplicationView(entry: entry)
                .containerBackground(for: .widget) { Color.black }
        }
        // Read by the watch face editor before any data exists, so English only.
        .configurationDisplayName("AAPS")
        .description("Glucose, arrow and age")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

private struct AapsComplicationView: View {

    let entry: AapsWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let state = entry.state {
            content(state, stale: entry.isStale)
        } else {
            noData
        }
    }

    @ViewBuilder
    private func content(_ state: AapsActivityAttributes.ContentState, stale: Bool) -> some View {
        let color = (stale ? AapsActivityAttributes.BgColorClass.stale : state.colorClass(at: entry.date)).color
        switch family {
        case .accessoryInline:
            Label {
                Text(state.bgText).strikethrough(stale)
            } icon: {
                Image(systemName: stale ? "exclamationmark.triangle" : (TrendArrowView.symbol(state.trend)?.0 ?? "drop"))
            }
            .accessibilityLabel(Text(state.bgText))
            .accessibilityValue(Text(stale ? state.staleText : (state.trendText ?? "")))
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    GlucoseText(state: state, stale: stale, color: color, font: .title3)
                    if stale {
                        Image(systemName: "exclamationmark.triangle").font(.caption2)
                    } else {
                        TrendArrowView(trend: state.trend).font(.caption2).foregroundStyle(color)
                    }
                    AgeText(state: state).font(.system(size: 10))
                }
            }
        case .accessoryCorner:
            GlucoseText(state: state, stale: stale, color: color, font: .title2)
                .widgetLabel {
                    AgeText(state: state)
                }
        default:
            // .accessoryRectangular
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    GlucoseText(state: state, stale: stale, color: color, font: .title2)
                    if !stale {
                        TrendArrowView(trend: state.trend).font(.headline).foregroundStyle(color)
                        Text(state.deltaText).font(.caption)
                    }
                }
                HStack(spacing: 3) {
                    if stale {
                        Image(systemName: "exclamationmark.triangle")
                            .accessibilityLabel(Text(state.staleText))
                    }
                    AgeText(state: state)
                }
                .font(.caption)
                .foregroundStyle(stale ? Color.gray : Color.secondary)
                HStack(spacing: 6) {
                    ValueText(label: state.iobLabel, value: state.iobText, stale: stale)
                    ValueText(label: state.cobLabel, value: state.cobText, stale: stale)
                }
                .font(.caption2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var noData: some View {
        switch family {
        case .accessoryInline:
            Label("---", systemImage: "exclamationmark.triangle")
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "exclamationmark.triangle")
            }
        default:
            Text("---").font(.title3).foregroundStyle(.gray)
        }
    }
}

/// The reading. Struck out and grey when stale.
private struct GlucoseText: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool
    let color: Color
    let font: Font

    var body: some View {
        Text(state.bgText)
            .font(font)
            .fontWeight(.bold)
            .strikethrough(stale)
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .accessibilityLabel(Text(state.bgText))
            .accessibilityValue(Text(stale ? state.staleText : (state.trendText ?? "")))
    }
}

/// How old the reading is. It counts by itself, so it is right even when nothing reloads.
private struct AgeText: View {

    let state: AapsActivityAttributes.ContentState

    var body: some View {
        if let date = state.bgDate {
            Text(date, style: .relative)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
    }
}

/// A label and its value. When stale the value is struck out and grey, like the reading.
private struct ValueText: View {

    let label: String
    let value: String
    let stale: Bool

    var body: some View {
        HStack(spacing: 3) {
            Text(label).foregroundStyle(.secondary)
            Text(value)
                .strikethrough(stale)
                .foregroundStyle(stale ? Color.gray : Color.primary)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
    }
}
