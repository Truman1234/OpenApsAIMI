import Foundation
import WatchConnectivity
import os
import AapsShared

/// The Apple Watch on the iPhone: WatchConnectivity.
///
/// The Kotlin plugin (`AppleWatchPlugin`) decides what the watch gets and handles every command from
/// it. This class only moves text. Each payload is one `EventData` as JSON, the same JSON a Wear OS
/// watch gets.
///
/// Two ways out, chosen by Kotlin (`AppleWatchLink`):
/// - `sendState`: the newest value of one kind of data (glucose, status, graph ...). The newest JSON
///   of each kind is kept here, and all of them go to the watch together as the application context.
///   iOS delivers that when it can, and only the newest context counts.
/// - `sendLive`: a message for right now (a confirmation screen, an error). Sent only while the watch
///   app is reachable, otherwise dropped. Never queued: an old confirmation must not show up later.
///
/// The complications: the same widget state as the iPhone home widgets (`WidgetStateJson`), handed over
/// by `SwiftHomeWidgetCompanion` (`sendWidgetState`). It always goes into the application context, and
/// now and then also as a complication transfer, which wakes the watch and redraws the complication.
/// Apple allows only about 50 of those a day, so they are spread over the day like Loop does
/// (`ComplicationBudget`), with an extra one when the glucose changes range or moves a lot.
///
/// In: the watch sends one `EventData` as JSON in a message, under the key `event`.
///
/// The session is activated in `init`, not in `start`. iOS may start the app in the background to
/// deliver a message from the watch, and the session has to be ready by then. A message that arrives
/// before Kotlin has called `start` is kept for at most `pendingMaxAge` seconds and handed over on
/// `start`; an older one is dropped, so a command never runs long after the user sent it.
final class SwiftAppleWatchCompanion: NSObject, AppleWatchCompanion, WCSessionDelegate {

    static let log = Logger(subsystem: "app.aaps", category: "AppleWatch")

    /// The message key, on both sides.
    static let eventKey = "event"

    /// The key of the widget state, on both sides (`WatchSession.widgetStateKey`).
    static let widgetStateKey = "widgetState"

    private static let pendingMaxAge: TimeInterval = 30
    private static let pendingMaxCount = 10

