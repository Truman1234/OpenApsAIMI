package app.aaps.pump.danars.ios

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.pump.mapState
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.keys.DanaStringNonKey
import app.aaps.pump.danars.comm.DanaRSPacketBasalGetBasalRate
import app.aaps.pump.danars.comm.DanaRSPacketEtcKeepConnection
import app.aaps.pump.danars.comm.DanaRSPacketGeneralGetPumpCheck
import app.aaps.pump.danars.comm.DanaRSPacketGeneralGetShippingInformation
import app.aaps.pump.danars.comm.DanaRSPacketGeneralInitialScreenInformation
import app.aaps.pump.danars.session.DanaRsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The iOS implementation of [Pump] for Dana RS / Dana-i, read-only.
 *
 * Identity and the status fields ([reservoirLevel], [batteryLevel], [baseBasalRate], ...) come
 * straight from [DanaPump], the same shared object Android fills from the same packets - so
 * nothing here duplicates that data.
 *
 * [getPumpStatus] is a smaller version of Android's `DanaRSService.readPumpStatus()`: only the
 * packets that fill status/reservoir/battery/basal. Time sync, history (`loadEvents`), user
 * options and the bolus calculation data are deliberately left out - they belong to dosing and
 * history, not to this read-only step. Every method that would move insulin fails closed with
 * [failClosed] until the maintainer enables dosing on iOS after a real pump test.
 */
