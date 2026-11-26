import SwiftUI

/// The AAPS app on the Apple Watch.
///
/// It shows what the phone sends and passes commands back to the phone. Every check, every
/// confirmation text and the decision itself stay on the phone (`DataHandlerMobile`, the same code
/// the Wear OS watch uses). Plan: `docs/kmp-migration/apple-watch-app.md`.
@main
struct AapsWatchApp: App {

    @StateObject private var session = WatchSession()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            MainView()
                .environmentObject(session)
        }
        .onChange(of: scenePhase) { _, phase in
            // Fresh data each time the app comes to the front. The context may be minutes old.
            if phase == .active { session.requestData() }
        }
    }
}