    private let lock = NSLock()
    private var callback: AppleWatchCallback?
    private var state: [String: Any] = [:]
    private var pending: [(received: Date, json: String)] = []
    private var lastComplication: ComplicationBudget.Sent?

    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }

    override init() {
        super.init()
        guard let session else {
            Self.log.info("WatchConnectivity is not supported on this device")
            return
        }
        session.delegate = self
        session.activate()
    }

    // MARK: AppleWatchCompanion

    func start(watchCallback callback: AppleWatchCallback) {
        lock.lock()
        self.callback = callback
        let now = Date()
        let waiting = pending.filter { now.timeIntervalSince($0.received) <= Self.pendingMaxAge }.map(\.json)
        let dropped = pending.count - waiting.count
        pending.removeAll()
        lock.unlock()
        if dropped > 0 { Self.log.info("Dropped \(dropped) old watch messages") }
        waiting.forEach { callback.messageReceived(json: $0) }
        reportWatchState()
    }

    func sendState(key: String, json: String) {
        lock.lock()
        state[key] = json
        let context = state
        lock.unlock()
        guard let session, session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        do {
            try session.updateApplicationContext(context)
        } catch {
            Self.log.error("Application context not sent: \(error.localizedDescription, privacy: .public)")
        }
    }

    func sendLive(json: String) {
        guard let session, session.activationState == .activated, session.isReachable else { return }
        session.sendMessage([Self.eventKey: json], replyHandler: nil) { error in
            Self.log.info("Message not sent: \(error.localizedDescription, privacy: .public)")
        }
    }

    func stop() {
        lock.lock()
        callback = nil
        lock.unlock()
    }

    /// The widget state for the complications. Into the application context always; as a complication
    /// transfer only when `ComplicationBudget` says so.
    func sendWidgetState(json: String) {
        sendState(key: Self.widgetStateKey, json: json)
        guard let session, session.activationState == .activated, session.isPaired, session.isWatchAppInstalled,
              session.isComplicationEnabled,
              let reading = ComplicationBudget.Reading(json: json)
        else { return }
        let now = Date()
        lock.lock()
        let last = lastComplication
        let send = ComplicationBudget.shouldSend(
            reading, last: last, remaining: session.remainingComplicationUserInfoTransfers, now: now
        )
        if send { lastComplication = ComplicationBudget.Sent(reading: reading, at: now) }
        lock.unlock()
        guard send else { return }
        session.transferCurrentComplicationUserInfo([Self.widgetStateKey: json])
        Self.log.info("Complication update sent, \(session.remainingComplicationUserInfoTransfers) left today")
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if let error {
            Self.log.error("Activation failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        Self.log.info("Activated, state \(activationState.rawValue)")
        reportWatchState()
        // The context kept while the session was not active yet
        lock.lock()
        let context = state
        lock.unlock()
        if !context.isEmpty, activationState == .activated, session.isPaired, session.isWatchAppInstalled {
            try? session.updateApplicationContext(context)
        }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    /// The user switched to another watch. Activate again for the new one.
    func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    func sessionWatchStateDidChange(_ session: WCSession) {
        reportWatchState()
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        receive(message)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        receive(message)
        // The answer comes later as its own message, like on Wear OS. The reply only ends the call.
        replyHandler([:])
    }

    // MARK: Private

    private func receive(_ message: [String: Any]) {
        guard let json = message[Self.eventKey] as? String else {
            Self.log.error("Watch message without \(Self.eventKey, privacy: .public)")
            return
        }
        lock.lock()
        let current = callback
        if current == nil {
            pending.append((Date(), json))
            if pending.count > Self.pendingMaxCount { pending.removeFirst() }
        }
        lock.unlock()
        current?.messageReceived(json: json)
    }

    private func reportWatchState() {
        guard let session, session.activationState == .activated else { return }
        lock.lock()
        let current = callback
        lock.unlock()
        current?.watchStateChanged(paired: session.isPaired, appInstalled: session.isWatchAppInstalled)
    }
}

/// When the iPhone may wake the watch complication. Apple allows about 50 complication transfers a
/// day and a CGM sends up to 288 readings, so they are spread over the rest of the day (the Loop
/// method), with an extra one when it matters.
enum ComplicationBudget {

    /// A move of at least this much (1 mmol/L) is sent at once.
    static let bigMoveMgdl = 18.0
    /// With this few transfers left, only a change of range is sent, so the evening still gets some.
    static let reserve = 5

    /// What the complication needs to know about a widget state.
    struct Reading {
        let bgTime: Double
        let bgMgdl: Double?
        /// `LOW`, `IN_RANGE` or `HIGH`.
        let range: String

        /// Nil without a reading: there is nothing new to show.
        init?(json: String) {
            guard let data = json.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let bgTime = object["bgTime"] as? Double
            else { return nil }
            self.bgTime = bgTime
            bgMgdl = object["bgMgdl"] as? Double
            range = object["range"] as? String ?? ""
        }
    }

    struct Sent {
        let reading: Reading
        let at: Date
    }

    /// Only a new reading is sent. A change of range or a big move goes at once (while more than
    /// `reserve` are left); otherwise the transfers left are spread evenly until midnight.
    static func shouldSend(_ reading: Reading, last: Sent?, remaining: Int, now: Date, calendar: Calendar = .current) -> Bool {
        guard remaining > 0 else { return false }
        guard let last else { return true }
        guard reading.bgTime > last.reading.bgTime else { return false }
        let rangeChanged = reading.range != last.reading.range
        if rangeChanged { return true }
        if remaining <= reserve { return false }
        if let new = reading.bgMgdl, let old = last.reading.bgMgdl, abs(new - old) >= bigMoveMgdl { return true }
        guard let midnight = calendar.nextDate(after: now, matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime)
        else { return false }
        let interval = midnight.timeIntervalSince(now) / Double(remaining + 1)
        return now.timeIntervalSince(last.at) >= interval
    }
}