class IosDanaRsPump(
    private val aapsLogger: AAPSLogger,
    private val danaPump: DanaPump,
    /**
     * Built lazily, on the first real connect attempt. Constructing a [DanaRsSession] builds the
     * [app.aaps.core.interfaces.pump.ble.session.IosBleTransport] under it, and that transport
     * opens CoreBluetooth in its own constructor - which is what makes iOS ask for the Bluetooth
     * permission. Nothing here must do that just by existing, since `isConfigured()` is false for
     * everyone until step 4d's pairing wizard exists, so no connect should ever really happen yet.
     */
    private val session: Lazy<DanaRsSession>,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val pumpEnactResultProvider: () -> PumpEnactResult,
    private val danaRSPacketEtcKeepConnection: () -> DanaRSPacketEtcKeepConnection,
    private val danaRSPacketGeneralGetShippingInformation: () -> DanaRSPacketGeneralGetShippingInformation,
    private val danaRSPacketGeneralGetPumpCheck: () -> DanaRSPacketGeneralGetPumpCheck,
    private val danaRSPacketBasalGetBasalRate: () -> DanaRSPacketBasalGetBasalRate,
    private val danaRSPacketGeneralInitialScreenInformation: () -> DanaRSPacketGeneralInitialScreenInformation,
    private val scope: CoroutineScope
) : Pump {

    override val lastDataTime: StateFlow<Long> = danaPump.lastConnectionFlow
    override val lastBolusTime: StateFlow<Long?> = danaPump.lastBolusTimeFlow
    override val lastBolusAmount: StateFlow<PumpInsulin?> = danaPump.lastBolusAmountFlow.mapState { it?.let(::PumpInsulin) }
    override val reservoirLevel: StateFlow<PumpInsulin> = danaPump.reservoirRemainingUnitsFlow.mapState(::PumpInsulin)
    override val batteryLevel: StateFlow<Int?> = danaPump.batteryRemainingFlow
    override val baseBasalRate get() = PumpRate(danaPump.currentBasal)

    override val pumpDescription: PumpDescription
        get() = danaPump.applyReadSteps(PumpDescription().fillFor(danaPump.pumpType()))

    override fun manufacturer(): ManufacturerType = ManufacturerType.Sooil
    override fun model(): PumpType = danaPump.pumpType()
    override fun serialNumber(): String = danaPump.serialNumber

    override fun isConfigured(): Boolean =
        preferences.get(DanaStringNonKey.MacAddress).isNotEmpty() && preferences.get(DanaStringNonKey.RsName).isNotEmpty()

    override fun isInitialized(): Boolean =
        isConfigured() && danaPump.lastConnection > 0 && danaPump.maxBasal > 0 && danaPump.isRSPasswordOK

    override fun isSuspended(): Boolean =
        danaPump.pumpSuspended || danaPump.errorState != DanaPump.ErrorState.NONE

    override fun isBusy(): Boolean = false

    // Checked without building the session, so merely showing pump status never opens CoreBluetooth.
    override fun isConnected(): Boolean = session.isInitialized() && session.value.isConnected
    override fun isConnecting(): Boolean = session.isInitialized() && session.value.isConnecting
    override fun isHandshakeInProgress(): Boolean = false

    override fun connect(reason: String) {
        val address = preferences.get(DanaStringNonKey.MacAddress)
        if (address.isEmpty()) return
        aapsLogger.debug(LTag.PUMP, "RS iOS connect from: $reason")
        scope.launch { session.value.connect(reason, address) }
    }

    override fun disconnect(reason: String) {
        if (!session.isInitialized()) return
        aapsLogger.debug(LTag.PUMP, "RS iOS disconnect from: $reason")
        scope.launch { session.value.disconnect(reason) }
    }

    override fun stopConnecting() {
        if (!session.isInitialized()) return
        scope.launch { session.value.stopConnecting() }
    }

    override suspend fun getPumpStatus(reason: String) {
        if (!isConfigured()) return
        try {
            val danaRsSession = session.value
            danaRsSession.sendMessage(danaRSPacketEtcKeepConnection()) // test encryption for v3 & BLE
            if (!danaRsSession.isConnected) return
            danaRsSession.sendMessage(danaRSPacketGeneralGetShippingInformation()) // serial no
            danaRsSession.sendMessage(danaRSPacketGeneralGetPumpCheck()) // firmware/model
            danaRsSession.sendMessage(danaRSPacketBasalGetBasalRate()) // basal profile, basalStep, maxBasal
            danaRsSession.sendMessage(danaRSPacketGeneralInitialScreenInformation()) // reservoir, battery, current basal
            danaPump.lastConnection = dateUtil.now()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPCOMM, "getPumpStatus failed", e)
        }
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (!isInitialized()) return true
        if (danaPump.pumpProfiles == null) return true
        val basalValues = if (danaPump.basal48Enable) 48 else 24
        val basalIncrement = if (danaPump.basal48Enable) 30 * 60 else 60 * 60
        for (h in 0 until basalValues) {
            val pumpValue = danaPump.pumpProfiles!![danaPump.activeProfile][h]
            val profileValue = profile.getBasalTimeFromMidnight(h * basalIncrement)
            if (abs(pumpValue - profileValue) > pumpDescription.basalStep) return false
        }
        return true
    }

    override fun deliveredBasal(): List<Profile.ProfileValue>? =
        if (!isInitialized()) null
        else danaPump.pumpProfiles?.get(danaPump.activeProfile)?.take(24)?.mapIndexed { hour, rate ->
            Profile.ProfileValue(hour * 3600, rate)
        }

    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = danaPump.usingUTC

    // -- Dosing: off until the maintainer enables it on iOS after a real pump test -------------

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult = failClosed("Dosing not enabled on iOS yet")
    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult = failClosed("Dosing not enabled on iOS yet")
    override fun stopBolusDelivering() { /* nothing is ever delivering: dosing is disabled */ }
    override suspend fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        failClosed("Dosing not enabled on iOS yet")

    override suspend fun setTempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        failClosed("Dosing not enabled on iOS yet")

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult = failClosed("Dosing not enabled on iOS yet")
    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = failClosed("Dosing not enabled on iOS yet")
    override suspend fun cancelExtendedBolus(): PumpEnactResult = failClosed("Dosing not enabled on iOS yet")

    /** No history reading wired on iOS yet (it needs its own history store, see [app.aaps.pump.dana.database.DanaHistoryRecordStore]). */
    override suspend fun loadTDDs(): PumpEnactResult = failClosed("History not available on iOS yet")

    private fun failClosed(comment: String): PumpEnactResult = pumpEnactResultProvider().success(false).enacted(false).comment(comment)
}
