import SwiftUI

/// A screen the phone wrote: the confirmation of a request, a result, or an error.
///
/// The lines are shown as the phone sent them, already translated and formatted, in the colour of
/// their role (Kotlin `ConfirmationRole`). "Confirm" sends the phone's own return command back; the
/// phone runs exactly what it showed here, and only once.
struct ConfirmView: View {

    @EnvironmentObject private var session: WatchSession
    @Environment(\.dismiss) private var dismiss
    let request: ConfirmRequest

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text(request.title).font(.headline)
                if !request.message.isEmpty { Text(request.message).font(.footnote) }
                ForEach(request.lines, id: \.self) { line in
                    Text(line.text)
                        .font(.footnote)
                        .foregroundStyle(color(line.role))
                }
                if request.isInfoOnly {
                    Button("OK") { dismiss() }
                } else {
                    Button("Confirm") {
                        session.confirmed(request)
                        dismiss()
                    }
                    .tint(.green)
                    Button("Cancel", role: .cancel) { dismiss() }
                }
            }
        }
    }

    private func color(_ role: String) -> Color {
        switch role {
        case "WARNING": return .orange
        case "BOLUS": return .blue
        case "CARBS", "COB": return .yellow
        case "TEMP_TARGET": return .green
        case "LOOP_SUSPENDED", "LOOP_DISABLED", "LOOP_DISCONNECTED": return .red
        case "INFO": return .gray
        default: return .primary
        }
    }
}
