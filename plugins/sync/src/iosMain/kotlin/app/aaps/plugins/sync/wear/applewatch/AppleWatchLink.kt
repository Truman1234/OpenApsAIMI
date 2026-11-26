package app.aaps.plugins.sync.wear.applewatch

import app.aaps.core.interfaces.rx.weardata.EventData

/** How a message from the phone reaches the Apple Watch. */
internal enum class AppleWatchDelivery {

    /** The newest value is kept and the watch gets it when it can. See [AppleWatchCompanion.sendState]. */
    STATE,

    /** Only while the watch app is reachable. See [AppleWatchCompanion.sendLive]. */
    LIVE,

    /** Not for the Apple Watch: the custom watchface data of Wear OS. */
    SKIP,
}

/**
 * Moves `EventData` between the phone and the Swift side. The rules live here, so they can be tested
 * without WatchConnectivity.
 *
 * Out: [toWatch] picks [AppleWatchDelivery] for each message.
 *
 * In: [fromWatch] decodes the JSON and gives it to [toPhone], which puts it on the bus.
 * `DataHandlerMobile` handles it there exactly as a message from a Wear OS watch: the same checks, the
 * same "Wear control" switch, the same confirmation step before any bolus.
 */
internal class AppleWatchLink(
    private val companion: AppleWatchCompanion,
    private val isEnabled: () -> Boolean,
    private val toPhone: (EventData) -> Unit,
    private val log: (String) -> Unit,
) {

    fun toWatch(event: EventData) {
        when (deliveryOf(event)) {
            AppleWatchDelivery.STATE -> companion.sendState(stateKey(event), event.serialize())
            AppleWatchDelivery.LIVE  -> companion.sendLive(event.serialize())
            AppleWatchDelivery.SKIP  -> Unit
        }
    }

    fun fromWatch(json: String) {
        if (!isEnabled()) {
            log("Apple Watch message ignored, the plugin is off")
            return
        }
        val event = EventData.deserialize(json)
        // deserialize gives an Error for text it cannot read. The watch never sends an Error itself.
        if (event is EventData.Error) {
            log("Apple Watch message could not be read")
            return
        }
        event.sourceNodeId = SOURCE_NODE_ID
        log("Apple Watch message ${event::class.simpleName}")
        toPhone(event)
    }

    companion object {

        /** Shown as the sender in the log lines of `DataHandlerMobile`. WatchConnectivity has no node id. */
        const val SOURCE_NODE_ID = "apple-watch"

        fun deliveryOf(event: EventData): AppleWatchDelivery = when (event) {
            is EventData.SingleBg,
            is EventData.GraphData,
            is EventData.TreatmentData,
            is EventData.Status,
            is EventData.Preferences,
            is EventData.QuickWizard,
            is EventData.UserAction,
            is EventData.SceneList,
            is EventData.ActiveSceneState                 -> AppleWatchDelivery.STATE

            is EventData.ActionSetCustomWatchface,
            is EventData.ActionUpdateCustomWatchface,
            is EventData.ActionrequestCustomWatchface,
            is EventData.ActionrequestSetDefaultWatchface -> AppleWatchDelivery.SKIP

            else                                          -> AppleWatchDelivery.LIVE
        }

        /** One key per kind of state, so a new value replaces the old one of the same kind. */
        fun stateKey(event: EventData): String = event::class.simpleName ?: "EventData"
    }
}
