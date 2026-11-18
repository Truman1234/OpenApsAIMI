package app.aaps.pump.dana.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ScreenAction
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.DanaStrings
import androidx.compose.ui.text.AnnotatedString

/**
 * The pure part of the Dana overview: pump data in, [PumpOverviewUiState] out, nothing about where
 * the data came from (no `ViewModel`, no Flow, no RxBus). Moved out of Android's
 * `DanaOverviewViewModel.buildUiState()` so the iOS overview screen (`DanaRsOverviewContent`) can
 * build the exact same 11 info rows without copying this logic - Android keeps calling this through
 * its Flow pipeline, unchanged in what it shows.
 *
 * [errorBanner] and [buildManagementActions] are the two hooks `DanaOverviewViewModel` lets
 * subclasses override (DanaRS adds BLE pair/unpair, a "not paired" banner); callers pass them as
 * plain lambdas instead, so this function needs no inheritance of its own.
 *
 * [onHistoryClick] is nullable: pass `null` to leave the "Pump history" action out entirely, for a
 * platform (iOS, for now) where that screen does not exist yet, rather than showing a button that
 * does nothing.
 */
fun buildDanaOverviewUiState(
    rh: TextResolver,
    dateUtil: DateUtil,
    ch: ConcentrationHelper,
    pump: DanaPump,
    activePump: Pump,
    lastConnectionTime: Long,
    reservoir: Double,
    battery: Int?,
    lastBolusTime: Long?,
    lastBolusAmount: Double?,
    liveStatusBanner: StatusBanner?,
    queueStatus: AnnotatedString?,
    includeErrorBanner: Boolean = true,
    errorBanner: (isConfigured: Boolean, isInitialized: Boolean) -> StatusBanner? = { _, _ -> null },
    buildManagementActions: (pump: DanaPump, isInitialized: Boolean, isConfigured: Boolean) -> List<ScreenAction>,
    onRefreshClick: () -> Unit,
    onHistoryClick: (() -> Unit)?
): PumpOverviewUiState {
    // Last connection
    val lastConnection = if (lastConnectionTime != 0L) {
        val agoMinutes = ((dateUtil.now() - lastConnectionTime) / 60_000).toInt()
        rh.gs(InterfacesStrings.value_with_detail, dateUtil.timeString(lastConnectionTime), rh.gs(InterfacesStrings.minago, agoMinutes))
    } else ""

    // Last bolus
    val lastBolus = if (lastBolusTime != null && lastBolusAmount != null) {
        ch.insulinAmountAgoString(PumpInsulin(lastBolusAmount), lastBolusTime)
    } else null

    // Base basal rate
    // "(1) 0.85 U/h": the number of the basal profile in the pump, then the rate
    val baseBasalRate = rh.gs(
        CoreUiStrings.value_with_unit,
        rh.gs(CoreUiStrings.value_in_parentheses, (pump.activeProfile + 1).toString()),
        ch.basalRateString(activePump.baseBasalRate, true)
    )

    // Temp basal. DanaPump's own method, not ConcentrationHelper - same as Android: a plain,
    // non-localized string ("50% @14:03 5/30'"), unlike every other row here.
    val tempBasalText = pump.temporaryBasalToString()

    // Extended bolus
    val extendedBolusText = if (pump.isExtendedInProgress) {
        ch.basalTbrString(
            rate = PumpRate(pump.extendedBolusAbsoluteRate),
            startTime = pump.extendedBolusStart,
            durationInMin = pump.extendedBolusDurationInMinutes,
            isExtended = true
        )
    } else ""

    // Battery
    val batteryText = battery?.let { rh.gs(CoreUiStrings.units_format_percent, it) }

    // Reservoir
    val reservoirText = if (reservoir > 0.0) ch.insulinAmountString(PumpInsulin(reservoir)) else null

    // Last connection warn level
    val lastConnectionLevel = if (lastConnectionTime != 0L) {
        val agoMinutes = ((dateUtil.now() - lastConnectionTime) / 60_000).toInt()
        when {
            agoMinutes >= 31 -> StatusLevel.CRITICAL
            agoMinutes >= 16 -> StatusLevel.WARNING
            else             -> StatusLevel.NORMAL
        }
    } else StatusLevel.UNSPECIFIED

    // Battery warn level
    val batteryLevel = when {
        battery == null -> StatusLevel.UNSPECIFIED
        battery <= 26    -> StatusLevel.CRITICAL
        battery <= 51    -> StatusLevel.WARNING
        else             -> StatusLevel.NORMAL
    }

    // Reservoir warn level
    val reservoirLevel = when {
        ch.fromPump(PumpInsulin(reservoir)) <= 20.0 -> StatusLevel.CRITICAL
        ch.fromPump(PumpInsulin(reservoir)) <= 50.0 -> StatusLevel.WARNING
        else                                         -> StatusLevel.NORMAL
    }

    val isConfigured = activePump.isConfigured()
    val isInitialized = activePump.isInitialized()

    // Status banner: prefer live communication status; fall back to a persistent error banner
    // (e.g. not paired) supplied by the caller when the pump is idle.
    val statusBanner = liveStatusBanner
        ?: if (includeErrorBanner) errorBanner(isConfigured, isInitialized) else null

    // Info rows
    val infoRows = if (!isConfigured) emptyList() else buildList {
        // 1. Serial number
        pump.serialNumber.takeIf { it.isNotEmpty() }?.let {
            add(PumpInfoRow(label = rh.gs(CoreUiStrings.serial_number), value = it))
        }

        // 2. Battery
        batteryText?.let {
            add(PumpInfoRow(label = rh.gs(CoreUiStrings.battery_label), value = it, level = batteryLevel))
        }

        // 3. Last connection
        if (lastConnection.isNotEmpty()) {
            add(PumpInfoRow(label = rh.gs(CoreUiStrings.last_connection_label), value = lastConnection, level = lastConnectionLevel))
        }

        // 4. Last bolus
        lastBolus?.let {
            add(PumpInfoRow(label = rh.gs(CoreUiStrings.last_bolus_label), value = it))
        }

        // 5. Daily units
        add(
            PumpInfoRow(
                label = rh.gs(CoreUiStrings.daily_units),
                value = ch.insulinAmountString(PumpInsulin(pump.dailyTotalUnits)),
                level = when {
                    pump.dailyTotalUnits > pump.maxDailyTotalUnits * 0.9  -> StatusLevel.CRITICAL
                    pump.dailyTotalUnits > pump.maxDailyTotalUnits * 0.75 -> StatusLevel.WARNING
                    else                                                  -> StatusLevel.NORMAL
                }
            )
        )
        add(
            PumpInfoRow(
                label = rh.gs(CoreUiStrings.max_daily_units),
                value = ch.insulinAmountString(PumpInsulin(pump.maxDailyTotalUnits.toDouble()))
            )
        )

        // 6. Base basal rate
        add(PumpInfoRow(label = rh.gs(CoreUiStrings.base_basal_rate_label), value = baseBasalRate))

        // 7. Temp basal (hidden when empty)
        add(PumpInfoRow(label = rh.gs(CoreUiStrings.tempbasal_label), value = tempBasalText, visible = tempBasalText.isNotEmpty()))

        // 8. Extended bolus (hidden when empty)
        add(PumpInfoRow(label = rh.gs(CoreUiStrings.extended_bolus_label), value = extendedBolusText, visible = extendedBolusText.isNotEmpty()))

        // 9. Reservoir
        reservoirText?.let {
            add(PumpInfoRow(label = rh.gs(CoreUiStrings.reservoir_label), value = it, level = reservoirLevel))
        }

        // 10. Basal/bolus step
        add(PumpInfoRow(label = rh.gs(DanaStrings.basal_bolus_step), value = rh.gs(InterfacesStrings.values_with_slash, pump.basalStep.toString(), pump.bolusStep.toString())))

        // Easy mode, only for pump variants that have it
        if (pump.hasEasyMode)
            add(PumpInfoRow(label = rh.gs(DanaStrings.dana_easy_mode), value = rh.gs(if (pump.isEasyModeEnabled) DanaStrings.option_on else DanaStrings.option_off)))

        // 11. Firmware
        if (pump.hwModel != 0) {
            add(
                PumpInfoRow(
                    label = rh.gs(CoreUiStrings.firmware),
                    value = rh.gs(DanaStrings.dana_model, pump.modelFriendlyName(), pump.hwModel, pump.protocol, pump.productCode)
                )
            )
        }
    }

    // Actions
    val primaryActions = buildList {
        add(
            // As soon as a pump is paired: after a first status read that failed, it is the way to try again.
            ScreenAction(
                label = rh.gs(CoreUiStrings.refresh),
                icon = Icons.Filled.Refresh,
                visible = isConfigured,
                onClick = onRefreshClick
            )
        )
        if (onHistoryClick != null) {
            add(
                ScreenAction(
                    label = rh.gs(CoreUiStrings.pump_history),
                    icon = Icons.AutoMirrored.Filled.List,
                    visible = isInitialized,
                    onClick = onHistoryClick
                )
            )
        }
    }

    val managementActions = buildManagementActions(pump, isInitialized, isConfigured)

    return PumpOverviewUiState(
        statusBanner = statusBanner,
        queueStatus = queueStatus,
        infoRows = infoRows,
        primaryActions = primaryActions,
        managementActions = managementActions
    )
}
