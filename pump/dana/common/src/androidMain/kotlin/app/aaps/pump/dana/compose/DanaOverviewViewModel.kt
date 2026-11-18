package app.aaps.pump.dana.compose

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ScreenAction
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.R
import app.aaps.pump.dana.events.EventDanaRNewStatus
import app.aaps.pump.dana.keys.DanaStringNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.aaps.core.ui.R as CoreUiR

sealed class DanaOverviewEvent {
    data object StartPairWizard : DanaOverviewEvent()
    data object StartHistory : DanaOverviewEvent()
    data object StartUserSettings : DanaOverviewEvent()
    data object ConfirmUnpair : DanaOverviewEvent()
}

// Registers itself: @ViewModelKey infers the key from the class. No graph entry, and deliberately
// unscoped so each screen gets its own.
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
open class DanaOverviewViewModel(
    private val aapsLogger: AAPSLogger,
    protected val rh: ResourceHelper,
    rxBus: RxBus,
    private val commandQueue: CommandQueue,
    private val dateUtil: DateUtil,
    private val danaPump: DanaPump,
    private val activePlugin: ActivePlugin,
    private val ch: ConcentrationHelper,
    private val persistenceLayer: PersistenceLayer,
    protected val uel: UserEntryLogger,
    protected val preferences: Preferences,
    private val context: Context
) : ViewModel() {


    private val _events = MutableSharedFlow<DanaOverviewEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<DanaOverviewEvent> = _events

    private val communicationStatus = PumpCommunicationStatus(rxBus, commandQueue, rh, viewModelScope)

    // RxBus events converted to a flow trigger for recomposition
    protected val rxTrigger = MutableStateFlow(0L)

    init {
        // viewModelScope dies with the view model like the CompositeDisposable did. The bodies only
        // write a timestamp, so the dispatcher does not matter. UNDISPATCHED because RxBus has no
        // replay, so a scheduled collector could miss an event sent before it starts.
        rxBus.toFlow(EventDanaRNewStatus::class)
            .collectResilient(viewModelScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) { rxTrigger.value = System.currentTimeMillis() }
        rxBus.toFlow(EventInitializationChanged::class)
            .collectResilient(viewModelScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) { rxTrigger.value = System.currentTimeMillis() }

        // Observe EB/TB database changes for immediate UI updates
        persistenceLayer.observeChanges(EB::class)
            .onEach { rxTrigger.value = System.currentTimeMillis() }
            .launchIn(viewModelScope)
        persistenceLayer.observeChanges(TB::class)
            .onEach { rxTrigger.value = System.currentTimeMillis() }
            .launchIn(viewModelScope)
    }

    private data class PumpSnapshot(
        val lastConnectionTime: Long,
        val reservoir: Double,
        val battery: Int?,
        val lastBolusTime: Long?,
        val lastBolusAmount: Double?
    )

    private val pumpDataFlow = combine(
        danaPump.lastConnectionFlow,
        danaPump.reservoirRemainingUnitsFlow,
        danaPump.batteryRemainingFlow,
        danaPump.lastBolusTimeFlow,
        danaPump.lastBolusAmountFlow
    ) { lastConnection, reservoir, battery, lastBolusTime, lastBolusAmount ->
        PumpSnapshot(lastConnection, reservoir, battery, lastBolusTime, lastBolusAmount)
    }

    private val refreshFlow = combine(
        rxTrigger,
        communicationStatus.refreshTrigger,
        tickerFlow(60_000L)
    ) { _, _, _ -> }

    val uiState: StateFlow<PumpOverviewUiState> = combine(
        pumpDataFlow,
        refreshFlow
    ) { snapshot, _ ->
        buildUiState(snapshot.lastConnectionTime, snapshot.reservoir, snapshot.battery, snapshot.lastBolusTime, snapshot.lastBolusAmount)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildInitialState())

    override fun onCleared() {
        super.onCleared()
    }

    fun onRefreshClick() {
        aapsLogger.debug(LTag.PUMP, "Clicked connect to pump")
        danaPump.readAllAgain()
        viewModelScope.launch { commandQueue.readStatus(rh.gs(CoreUiR.string.clicked_connect_to_pump)) }
    }

    fun onHistoryClick() {
        _events.tryEmit(DanaOverviewEvent.StartHistory)
    }

    fun onUserSettingsClick() {
        _events.tryEmit(DanaOverviewEvent.StartUserSettings)
    }

    fun onPairClick() {
        _events.tryEmit(DanaOverviewEvent.StartPairWizard)
    }

    fun onUnpairClick() {
        _events.tryEmit(DanaOverviewEvent.ConfirmUnpair)
    }

    /**
     * Override in subclasses for variant-specific unpair logic (e.g., BLE bond removal for DanaRS).
     * Default: clears stored device name, resets pump state, logs user entry.
     */
    open fun performUnpair() {
        uel.log(Action.CLEAR_PAIRING_KEYS, Sources.Dana)
        preferences.remove(DanaStringNonKey.RName)
        danaPump.reset()
        rxTrigger.value = System.currentTimeMillis()
    }

    /**
     * Override in subclasses to surface a variant-specific persistent error banner on top of the
     * overview (e.g. DanaRS shows "not paired" when configured but the device is not bonded).
     * Only used when there is no live communication status to display. Default: no banner.
     */
    protected open fun errorBanner(isConfigured: Boolean, isInitialized: Boolean): StatusBanner? = null

    /**
     * Override in subclasses to add variant-specific management actions (e.g., BLE pair/unpair for DanaRS).
     */
    protected open fun buildManagementActions(pump: DanaPump, isInitialized: Boolean, isConfigured: Boolean): List<ScreenAction> = buildList {
        // User Settings (only for non-Korean, non-legacy-DanaR firmware)
        if (pump.hwModel != 1 && pump.protocol != 0x00) {
            add(
                ScreenAction(
                    label = rh.gs(R.string.danar_user_options),
                    icon = Icons.Filled.Settings,
                    visible = isInitialized,
                    onClick = { onUserSettingsClick() }
                )
            )
        }

        // Pair / Unpair
        if (isConfigured) {
            add(
                ScreenAction(
                    label = rh.gs(CoreUiR.string.pump_unpair),
                    icon = Icons.Filled.Bluetooth,
                    onClick = { onUnpairClick() }
                )
            )
        } else {
            add(
                ScreenAction(
                    label = rh.gs(CoreUiR.string.pairing),
                    icon = Icons.Filled.Bluetooth,
                    onClick = { onPairClick() }
                )
            )
        }
    }

    // Initial placeholder state, evaluated during construction. Must NOT invoke the open
    // errorBanner() hook: subclass fields it relies on aren't initialized yet (superclass
    // constructor runs first). The real banner appears once the uiState flow emits on subscribe.
    private fun buildInitialState(): PumpOverviewUiState = buildUiState(
        lastConnectionTime = danaPump.lastConnection,
        reservoir = danaPump.reservoirRemainingUnits,
        battery = danaPump.batteryRemaining,
        lastBolusTime = danaPump.lastBolusTime,
        lastBolusAmount = danaPump.lastBolusAmount,
        includeErrorBanner = false
    )

    // The row-building and action-building logic used to live here. It moved to the commonMain
    // buildDanaOverviewUiState() so the iOS overview screen can build the exact same rows without
    // copying this logic; this method only supplies the platform-specific inputs (the live
    // communication-status banner/queue text) and the two overridable hooks, unchanged in behavior.
    private fun buildUiState(
        lastConnectionTime: Long,
        reservoir: Double,
        battery: Int?,
        lastBolusTime: Long?,
        lastBolusAmount: Double?,
        includeErrorBanner: Boolean = true
    ): PumpOverviewUiState = buildDanaOverviewUiState(
        rh = rh,
        dateUtil = dateUtil,
        ch = ch,
        pump = danaPump,
        activePump = activePlugin.activePump,
        lastConnectionTime = lastConnectionTime,
        reservoir = reservoir,
        battery = battery,
        lastBolusTime = lastBolusTime,
        lastBolusAmount = lastBolusAmount,
        liveStatusBanner = communicationStatus.statusBanner(),
        queueStatus = communicationStatus.queueStatus(),
        includeErrorBanner = includeErrorBanner,
        errorBanner = ::errorBanner,
        buildManagementActions = ::buildManagementActions,
        onRefreshClick = ::onRefreshClick,
        onHistoryClick = ::onHistoryClick
    )

    fun extendedBolusToString(): String {
        val pump = danaPump
        if (!pump.isExtendedInProgress) return ""
        return ch.basalTbrString(
            rate = PumpRate(pump.extendedBolusAbsoluteRate),
            startTime = pump.extendedBolusStart,
            durationInMin = pump.extendedBolusDurationInMinutes,
            isExtended = true
        )
    }
}
