package app.aaps.pump.danars.di

import app.aaps.pump.danars.comm.DanaRSPacket
import app.aaps.pump.danars.comm.DanaRSPacketNotifyAlarm
import app.aaps.pump.danars.comm.DanaRSPacketNotifyDeliveryComplete
import app.aaps.pump.danars.comm.DanaRSPacketNotifyDeliveryRateDisplay
import app.aaps.pump.danars.comm.DanaRSPacketNotifyMissedBolusAlarm
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * The DanaRS command set: the packets that are not a response to a sent packet, used by
 * [app.aaps.pump.danars.comm.DanaRSMessageHashTable] to find the right packet for an unsolicited
 * message. Was part of `DanaRSModule`/`DanaRSCommModule` on Android, then `DanaRSBindings`.
 *
 * Moved here from `DanaRSBindings` (androidMain) because it has no Android-specific part - all
 * four packets are plain commonMain classes - and the iOS driver (`IosDanaRsDriverHost`) needs
 * this binding too. `IosAppGraph` failed with `[Metro/MissingBinding] No binding found for
 * Set<DanaRSPacket>` before this moved, since androidMain code is not visible to iOS.
 *
 * No `@JvmSuppressWildcards`: that is a JVM-only Java interop hint with no meaning on iOS, the
 * same reason `DanaRSMessageHashTable` dropped it when it moved to commonMain.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DanaRSCommonBindings {

    @Provides
    fun providesCommands(
        notifyAlarm: DanaRSPacketNotifyAlarm,
        notifyDeliveryComplete: DanaRSPacketNotifyDeliveryComplete,
        notifyDeliveryRateDisplay: DanaRSPacketNotifyDeliveryRateDisplay,
        notifyMissedBolusAlarm: DanaRSPacketNotifyMissedBolusAlarm
    ): Set<DanaRSPacket> =
        setOf(notifyAlarm, notifyDeliveryComplete, notifyDeliveryRateDisplay, notifyMissedBolusAlarm)
}
