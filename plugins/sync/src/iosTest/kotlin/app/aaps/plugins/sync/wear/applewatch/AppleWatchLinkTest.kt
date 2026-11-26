package app.aaps.plugins.sync.wear.applewatch

import app.aaps.core.interfaces.rx.weardata.EventData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleWatchLinkTest {

    private val companion = FakeCompanion()
    private val toPhone = mutableListOf<EventData>()
    private var enabled = true
    private val link = AppleWatchLink(companion, isEnabled = { enabled }, toPhone = { toPhone.add(it) }, log = {})

    @AfterTest
    fun cleanUp() {
        AppleWatchRegistry.companion = null
    }

    @Test
    fun statusGoesOutAsStateUnderItsOwnKey() {
        val status = EventData.Status(
            dataset = 0, externalStatus = "", iobSum = "1.20", iobDetail = "", cob = "10g", currentBasal = "0.8U/h",
            battery = "", rigBattery = "", openApsStatus = 0L, bgi = "", batteryLevel = 1, patientName = "",
            tempTarget = "", tempTargetLevel = 0, reservoirString = "", reservoir = 0.0, reservoirLevel = 0,
            dkaRisk = "WARNING", dkaReason = "ketones"
        )

        link.toWatch(status)

        assertEquals(listOf("Status" to status.serialize()), companion.states)
        assertTrue(companion.live.isEmpty())
        assertTrue(companion.states.single().second.contains("WARNING"))
    }

    @Test
    fun aConfirmationGoesOutLive() {
        val confirm = EventData.ConfirmAction("Bolus", "1.00 U", returnCommand = null)

        link.toWatch(confirm)

        assertEquals(listOf(confirm.serialize()), companion.live)
        assertTrue(companion.states.isEmpty())
    }

    @Test
    fun customWatchfaceDataIsNotSent() {
        link.toWatch(EventData.ActionrequestCustomWatchface(exportFile = false))

        assertTrue(companion.states.isEmpty())
        assertTrue(companion.live.isEmpty())
    }

    @Test
    fun aCommandFromTheWatchGoesToThePhoneWithItsSender() {
        val json = EventData.ActionBolusPreCheck(insulin = 1.0, carbs = 0).serialize()

        link.fromWatch(json)

        val event = assertIs<EventData.ActionBolusPreCheck>(toPhone.single())
        assertEquals(1.0, event.insulin)
        assertEquals(AppleWatchLink.SOURCE_NODE_ID, event.sourceNodeId)
    }

    @Test
    fun nothingReachesThePhoneWhileThePluginIsOff() {
        enabled = false

        link.fromWatch(EventData.ActionBolusPreCheck(insulin = 1.0, carbs = 0).serialize())

        assertTrue(toPhone.isEmpty())
    }

    @Test
    fun unreadableTextIsDropped() {
        link.fromWatch("not json")

        assertTrue(toPhone.isEmpty())
    }

    @Test
    fun theInstalledCompanionIsTheOneThePluginWillUse() {
        installAppleWatchCompanion(companion)

        assertEquals(companion, AppleWatchRegistry.companion)
    }

    private class FakeCompanion : AppleWatchCompanion {

        val states = mutableListOf<Pair<String, String>>()
        val live = mutableListOf<String>()

        override fun start(callback: AppleWatchCallback) = Unit

        override fun sendState(key: String, json: String) {
            states.add(key to json)
        }

        override fun sendLive(json: String) {
            live.add(json)
        }

        override fun stop() = Unit
    }
}
