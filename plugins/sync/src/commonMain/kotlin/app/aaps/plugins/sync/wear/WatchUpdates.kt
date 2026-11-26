package app.aaps.plugins.sync.wear

import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.rx.events.EventWearUpdateTiles
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration.Companion.seconds

/**
 * When the phone sends new data to a watch. Shared by the Wear OS plugin (Android) and the Apple
 * Watch plugin (iOS), so both watches get the same updates at the same moments.
 *
 * Every message goes out as an [EventMobileToWear] on the bus. Each platform has its own transport
 * that listens for it: the Wear Data Layer on Android, WatchConnectivity on iOS.
 *
 * The plugin calls [start] from `onStart` with its own scope, and cancels that scope in `onStop`.
 */
@SingleIn(AppScope::class)
@Inject
class WatchUpdates(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val dataHandlerMobile: DataHandlerMobile,
    private val bolusProgressData: BolusProgressData,
    private val persistenceLayer: PersistenceLayer,
    private val scenes: SceneAutomationApi,
    private val eatReminder: EatReminder,
) {

    /**
     * Starts all updates on [scope].
     *
     * [isEnabled] is asked for each bolus progress frame, because the plugin can be switched off
     * while a bolus runs. [onPreferenceChange] runs after the resend that a changed watch setting
     * causes; Wear OS uses it for its custom watchface.
     */
    fun start(scope: CoroutineScope, isEnabled: () -> Boolean, onPreferenceChange: () -> Unit = {}) {
        // Last percent actually sent to the watch. Starts at 100 = "nothing to clear": the empty-status
        // clear frame on state-null is only needed when the watch was left mid-progress (< 100). If the
        // driver already reported 100% ("Bolus delivered successfully"), sending the clear frame would
        // overwrite that text with "100% - " for the notification's 5 s dismiss window.
        var lastSentPercent = 100
        bolusProgressData.state
            .drop(1) // Skip initial null emission on collection start
            .collectResilient(scope, aapsLogger, LTag.WEAR) { state ->
                if (isEnabled()) {
                    if (state != null) {
                        if (!state.isSMB || preferences.get(BooleanKey.WearNotifyOnSmb)) {
                            rxBus.send(EventMobileToWear(EventData.BolusProgress(percent = state.percent, status = rh.gs(state.wearStatus))))
                            lastSentPercent = state.percent
                        }
                    } else if (lastSentPercent < 100) {
                        // Bolus ended without a 100% frame (cancelled/failed) — send 100% to clear wear display
                        rxBus.send(EventMobileToWear(EventData.BolusProgress(percent = 100, status = "")))
                        lastSentPercent = 100
                    }
                }
            }
        merge(
            // Preferences sent to watch via resendData()
            preferences.observe(BooleanKey.WearControl).drop(1).map {},
            preferences.observe(IntKey.OverviewBolusPercentage).drop(1).map {},
            preferences.observe(IntKey.SafetyMaxCarbs).drop(1).map {},
            preferences.observe(DoubleKey.SafetyMaxBolus).drop(1).map {},
            preferences.observe(DoubleKey.OverviewInsulinButtonIncrement1).drop(1).map {},
            preferences.observe(DoubleKey.OverviewInsulinButtonIncrement2).drop(1).map {},
            preferences.observe(IntKey.OverviewCarbsButtonIncrement1).drop(1).map {},
            preferences.observe(IntKey.OverviewCarbsButtonIncrement2).drop(1).map {},
            // Custom watchface preferences
            preferences.observe(BooleanKey.WearCustomWatchfaceAuthorization).drop(1).map {},
            preferences.observe(StringNonKey.WearCwfWatchfaceName).drop(1).map {},
            preferences.observe(StringNonKey.WearCwfAuthorVersion).drop(1).map {},
            preferences.observe(StringNonKey.WearCwfFileName).drop(1).map {},
            // Which Watch Face Format face the watch installs; the watch swaps its slot on arrival
            preferences.observe(StringKey.WearPushedWatchface).drop(1).map {},
        ).collectResilient(scope, aapsLogger, LTag.WEAR) {
            dataHandlerMobile.resendData("PreferenceChange")
            onPreferenceChange()
        }
        resendRequests(scope)
            .collectResilient(scope, aapsLogger, LTag.WEAR) { reason -> dataHandlerMobile.resendData(reason) }
        // Refresh wear scene tile whenever the scene list changes (add / update / delete). The
        // active state goes too: an edited follow-up changes which button the tile offers.
        scenes.scenesFlow
            .drop(1) // Skip initial replay on subscribe
            .collectResilient(scope, aapsLogger, LTag.WEAR) {
                dataHandlerMobile.sendScenes()
                dataHandlerMobile.sendActiveSceneState(scenes.hasSceneToStop())
            }
        // Push active-scene flag to wear so the tile can swap between scene list and STOP button
        scenes.activeFlow
            .collectResilient(scope, aapsLogger, LTag.WEAR) { dataHandlerMobile.sendActiveSceneState(it) }
        rxBus.toFlow(EventWearUpdateTiles::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { dataHandlerMobile.sendUserActions() }
    }

    /**
     * The events after which the watch gets everything again, as one flow with one [RESEND_DEBOUNCE].
     *
     * Every resend builds the whole graph and all treatments again. After a BG the calculation, the loop
     * and NS each send their event within seconds, often twice, and each one used to start its own
     * resend: 9 full resends in 45 s were measured on a phone. Gives the reason of the last event of a
     * burst, for the log.
     *
     * The sources are subscribed here, undispatched, into a channel. `merge` would subscribe a moment
     * later in its own coroutines, and an event sent before that would be lost (see `collectResilient`).
     * The channel keeps the request until the debounce reads it.
     */
    @OptIn(FlowPreview::class)
    internal fun resendRequests(scope: CoroutineScope): Flow<String> {
        val requests = Channel<String>(Channel.CONFLATED)
        fun forward(source: Flow<*>, reason: String) {
            source.collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { requests.trySend(reason) }
        }
        forward(rxBus.toFlow(EventAutosensCalculationFinished::class), "EventAutosensCalculationFinished")
        forward(rxBus.toFlow(EventLoopUpdateGui::class), "EventLoopUpdateGui")
        // AAPSCLIENT: fresh predictions arrive via NS devicestatus, not a local loop run — without this the
        // watch graph trails the phone by one loop cycle (the BG-triggered autosens resend fires BEFORE the
        // master's new devicestatus lands). Event is only sent on AAPSCLIENT; processedDeviceStatusData is
        // updated synchronously before it fires, so the resend reads the new predictions.
        forward(rxBus.toFlow(EventNsClientStatusUpdated::class), "EventNsClientStatusUpdated")
        // Push status to watch quickly when a TT changes, without waiting for the loop's 10s debounce
        forward(persistenceLayer.observeChanges<TT>().drop(1), "TempTargetChange") // drop: the initial emission on collection start
        // The "time to eat" timer: the watch draws its end and rings at it, so a start, stop or +5 min goes out at once
        forward(eatReminder.state.drop(1), "EatReminderChange") // drop: the current state on collection start
        // Push status to watch quickly when the running mode changes on the phone, so the
        // running-mode complication and tile do not wait for the next loop run. A wear-side
        // change already refreshes through handleRunningModeConfirmed.
        forward(persistenceLayer.observeChanges<RM>().drop(1), "RunningModeChange")
        // An AIMI mode is a note: the watch shows the running one, so a new or ended note goes out at once
        forward(persistenceLayer.observeChanges<TE>().drop(1), "TherapyEventChange")
        return requests.receiveAsFlow().debounce(RESEND_DEBOUNCE)
    }

    internal companion object {

        /** The same 2 s the TT and running mode changes waited before they were merged in here. */
        val RESEND_DEBOUNCE = 2.seconds
    }
}
