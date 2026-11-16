package app.aaps.pump.danars.ios

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.pump.ble.PairingState
import app.aaps.core.interfaces.pump.ble.PairingStep
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ScreenAction
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.DanaStrings
import app.aaps.pump.dana.compose.buildDanaOverviewUiState
import app.aaps.pump.dana.events.EventDanaRNewStatus
import app.aaps.pump.dana.keys.DanaStringNonKey
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The Dana RS / Dana-i pump screen on iPhone. Built from [app.aaps.pump.dana.compose.buildDanaOverviewUiState],
 * the same commonMain function Android's `DanaOverviewViewModel` delegates to, so the same 11 info
 * rows and the same pair/unpair management action show up here - only "User Settings" and "Pump
 * history" are left out, since neither screen exists on iOS yet (approved: 2026-11, full parity
 * extraction, omit rather than disable the two missing actions).
 *
 * A plain class, not a `ViewModel`, like Medtrum's `MedtrumIosOverviewContent`.
 * [DanaPump]'s own `lastConnectionFlow`/`reservoirRemainingUnitsFlow`/`batteryRemainingFlow`/
 * `lastBolusTimeFlow`/`lastBolusAmountFlow` are collected directly; everything else
 * [buildDanaOverviewUiState] reads (daily units, extended bolus, serial, firmware, ...) are plain
 * fields with no `Flow` of their own, so they are only re-read when something signals that the
 * pump status may have changed - the same signals Android's ViewModel combines: the two RxBus
 * events, EB/TB database changes, the communication status's own refresh trigger, and a 60s ticker
 * as a fallback.
 */
internal class DanaRsOverviewContent(
    private val host: IosDanaRsDriverHost,
    private val danaRsHost: IosDanaRsHost,
    private val danaPump: DanaPump,
    private val rh: TextResolver,
    private val commandQueue: CommandQueue,
    private val rxBus: RxBus,
    private val persistenceLayer: PersistenceLayer,
    private val ch: ConcentrationHelper,
    private val uel: UserEntryLogger,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    /** The same pairing wizard the settings entry opens. */
    private val pairWizard: @Composable (onClose: () -> Unit) -> Unit
) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        var showWizard by remember { mutableStateOf(false) }
        if (showWizard) {
            pairWizard { showWizard = false }
            return
        }

        val scope = rememberCoroutineScope()
        val communicationStatus = remember(scope) { PumpCommunicationStatus(rxBus, commandQueue, rh, scope) }

        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            launch { rxBus.toFlow(EventDanaRNewStatus::class).collect { tick = Clock.System.now().toEpochMilliseconds() } }
            launch { rxBus.toFlow(EventInitializationChanged::class).collect { tick = Clock.System.now().toEpochMilliseconds() } }
            launch { persistenceLayer.observeChanges(EB::class).collect { tick = Clock.System.now().toEpochMilliseconds() } }
            launch { persistenceLayer.observeChanges(TB::class).collect { tick = Clock.System.now().toEpochMilliseconds() } }
            launch { communicationStatus.refreshTrigger.collect { tick = it } }
            launch { tickerFlow(60_000L).collect { tick = Clock.System.now().toEpochMilliseconds() } }
        }

        val lastConnectionTime by danaPump.lastConnectionFlow.collectAsState()
        val reservoir by danaPump.reservoirRemainingUnitsFlow.collectAsState()
        val battery by danaPump.batteryRemainingFlow.collectAsState()
        val lastBolusTime by danaPump.lastBolusTimeFlow.collectAsState()
        val lastBolusAmount by danaPump.lastBolusAmountFlow.collectAsState()

        val state = remember(tick, lastConnectionTime, reservoir, battery, lastBolusTime, lastBolusAmount) {
            buildDanaOverviewUiState(
                rh = rh,
                dateUtil = dateUtil,
                ch = ch,
                pump = danaPump,
                activePump = host.driver,
                lastConnectionTime = lastConnectionTime,
                reservoir = reservoir,
                battery = battery,
                lastBolusTime = lastBolusTime,
                lastBolusAmount = lastBolusAmount,
                liveStatusBanner = communicationStatus.statusBanner(),
                queueStatus = communicationStatus.queueStatus(),
                errorBanner = ::errorBanner,
                buildManagementActions = { _, _, isConfigured ->
                    buildList {
                        if (isConfigured) {
                            add(
                                ScreenAction(
                                    label = rh.gs(CoreUiStrings.pump_unpair),
                                    icon = Icons.Filled.Bluetooth,
                                    onClick = {
                                        performUnpair()
                                        tick = Clock.System.now().toEpochMilliseconds()
                                    }
                                )
                            )
                        } else {
                            add(
                                ScreenAction(
                                    label = rh.gs(CoreUiStrings.pairing),
                                    icon = Icons.Filled.Bluetooth,
                                    onClick = { showWizard = true }
                                )
                            )
                        }
                    }
                },
                onRefreshClick = {
                    danaPump.reset()
                    scope.launch { commandQueue.readStatus(rh.gs(CoreUiStrings.clicked_connect_to_pump)) }
                },
                onHistoryClick = null
            )
        }

        PumpOverviewScreen(state = state)
    }

    /**
     * Same check as Android's `DanaRSOverviewViewModel.errorBanner()`: configured but the stored
     * address is not bonded means the person still needs to pair the device.
     */
    private fun errorBanner(isConfigured: Boolean, isInitialized: Boolean): StatusBanner? {
        if (!isConfigured) return null
        val address = preferences.get(DanaStringNonKey.MacAddress)
        if (address.isBlank() || host.session.value.bleTransport.adapter.isDeviceBonded(address)) return null
        return StatusBanner(rh.gs(DanaStrings.pairfirst), StatusLevel.CRITICAL)
    }

    /** Same steps as Android's `DanaRSOverviewViewModel.performUnpair()`. */
    private fun performUnpair() {
        uel.log(Action.CLEAR_PAIRING_KEYS, Sources.Dana)
        val address = preferences.get(DanaStringNonKey.MacAddress)
        danaRsHost.clearPairing()
        preferences.remove(DanaStringNonKey.MacAddress)
        preferences.remove(DanaStringNonKey.RsName)
        preferences.remove(DanaStringNonKey.EmulatorDeviceName)
        val bleTransport = host.session.value.bleTransport
        if (address.isNotBlank()) bleTransport.adapter.removeBond(address)
        bleTransport.updatePairingState(PairingState(step = PairingStep.IDLE))
        danaRsHost.changePump()
    }
}
