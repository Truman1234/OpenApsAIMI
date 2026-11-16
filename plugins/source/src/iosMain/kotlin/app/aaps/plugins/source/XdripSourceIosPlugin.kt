package app.aaps.plugins.source

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.ui.compose.icons.IcXDrip
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.xdrip.XdripAppGroupStore
import app.aaps.plugins.source.xdrip.XdripHeartbeatListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle

/**
 * xDrip4iOS as a BG source on iPhone, over the "App Group" mechanism Loop/Trio/iAPS all use:
 * xDrip4iOS writes its readings into a shared `NSUserDefaults` suite, and this plugin reads them
 * back - see [XdripAppGroupStore]. Also covers Dexcom G6 (and anything else) when xDrip4iOS is the
 * app actually talking to the sensor; there is no separate G6 driver here.
 *
 * `@IntKey(400)` is the same number Android's `XdripSourcePlugin` uses - the same slot, read a
 * different way, never both present in the same build. Deliberately does NOT implement
 * `XDripSource`: that interface is about the Android broadcast-receiver channel, which
 * `IosXDripSource` (`ios/shell`) already answers "disabled" for, and a second binding for it here
 * would collide with that one.
 *
 * The App Group ID itself is not a setting in this app: it is read from this app's own Info.plist
 * key `"AppGroupID"`, exactly like iAPS does. That key, the matching App Group capability, and
 * using the same App Group ID in the person's own xDrip4iOS build are the maintainer's own Xcode
 * setup step, not something this plugin can do.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(400)
@SingleIn(AppScope::class)
@Inject
class XdripSourceIosPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    notificationManager: NotificationManager,
    private val persistenceLayer: PersistenceLayer
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { BgSourceComposeContent(title = rh.gs(SourceStrings.xdrip_ios_bg_source)) }
        .icon(IcXDrip)
        .pluginName(SourceStrings.xdrip_ios_bg_source)
        .description(SourceStrings.description_source_xdrip_ios),
    aapsLogger, rh, notificationManager
), BgSource {

    private val ioScope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    private val heartbeatListener = XdripHeartbeatListener(aapsLogger)
    private var store: XdripAppGroupStore? = null

    override suspend fun onStart() {
        super.onStart()
        heartbeatListener.onHeartbeat = {
            ioScope.launch {
                // Give xDrip4iOS a few seconds to finish reading the sensor and writing the shared
                // data, same delay `HeartBeatManager.swift` uses before it re-reads.
                delay(HEARTBEAT_READ_DELAY_MS)
                ingestNewReadings()
            }
        }
        val appGroupId = (NSBundle.mainBundle.objectForInfoDictionaryKey("AppGroupID") as? String)?.takeIf { it.isNotBlank() }
        if (appGroupId == null) {
            aapsLogger.error(LTag.BGSOURCE, "XDRIP_IOS: no \"AppGroupID\" Info.plist key, this build cannot read xDrip4iOS")
            notificationManager.post(NotificationId.XDRIP_IOS_APP_GROUP_MISSING, SourceStrings.xdrip_ios_app_group_missing)
            return
        }
        notificationManager.dismiss(NotificationId.XDRIP_IOS_APP_GROUP_MISSING)
        val newStore = XdripAppGroupStore(appGroupId)
        store = newStore
        ioScope.launch { ingestNewReadings() }
        newStore.heartbeatIdentity()?.let {
            heartbeatListener.start(it.deviceAddress, it.serviceUuid, it.receiveCharacteristicUuid)
        }
    }

    override suspend fun onStop() {
        heartbeatListener.stop()
        store = null
        super.onStop()
    }

    private suspend fun ingestNewReadings() {
        val readings = store?.readNewReadings().orEmpty()
            .filter { it.timestampMs > 0 && it.mgdl > 0.0 }
        if (readings.isEmpty()) return
        val glucoseValues = readings.map { reading ->
            GV(
                timestamp = reading.timestampMs,
                raw = null,
                value = reading.mgdl,
                trendArrow = TrendArrow.fromString(reading.trend),
                noise = null,
                // Not derivable from what xDrip4iOS shares (see the plugin doc comment on the Dexcom
                // G6 case): UNKNOWN is the honest answer, not a guess that would feed a lag
                // compensation keyed on the wrong sensor.
                sourceSensor = SourceSensor.UNKNOWN
            )
        }
        val result = persistenceLayer.insertCgmSourceData(Sources.Xdrip, glucoseValues, emptyList(), sensorInsertionTime = null)
        aapsLogger.debug(
            LTag.BGSOURCE,
            "XDRIP_IOS_BG: insert complete - inserted: ${result.inserted.size}, updated: ${result.updated.size}"
        )
    }

    private companion object {

        const val HEARTBEAT_READ_DELAY_MS = 5_000L
    }
}
