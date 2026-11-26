import SwiftUI

/// The AAPS app on the Apple Watch.
///
/// It shows what the phone sends and passes commands back to the phone. Every check, every
/// confirmation text and the decision itself stay on the phone (`DataHandlerMobile`, the same code
/// the Wear OS watch uses). Plan: `docs/kmp-migration/apple-watch-app.md`.
@main
struct AapsWatchApp: App {

    init() {
        // Activate the session at once, also for a launch in the background.
        _ = WatchSession.shared
    }

    @StateObject private var session = WatchSession.shared
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
        // watchOS starts the app in the background to hand over a complication update from the iPhone.
        // The session is already active (`WatchSession.shared`); keep the app alive until the data is in.
        .backgroundTask(.watchConnectivity) {
            await WatchSession.shared.waitForPendingContent()
        }
    }
}
