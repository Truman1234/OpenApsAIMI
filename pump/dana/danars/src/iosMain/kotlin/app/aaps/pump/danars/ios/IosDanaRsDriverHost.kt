package app.aaps.pump.danars.ios

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.danars.comm.DanaRSMessageHashTable
import app.aaps.pump.danars.comm.DanaRSPacketBasalGetBasalRate
import app.aaps.pump.danars.comm.DanaRSPacketEtcKeepConnection
import app.aaps.pump.danars.comm.DanaRSPacketGeneralGetPumpCheck
import app.aaps.pump.danars.comm.DanaRSPacketGeneralGetShippingInformation
import app.aaps.pump.danars.comm.DanaRSPacketGeneralInitialScreenInformation
import app.aaps.pump.danars.encryption.BleEncryption
import app.aaps.pump.danars.session.DanaRsSession
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Builds the iOS Dana RS / Dana-i driver and owns the parts it needs: the transport, the session
 * and the coroutine scope. Same role as `IosMedtrumDriverHost`.
 *
 * Nothing here is wired into the iOS shell yet, and there is no pairing screen (that is step 4d),
 * so [IosDanaRsPlugin] can be built but has no device address to connect to until then.
 */
@SingleIn(AppScope::class)
@Inject
class IosDanaRsDriverHost(
    aapsLogger: AAPSLogger,
    rxBus: RxBus,
    danaPump: DanaPump,
    bleEncryption: BleEncryption,
    dateUtil: DateUtil,
    preferences: Preferences,
    host: IosDanaRsHost,
    danaRSMessageHashTable: DanaRSMessageHashTable,
    pumpEnactResultProvider: () -> PumpEnactResult,
    danaRSPacketEtcKeepConnection: () -> DanaRSPacketEtcKeepConnection,
    danaRSPacketGeneralGetShippingInformation: () -> DanaRSPacketGeneralGetShippingInformation,
    danaRSPacketGeneralGetPumpCheck: () -> DanaRSPacketGeneralGetPumpCheck,
    danaRSPacketBasalGetBasalRate: () -> DanaRSPacketBasalGetBasalRate,
    danaRSPacketGeneralInitialScreenInformation: () -> DanaRSPacketGeneralInitialScreenInformation
) {

    /** Own scope, cancelled in [shutdown] like `IosMedtrumDriverHost`'s. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The handshake, pairing and packet exchange, built only on first use. `createIosDanaRsBleTransport()`
     * opens CoreBluetooth as soon as it is constructed (see `IosDanaRsBleTransport`'s doc), and that
     * must not happen just because this host exists - see the doc on [IosDanaRsPump]'s `session`.
     * `requiresBond = false`: iOS has no system bond to ask for, unlike Android.
     */
    val session: Lazy<DanaRsSession> = lazy {
        DanaRsSession(
            aapsLogger = aapsLogger,
            rxBus = rxBus,
            danaPump = danaPump,
            bleEncryption = bleEncryption,
            dateUtil = dateUtil,
            preferences = preferences,
            bleTransport = createIosDanaRsBleTransport(),
            host = host,
            findMessage = danaRSMessageHashTable::findMessage,
            scope = scope,
            requiresBond = false
        )
    }

    val driver = IosDanaRsPump(
        aapsLogger = aapsLogger,
        danaPump = danaPump,
        session = session,
        preferences = preferences,
        dateUtil = dateUtil,
        pumpEnactResultProvider = pumpEnactResultProvider,
        danaRSPacketEtcKeepConnection = danaRSPacketEtcKeepConnection,
        danaRSPacketGeneralGetShippingInformation = danaRSPacketGeneralGetShippingInformation,
        danaRSPacketGeneralGetPumpCheck = danaRSPacketGeneralGetPumpCheck,
        danaRSPacketBasalGetBasalRate = danaRSPacketBasalGetBasalRate,
        danaRSPacketGeneralInitialScreenInformation = danaRSPacketGeneralInitialScreenInformation,
        scope = scope
    )

    /**
     * Ends the session and the driver's own work. After this the host must not be used again.
     *
     * [IosDanaRsPump.disconnect] itself does nothing when [session] was never built, so this is
     * safe to call even when the radio was never opened.
     */
    fun shutdown() {
        driver.disconnect("plugin stopped")
        scope.cancel()
    }
}
