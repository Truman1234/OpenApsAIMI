package app.aaps.pump.danars.ios

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginDanaI
import app.aaps.pump.dana.DanaStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The plugin wrapper around the iOS Dana RS / Dana-i driver. All of [Pump] is delegated to the
 * driver in [host] through one `by` clause, same as `IosMedtrumPlugin`.
 *
 * `@MetroIntKey(1040)` is the same number the Android `DanaRSPlugin` uses. No `@PumpDriver`: that
 * qualifier is Android-only, `IosAppGraph` keeps the iOS drivers in the unqualified bucket.
 *
 * No preference screen yet: the pairing wizard (step 4d) is what will give this plugin a device
 * to connect to. Until then the plugin builds and can be registered, but [Pump.isConfigured] stays
 * false because the Dana RS preferences (`MacAddress`, `RsName`) are never set from anywhere on iOS.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(1040)
@SingleIn(AppScope::class)
@Inject
class IosDanaRsPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    commandQueue: CommandQueue,
    notificationManager: NotificationManager,
    private val host: IosDanaRsDriverHost
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .icon(IcPluginDanaI)
        .pluginName(DanaStrings.danarspump)
        .description(DanaStrings.description_pump_dana_rs),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    commandQueue = commandQueue,
    notificationManager = notificationManager
), Pump by host.driver {

    override suspend fun onStop() {
        host.shutdown()
        super.onStop()
    }
}
