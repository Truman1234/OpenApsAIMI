package app.aaps.plugins.sync.wear.applewatch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.wear.WatchUpdates
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The Apple Watch on the iPhone.
 *
 * The phone side is the one Wear OS uses: [WatchUpdates] decides when the watch gets new data, and
 * `DataHandlerMobile` builds that data and handles every command from the watch (bolus, wizard,
 * running mode, temp target), with the same checks and confirmation steps. This plugin only adds the
 * transport: [AppleWatchLink] and the Swift [AppleWatchCompanion] (WatchConnectivity).
 *
 * It uses the Wear settings ([BooleanKey.WearControl] and the wizard switches), so a settings file
 * from an Android phone keeps them.
 *
 * ⚠️ ASYNC IMPACT: the collectors run on [scope]. Swift calls [Callback] on a WatchConnectivity
 * thread; an incoming message goes straight to the bus, which keeps the order of the messages.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(350)
@SingleIn(AppScope::class)
@Inject
class AppleWatchPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    notificationManager: NotificationManager,
    private val rxBus: RxBus,
    private val watchUpdates: WatchUpdates,
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(Icons.Default.Watch)
        .pluginName(SyncStrings.apple_watch)
        .description(SyncStrings.description_apple_watch),
    aapsLogger = aapsLogger, rh = rh, preferences = preferences, notificationManager = notificationManager
) {

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var companion: AppleWatchCompanion? = null

    override suspend fun onStart() {
        super.onStart()
        val newScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = newScope
        val companion = AppleWatchRegistry.companion
        if (companion == null) {
            aapsLogger.info(LTag.WEAR, "Apple Watch bridge is not installed")
            return
        }
        this.companion = companion
        val link = AppleWatchLink(
            companion = companion,
            isEnabled = ::isEnabled,
            toPhone = { rxBus.send(it) },
            log = { aapsLogger.debug(LTag.WEAR, it) },
        )
        rxBus.toFlow(EventMobileToWear::class)
            .collectResilient(newScope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { link.toWatch(it.payload) }
        watchUpdates.start(newScope, isEnabled = ::isEnabled)
        companion.start(Callback(link))
        aapsLogger.info(LTag.WEAR, "Apple Watch started")
    }

    override suspend fun onStop() {
        companion?.stop()
        companion = null
        scope.cancel()
        aapsLogger.info(LTag.WEAR, "Apple Watch stopped")
        super.onStop()
    }

    private inner class Callback(private val link: AppleWatchLink) : AppleWatchCallback {

        override fun messageReceived(json: String) = link.fromWatch(json)

        override fun watchStateChanged(paired: Boolean, appInstalled: Boolean) {
            aapsLogger.info(LTag.WEAR, "Apple Watch paired=$paired appInstalled=$appInstalled")
            // A newly installed or paired watch has nothing yet. The same request the Wear OS service
            // sends when a watch connects.
            if (paired && appInstalled) rxBus.send(EventData.ActionResendData("AppleWatchPlugin"))
        }
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "apple_watch_settings",
        title = SyncStrings.apple_watch,
        items = listOf(
            BooleanKey.WearControl,
            PreferenceSubScreenDef(
                key = "apple_watch_wizard_settings",
                title = CoreUiStrings.wear_wizard_settings,
                summary = SyncStrings.wear_wizard_settings_summary,
                items = listOf(
                    BooleanKey.WearWizardBg,
                    BooleanKey.WearWizardTt,
                    BooleanKey.WearWizardTrend,
                    BooleanKey.WearWizardCob,
                    BooleanKey.WearWizardIob
                )
            ),
            BooleanKey.WearNotifyOnSmb
        ),
        icon = pluginDescription.icon
    )
}
