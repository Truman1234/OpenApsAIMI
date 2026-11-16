package app.aaps.plugins.source.xdrip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSUserDefaults

/**
 * Reads the glucose readings and the heartbeat transmitter identity that xDrip4iOS itself writes
 * to the shared `NSUserDefaults` App Group suite.
 *
 * Mirrors iAPS's `AppGroupSource.swift` (readings) and `HeartBeatManager.swift` (transmitter
 * identity): both apps are configured with the same App Group, xDrip4iOS writes into it, and
 * nothing here writes back. [suiteName] is this app's own App Group ID, read from the Info.plist
 * key `"AppGroupID"` - see `XdripSourceIosPlugin`.
 */
internal class XdripAppGroupStore(suiteName: String) {

    private val defaults = NSUserDefaults(suiteName = suiteName)

    /** The three keys that identify the transmitter xDrip4iOS is reading from, for the heartbeat. */
    data class HeartbeatIdentity(
        val deviceAddress: String,
        val serviceUuid: String,
        val receiveCharacteristicUuid: String
    )

    fun heartbeatIdentity(): HeartbeatIdentity? {
        val address = defaults.stringForKey(KEY_TRANSMITTER_ADDRESS) ?: return null
        val service = defaults.stringForKey(KEY_TRANSMITTER_SERVICE) ?: return null
        val receive = defaults.stringForKey(KEY_TRANSMITTER_RECEIVE) ?: return null
        return HeartbeatIdentity(address, service, receive)
    }

    /** One parsed reading. [fromApp] is xDrip4iOS's own "from" field ("xDrip" or "GlucoseDirect"). */
    data class Reading(val timestampMs: Long, val mgdl: Double, val trend: String?, val fromApp: String?)

    /**
     * New readings since the last call - deduplicated the same way `AppGroupSource.swift` does: by
     * comparing the whole raw JSON string against what was last seen, not by timestamp, because
     * xDrip4iOS rewrites the entire array on every update rather than appending to it.
     */
    fun readNewReadings(): List<Reading> {
        val raw = defaults.stringForKey(KEY_LATEST_READINGS) ?: return emptyList()
        if (raw == lastSeenRaw) return emptyList()
        lastSeenRaw = raw
        return parseReadings(raw)
    }

    private var lastSeenRaw: String? = null

    companion object {

        private const val KEY_LATEST_READINGS = "latestReadings"
        private const val KEY_TRANSMITTER_ADDRESS = "cgmTransmitterDeviceAddress"
        private const val KEY_TRANSMITTER_SERVICE = "cgmTransmitter_CBUUID_Service"
        private const val KEY_TRANSMITTER_RECEIVE = "cgmTransmitter_CBUUID_Receive"

        /** `/Date(1700000000000)/`, the .NET-style date xDrip4iOS writes for `DT`. */
        private val msDatePattern = Regex("""/Date\((\d+)\)/""")

        internal fun parseReadings(raw: String): List<Reading> {
            val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val dt = obj["DT"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val timestampMs = msDatePattern.find(dt)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                val mgdl = obj["Value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val from = obj["from"]?.jsonPrimitive?.contentOrNull
                val trend = (obj["Trend"] ?: obj["direction"])?.jsonPrimitive?.contentOrNull
                Reading(timestampMs = timestampMs, mgdl = mgdl, trend = trend, fromApp = from)
            }
        }
    }
}
