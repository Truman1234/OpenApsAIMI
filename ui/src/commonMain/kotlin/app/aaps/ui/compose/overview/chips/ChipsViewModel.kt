package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.PluginStatusBadge
import app.aaps.core.interfaces.overview.PluginStatusBadgeSource
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.ApsResultSource
import app.aaps.core.objects.extensions.displayedIobUnits
import app.aaps.core.objects.extensions.profileDisplayedIob
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import app.aaps.ui.compose.dashboard.aimi.activeTempTargetLine
import app.aaps.ui.compose.dashboard.aimi.aimiActiveModeLine
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Stable
@AssistedInject
class ChipsViewModel(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val sensitivityOverview: SensitivityOverview,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val dateUtil: DateUtil,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val profileUtil: ProfileUtil,
    /**
     * The one plugin that contributes a status chip, when a platform has one.
     *
     * Null on a target where no plugin binds it - iOS today, which has no AIMI plugin at all. The
     * chip is then not drawn, rather than drawn empty by a stub that answers "nothing to report":
     * a chip that is always idle looks like a working feature with nothing to say.
     */
    private val pluginStatusBadgeSource: PluginStatusBadgeSource? = null
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val iobCobTicker = flow {
        while (true) {
            emit(Unit)
            delay(150_000L) // 2.5 minutes
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    /**
     * When the status lines above the graph are worked out again: every minute (they count down), and at
     * once with a new note or temp target. The database is only observed while someone collects a line,
     * so a test that never reads them needs no stub.
     */
    private val statusLineRefresh = merge(
        flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        },
        flow { emitAll(persistenceLayer.observeChanges(TE::class)) }.map { },
        flow { emitAll(persistenceLayer.observeChanges(TT::class)) }.map { },
    )

    /**
     * The AIMI mode that runs now ("Mode: Lunch (45 min)"), or null: the same line as the AIMI and Glass
     * homes (`aimiActiveModeLine`). Null too when Appearance > Home screen lines switches it off; a change
     * of the switch shows when the home screen is opened again, or within a minute.
     */
    val activeModeText: StateFlow<String?> = statusLineRefresh
        .map { if (preferences.get(BooleanKey.OverviewShowAimiModeLine)) aimiActiveModeLine(persistenceLayer, rh, dateUtil.now()) else null }
        .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = null)

    /** The temp target that runs now ("Temp target: 110 mg/dL (45m)"), or null, with its own switch. */
    val tempTargetLine: StateFlow<String?> = statusLineRefresh
        .map {
            if (preferences.get(BooleanKey.OverviewShowTempTargetLine)) activeTempTargetLine(persistenceLayer, rh, profileUtil, dateUtil.now())
            else null
        }
        .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = null)

    private val iobRefresh =
        if (config.AAPSCLIENT) merge(iobCobTicker, rxBus.toFlow(EventNsClientStatusUpdated::class).map { })
        else iobCobTicker

    val iobUiState: StateFlow<IobUiState> = iobRefresh.combine(cache.iobGraphFlow) { _, _ ->
        val local = iobCobCalculator.profileDisplayedIob()
        val total = ApsResultSource.displayedIob(config, processedDeviceStatusData, dateUtil.now(), local)
        IobUiState(
            text = rh.gs(InterfacesStrings.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(CoreUiStrings.value_unavailable_short)
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText = rh.gs(CoreUiStrings.value_with_unit, cobText, rh.gs(CoreUiStrings.value_with_unit, constraintsProcessed.carbsReq.toString(), rh.gs(CoreUiStrings.required)))
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    // The IOB graph is published before the loop runs in the same calculation chain, so on its
    // own it would show the previous loop's ratio and variable ISF. Predictions are published
    // right after the loop ran on the master, and right after a device status came in on a
    // client, so they carry the fresh values on both sides.
    val sensitivityUiState: StateFlow<SensitivityUiState> = combine(iobCobTicker, cache.iobGraphFlow, cache.predictionsFlow) { _, _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
            isfStep = data.isfStep,
            dialogText = data.lines.joinToString("\n"),
            ratio = data.ratio,
            isEnabled = data.isEnabled,
            hasData = data.hasData
        )
    }

    /** Null while no plugin contributes a chip - see [pluginStatusBadgeSource]. */
    val pluginBadge: StateFlow<PluginStatusBadge?> =
        pluginStatusBadgeSource?.badge ?: MutableStateFlow(null)

    fun onPluginBadgeClick() {
        pluginStatusBadgeSource?.onBadgeClick()
    }

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()
            val local = displayedIobUnits(bolusIob, basalIob)
            val total = ApsResultSource.displayedIob(config, processedDeviceStatusData, dateUtil.now(), local)
            val message = listOf(
                CoreUiStrings.bolus_iob_label to bolusIob.iob,
                CoreUiStrings.treatments_wizard_basaliob_label to basalIob.basaliob,
                CoreUiStrings.iob to total
            ).joinToString("\n") { (label, insulin) ->
                rh.gs(InterfacesStrings.confirmation_line, rh.gs(label), rh.gs(InterfacesStrings.format_insulin_units, insulin))
            }
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}
