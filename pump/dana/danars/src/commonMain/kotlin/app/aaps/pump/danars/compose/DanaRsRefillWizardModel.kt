package app.aaps.pump.danars.compose

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.CoreUiStrings
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.danars.comm.DanaRSPacketOptionSetUserOption
import app.aaps.pump.danars.session.DanaRsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DanaRsRefillWizardStep {
    RESERVOIR_AMOUNT,
    PRIME_TUBE,
    PRIME_CANNULA,
    COMPLETE,
    ERROR
}

/** Sub-state within [DanaRsRefillWizardStep.PRIME_TUBE]/[DanaRsRefillWizardStep.PRIME_CANNULA]. */
enum class DanaRsPrimeState { READY, PRIMING, DONE }

data class DanaRsRefillWizardUiState(
    val step: DanaRsRefillWizardStep = DanaRsRefillWizardStep.RESERVOIR_AMOUNT,
    val reservoirAmount: String = "",
    val primeState: DanaRsPrimeState = DanaRsPrimeState.READY,
    val tubeAmount: String = "",
    val cannulaAmount: String = "",
    val errorMessage: String? = null,
    /** Where to go back to from [DanaRsRefillWizardStep.ERROR] on retry. */
    val failedStep: DanaRsRefillWizardStep = DanaRsRefillWizardStep.RESERVOIR_AMOUNT
)

sealed class DanaRsRefillWizardEvent {
    data object Finish : DanaRsRefillWizardEvent()
}

/**
 * The Dana RS / Dana-i reservoir-refill-and-priming wizard, shared by Android and iOS (iOS wires
 * it in first; see `IosDanaRsPlugin`). Android has no guided refill/prime flow today - the person
 * fills and primes on the pump itself - so this is new behavior on both platforms, not a port of
 * existing code.
 *
 * Three steps, same order DanaKit (the iOS/Loop driver this protocol was cross-checked against)
 * uses:
 * 1. [submitReservoirAmount]: writes the pump's own `refillAmount` field through the existing
 *    `DanaRSPacketOptionSetUserOption` packet. That packet re-sends every user-option field in one
 *    shot, so this mutates [DanaPump.refillAmount] (already populated with the pump's current
 *    settings after `getPumpStatus()`) rather than building a packet from scratch - every other
 *    field goes out unchanged. No insulin moves, so this step needs no dosing check on any platform.
 * 2./3. [startPrimeTube]/[startPrimeCannula]: a bolus through [CommandQueue.bolus], the same
 *    generic path Omnipod/ComboV2 use for their own priming, with [DetailedBolusInfo.bolusType] set
 *    to [BS.Type.PRIMING] so it is excluded from IOB/therapy (`CommandQueueImplementation`/
 *    `PumpSyncImplementation` already branch on that type - nothing Dana-specific needed there).
 *    On iOS this goes nowhere until the maintainer lifts `IosDanaRsPump`'s dosing guard: its
 *    `deliverTreatment` fails closed the same way a real bolus would, and the wizard just shows
 *    that failure's [app.aaps.core.interfaces.pump.PumpEnactResult.comment].
 *
 * A plain class, not a `ViewModel`, same reasoning as [DanaRsPairWizardModel].
 */
class DanaRsRefillWizardModel(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
    private val danaPump: DanaPump,
    private val commandQueue: CommandQueue,
    private val danaRSPacketOptionSetUserOption: () -> DanaRSPacketOptionSetUserOption,
    private val session: DanaRsSession,
    private val scope: CoroutineScope
) {

    private val _uiState = MutableStateFlow(DanaRsRefillWizardUiState())
    val uiState: StateFlow<DanaRsRefillWizardUiState> = _uiState

    private val _events = MutableSharedFlow<DanaRsRefillWizardEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<DanaRsRefillWizardEvent> = _events

    fun updateReservoirAmount(value: String) {
        _uiState.update { it.copy(reservoirAmount = value.filter { c -> c.isDigit() }.take(4)) }
    }

    fun submitReservoirAmount() {
        val amount = _uiState.value.reservoirAmount.toIntOrNull() ?: return
        scope.launch {
            danaPump.refillAmount = amount
            val packet = danaRSPacketOptionSetUserOption()
            session.sendMessage(packet)
            if (!packet.isReceived || packet.failed) {
                aapsLogger.error(LTag.PUMP, "RefillPrimeWizard: setting the reservoir amount failed")
                fail(DanaRsRefillWizardStep.RESERVOIR_AMOUNT, rh.gs(CoreUiStrings.error))
            } else {
                _uiState.update { it.copy(step = DanaRsRefillWizardStep.PRIME_TUBE, primeState = DanaRsPrimeState.READY) }
            }
        }
    }

    fun updateTubeAmount(value: String) {
        _uiState.update { it.copy(tubeAmount = filterAmount(value)) }
    }

    fun updateCannulaAmount(value: String) {
        _uiState.update { it.copy(cannulaAmount = filterAmount(value)) }
    }

    fun startPrimeTube() = startPrime(DanaRsRefillWizardStep.PRIME_TUBE, _uiState.value.tubeAmount)

    fun startPrimeCannula() = startPrime(DanaRsRefillWizardStep.PRIME_CANNULA, _uiState.value.cannulaAmount)

    fun nextAfterTube() {
        _uiState.update { it.copy(step = DanaRsRefillWizardStep.PRIME_CANNULA, primeState = DanaRsPrimeState.READY) }
    }

    fun nextAfterCannula() {
        _uiState.update { it.copy(step = DanaRsRefillWizardStep.COMPLETE) }
    }

    fun retry() {
        val step = _uiState.value.failedStep
        _uiState.update { it.copy(step = step, errorMessage = null, primeState = DanaRsPrimeState.READY) }
    }

    fun finishWizard() {
        _events.tryEmit(DanaRsRefillWizardEvent.Finish)
    }

    fun cancel() {
        _events.tryEmit(DanaRsRefillWizardEvent.Finish)
    }

    private fun startPrime(step: DanaRsRefillWizardStep, amountText: String) {
        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0.0) return
        _uiState.update { it.copy(primeState = DanaRsPrimeState.PRIMING) }
        scope.launch {
            val result = commandQueue.bolus(
                DetailedBolusInfo().apply {
                    insulin = amount
                    bolusType = BS.Type.PRIMING
                }
            )
            if (result.success) {
                _uiState.update { it.copy(primeState = DanaRsPrimeState.DONE) }
            } else {
                aapsLogger.error(LTag.PUMP, "RefillPrimeWizard: $step failed: ${result.comment}")
                fail(step, result.comment)
            }
        }
    }

    private fun fail(step: DanaRsRefillWizardStep, message: String) {
        _uiState.update {
            it.copy(step = DanaRsRefillWizardStep.ERROR, errorMessage = message, failedStep = step, primeState = DanaRsPrimeState.READY)
        }
    }

    private fun filterAmount(value: String): String {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val firstDot = filtered.indexOf('.')
        val deduped = if (firstDot == -1) filtered else filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).filter { it != '.' }
        return deduped.take(5)
    }
}
