import Foundation

/// The messages of the AAPS phone, as the watch reads them.
///
/// The phone sends one Kotlin `EventData` as JSON. The JSON field `type` is the full Kotlin class name,
/// for example `app.aaps.core.interfaces.rx.weardata.EventData.SingleBg`. Kotlin leaves out a field that
/// has its default value, so every field with a Kotlin default is optional here.
///
/// Only the fields the watch shows are read. Unknown fields are ignored.
enum EventType {

    static let prefix = "app.aaps.core.interfaces.rx.weardata.EventData."

    /// "SingleBg" for "app.aaps.core.interfaces.rx.weardata.EventData.SingleBg".
    static func shortName(_ type: String) -> String {
        type.hasPrefix(prefix) ? String(type.dropFirst(prefix.count)) : type
    }
}

struct SingleBg: Decodable {
    let timeStamp: Int64
    let sgvString: String?
    let glucoseUnits: String?
    let slopeArrow: String?
    let delta: String?
    let sgvLevel: Int64?
    let sgv: Double
    let high: Double
    let low: Double

    var date: Date { Date(timeIntervalSince1970: Double(timeStamp) / 1000) }
}

struct GraphData: Decodable {
    let entries: [SingleBg]
}

struct Status: Decodable {
    let iobSum: String
    let iobDetail: String
    let cob: String
    let currentBasal: String
    let battery: String
    let openApsStatus: Int64
    let bgi: String
    let tempTarget: String
    let tempTargetLevel: Int
    let tempTargetDuration: Int64?
    let reservoirString: String
    let loopMode: String?
    let modeEndTime: Int64?
    let dkaRisk: String?
    let dkaReason: String?
    /// The running AIMI mode, already translated on the phone, or nil when none runs.
    let aimiMode: String?
    /// When the running AIMI mode ends, in ms since 1970.
    let aimiModeEndsAt: Int64?

    /// The time of the last loop run, or nil when there was none.
    var lastLoop: Date? { openApsStatus > 0 ? Date(timeIntervalSince1970: Double(openApsStatus) / 1000) : nil }

    /// True when the DKA guard sees a risk. "NONE" or no value means no risk.
    var dkaRaised: Bool { (dkaRisk ?? "NONE") != "NONE" }

    /// The running AIMI mode with the minutes left, or nil when none runs at [now].
    func aimiModeText(now: Date) -> String? {
        guard let aimiMode, let aimiModeEndsAt else { return nil }
        let left = Int((Double(aimiModeEndsAt) / 1000 - now.timeIntervalSince1970) / 60)
        return left > 0 ? String(localized: "\(aimiMode) · \(left) min") : nil
    }
}

struct WatchPreferences: Decodable {
    let wearControl: Bool
    let unitsMgdl: Bool
    let bolusPercentage: Int
    let maxCarbs: Int
    let maxBolus: Double
    let insulinButtonIncrement1: Double
    let insulinButtonIncrement2: Double
    let carbsButtonIncrement1: Int
    let carbsButtonIncrement2: Int
}

struct ConfirmLine: Decodable, Hashable {
    let role: String
    let text: String
}

/// A screen the phone asks the watch to show: a confirmation, a result or an error.
///
/// [returnCommand] is kept as the raw JSON object. The watch sends it back unchanged when the user
/// confirms, so the phone gets exactly the request it prepared (with its one-time id).
struct ConfirmRequest: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let lines: [ConfirmLine]
    let returnCommand: [String: Any]?
    /// True when the phone is an AAPS client: the result comes later from the master.
    let deferConfirm: Bool

    /// Only an OK button: an error, or a message with nothing to confirm.
    var isInfoOnly: Bool {
        guard let type = returnCommand?["type"] as? String else { return true }
        return EventType.shortName(type) == "Error"
    }

    /// Reads a `ConfirmAction` or an `OpenLoopRequest`.
    init?(json: [String: Any]) {
        guard let title = json["title"] as? String else { return nil }
        self.title = title
        message = json["message"] as? String ?? ""
        returnCommand = json["returnCommand"] as? [String: Any]
        deferConfirm = json["deferConfirm"] as? Bool ?? false
        lines = (json["lines"] as? [[String: Any]] ?? []).compactMap { line in
            guard let role = line["role"] as? String, let text = line["text"] as? String else { return nil }
            return ConfirmLine(role: role, text: text)
        }
    }
}

struct RunningModeList: Decodable {
    let timeStamp: Int64
    let states: [AvailableRunningMode]
}

struct AvailableRunningMode: Decodable, Hashable {
    let state: String
    let durations: [Int]?
    let title: String?
}

struct BolusProgress: Decodable {
    let percent: Int
    let status: String
}

/// Builds the JSON of a command for the phone.
///
/// The field `type` is written first. kotlinx.serialization finds the class from it, and the first
/// position is the one it reads fastest and most surely.
enum Command {

    static func json(_ name: String, _ fields: [String: Any] = [:]) -> String? {
        json(type: EventType.prefix + name, fields: fields)
    }

    /// A command the phone sent earlier (the return command of a confirmation), sent back as it was.
    static func json(raw: [String: Any]) -> String? {
        guard let type = raw["type"] as? String else { return nil }
        var fields = raw
        fields.removeValue(forKey: "type")
        return json(type: type, fields: fields)
    }

    private static func json(type: String, fields: [String: Any]) -> String? {
        guard let typeData = try? JSONSerialization.data(withJSONObject: [type], options: .fragmentsAllowed),
              let typeArray = String(data: typeData, encoding: .utf8) else { return nil }
        // "[\"app....\"]" -> "\"app....\""
        let quotedType = String(typeArray.dropFirst().dropLast())
        if fields.isEmpty { return "{\"type\":\(quotedType)}" }
        guard let body = try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys]),
              let text = String(data: body, encoding: .utf8) else { return nil }
        // "{...}" -> "{\"type\":...,...}"
        return "{\"type\":\(quotedType)," + text.dropFirst()
    }

    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
