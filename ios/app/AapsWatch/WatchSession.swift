import Foundation
import WatchConnectivity
import os

/// The watch side of WatchConnectivity, and everything the watch knows.
///
/// The iPhone (`SwiftAppleWatchCompanion` in `ios/app/Shared/AppleWatchBridge.swift`) sends:
/// - the application context: the newest JSON of each kind of data, one key per kind
///   (`SingleBg`, `Status`, `GraphData`, `Preferences`, ...);
/// - messages under the key `event`: confirmations, errors, bolus progress, the running mode list.
///
/// The watch sends commands as messages under the same key. Only while the iPhone is reachable: a
/// command that cannot be sent now is never queued, the user gets an error instead.
///
/// The watch decides nothing about therapy. Every check and every confirmation text comes from the phone.
@MainActor
final class WatchSession: NSObject, ObservableObject {

    nonisolated static let log = Logger(subsystem: "app.aaps.watch", category: "Session")
    nonisolated static let eventKey = "event"

    @Published private(set) var bg: SingleBg?
    @Published private(set) var graph: [SingleBg] = []
    @Published private(set) var status: Status?
    @Published private(set) var preferences: WatchPreferences?
    @Published private(set) var runningModes: RunningModeList?
    @Published private(set) var bolusProgress: BolusProgress?
    @Published var confirm: ConfirmRequest?
    @Published private(set) var reachable = false
    /// When the last command was sent and its answer has not come yet.
    @Published private(set) var waitingSince: Date?

    private let session: WCSession? = WCSession.isSupported() ? WCSession.default : nil

    override init() {
        super.init()
        session?.delegate = self
        session?.activate()
    }

    // MARK: Commands

    /// Asks the phone for everything again. Done when the app comes to the front.
    func requestData() {
        send(Command.json("ActionResendData", ["from": "AppleWatch"]), expectsAnswer: false, showError: false)
    }

    func bolus(insulin: Double, carbs: Int) {
        send(Command.json("ActionBolusPreCheck", ["insulin": insulin, "carbs": carbs]))
    }

    func wizard(carbs: Int, percentage: Int) {
        send(Command.json("ActionWizardPreCheck", ["carbs": carbs, "percentage": percentage]))
    }

    /// `command` is a Kotlin `TempTargetCommand` name: PRESET_ACTIVITY, PRESET_EATING, PRESET_HYPO or CANCEL.
    func tempTarget(_ command: String) {
        send(Command.json("ActionTempTargetPreCheck", ["command": command]))
    }

    /// `modeId` is a `ModePreset.id` of the phone, for example "lunch" or "stop".
    func aimiMode(_ modeId: String) {
        send(Command.json("ActionAimiModePreCheck", ["modeId": modeId]))
    }

    func requestRunningModes() {
        runningModes = nil
        send(Command.json("RunningModeRequest", ["timeStamp": Command.nowMs]))
    }

    func selectRunningMode(list: RunningModeList, index: Int, duration: Int?) {
        var fields: [String: Any] = ["timeStamp": list.timeStamp, "index": index]
        if let duration { fields["duration"] = duration }
        send(Command.json("RunningModeSelected", fields))
    }

    func cancelBolus() {
        send(Command.json("CancelBolus", ["timeStamp": Command.nowMs]), expectsAnswer: false)
    }

    /// The user confirmed: the phone gets back exactly the request it prepared.
    func confirmed(_ request: ConfirmRequest) {
        confirm = nil
        guard !request.isInfoOnly, let raw = request.returnCommand else { return }
        // A master runs the command at once and answers only with an error. A client waits for the master.
        send(Command.json(raw: raw), expectsAnswer: request.deferConfirm)
    }

    /// [expectsAnswer]: the phone answers with a screen (a confirmation or an error), so a spinner shows until then.
    private func send(_ json: String?, expectsAnswer: Bool = true, showError: Bool = true) {
        guard let json else { return }
        guard let session, session.activationState == .activated, session.isReachable else {
            if showError { showLocalError(String(localized: "iPhone not reachable. Nothing was sent.")) }
            return
        }
        if expectsAnswer { waitingSince = Date() }
        session.sendMessage([Self.eventKey: json], replyHandler: nil) { error in
            Task { @MainActor in
                self.waitingSince = nil
                if showError { self.showLocalError(String(localized: "Not sent: \(error.localizedDescription)")) }
            }
        }
    }

    private func showLocalError(_ text: String) {
        // The action sheet that sent the command is closing right now, and SwiftUI does not show a
        // second sheet during that. Wait until it is gone.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(600))
            confirm = ConfirmRequest(json: ["title": String(localized: "Error"), "message": text])
        }
    }

    // MARK: Incoming

    fileprivate func apply(context: [String: Any]) {
        for value in context.values {
            if let json = value as? String { handle(json) }
        }
    }

    fileprivate func handle(_ json: String) {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String else {
            Self.log.error("Message could not be read")
            return
        }
        let decoder = JSONDecoder()
        switch EventType.shortName(type) {
        case "SingleBg":
            if let value = try? decoder.decode(SingleBg.self, from: data) { bg = value }
        case "GraphData":
            if let value = try? decoder.decode(GraphData.self, from: data) { graph = value.entries.sorted { $0.timeStamp < $1.timeStamp } }
        case "Status":
            if let value = try? decoder.decode(Status.self, from: data) { status = value }
        case "Preferences":
            if let value = try? decoder.decode(WatchPreferences.self, from: data) { preferences = value }
        case "RunningModeList":
            waitingSince = nil
            if let value = try? decoder.decode(RunningModeList.self, from: data) { runningModes = value }
        case "BolusProgress":
            if let value = try? decoder.decode(BolusProgress.self, from: data) {
                bolusProgress = value.percent >= 100 && value.status.isEmpty ? nil : value
                if value.percent >= 100 { clearProgressLater() }
            }
        case "ConfirmAction", "OpenLoopRequest":
            waitingSince = nil
            confirm = ConfirmRequest(json: object)
        case "CancelNotification":
            confirm = nil
        case "RemoteDelivered":
            waitingSince = nil
        case "ContactingMaster":
            waitingSince = Date()
        default:
            break // Data the Apple Watch does not use (tiles, scenes, watch faces of Wear OS)
        }
    }

    private func clearProgressLater() {
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(5))
            if (bolusProgress?.percent ?? 0) >= 100 { bolusProgress = nil }
        }
    }

    fileprivate func setReachable(_ value: Bool) {
        reachable = value
    }
}

extension WatchSession: WCSessionDelegate {

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if let error {
            Self.log.error("Activation failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        let context = session.receivedApplicationContext
        let reachable = session.isReachable
        Task { @MainActor in
            self.apply(context: context)
            self.setReachable(reachable)
            if reachable { self.requestData() }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        Task { @MainActor in self.apply(context: applicationContext) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let json = message[Self.eventKey] as? String else { return }
        Task { @MainActor in self.handle(json) }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in
            self.setReachable(reachable)
            if reachable { self.requestData() }
        }
    }
}
