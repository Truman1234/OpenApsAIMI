package app.aaps.pump.danars.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.icons.IcPluginDanaI
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.dana.DanaPump
import app.aaps.pump.dana.DanaStrings
import app.aaps.pump.danars.compose.DanaRsPairWizardModel
import app.aaps.pump.danars.compose.DanaRsPairWizardScreen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The plugin wrapper around the iOS Dana RS / Dana-i driver. All of [Pump] is delegated to the
 * driver in [host] through one `by` clause, same as `IosMedtrumPlugin`.
 *
 * `@MetroIntKey(1040)` is the same number the Android `DanaRSPlugin` uses. No `@PumpDriver`: that
 * qualifier is Android-only, `IosAppGraph` keeps the iOS drivers in the unqualified bucket.
 *
 * The preference screen has one entry, [DanaRsIosIntentKey.PairPump]: the shared pairing wizard
 * over [DanaRsPairWizardModel]. The overview screen ([DanaRsOverviewContent], wired through
 * `composeContent{}` below) opens the very same wizard through its own "Pair" action, so this
 * settings entry is a second, independent way to reach it.
 *
 * [danaRsHost] is the same [app.aaps.pump.danars.session.DanaRsHost] the driver uses (through
 * [host]): the wizard must drive the one session the driver will later use to talk to the pump,
 * not a second one. [host]'s `session` is lazy and opens CoreBluetooth only on first access (see
 * its doc); reading `host.session.value` here, inside the wizard model builder, means that still
 * only happens once the wizard is actually opened.
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
    private val host: IosDanaRsDriverHost,
    private val danaRsHost: IosDanaRsHost,
    private val danaPump: DanaPump,
    private val pumpSync: () -> PumpSync,
    // Providers: only the overview screen needs these, and only when it is opened.
    private val rxBus: () -> RxBus,
    private val persistenceLayer: () -> PersistenceLayer,
    private val ch: () -> ConcentrationHelper,
    private val uel: () -> UserEntryLogger,
    private val dateUtil: () -> DateUtil
) : PumpPluginBase(
    // The pump screen of the overview. Built when it is opened, so building the plugin builds no UI.
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .icon(IcPluginDanaI)
        .pluginName(DanaStrings.danarspump)
        .description(DanaStrings.description_pump_dana_rs)
        .composeContent { plugin -> (plugin as IosDanaRsPlugin).overviewContent() },
    ownPreferences = DanaRsIosIntentKey.entries,
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

    /**
     * Opens through [withCompose] rather than an Activity, which is how every shared screen
     * reaches iOS. [DanaRsPairWizardScreen] is shared code - Android could show the very same
     * one once it is wired there too - and the only iPhone-specific part is [danaRsHost] and
     * [host]'s session.
     */
    override fun getPreferenceScreenContent(): PreferenceItem = PreferenceSubScreenDef(
        key = "danars_ios_settings",
        title = DanaStrings.danarspump,
        items = listOf(
            DanaRsIosIntentKey.PairPump.withCompose(
                ComposeScreenContent { onBack -> PairWizard(onBack = onBack) }
            )
        ),
        icon = pluginDescription.icon
    )

    /**
     * One wizard model per opened screen, living as long as that screen does.
     *
     * [onBack] alone leaves the settings screen; the wizard's own [DanaRsPairWizardModel.cancel]
     * also has to run first when the person backs out early, so the scan stops and the pairing
     * state resets - [DanaRsPairWizardScreen]'s `onFinish` does not need that, since reaching
     * [app.aaps.pump.danars.compose.DanaRsWizardStep.COMPLETE] already called
     * [DanaRsPairWizardModel.finishWizard]. [DanaRsPairWizardModel.close] runs on every exit
     * through [DisposableEffect], same as the scope `remember`s this model is tied to.
     */
    @Composable
    private fun PairWizard(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val model = remember { pairWizardModel(scope).also { it.open() } }
        DisposableEffect(model) {
            onDispose { model.close() }
        }
        DanaRsPairWizardScreen(
            model = model,
            onFinish = onBack,
            onCancel = {
                model.cancel()
                onBack()
            }
        )
    }

    /** The pump screen: the 11 shared info rows, refresh, and pair/unpair. */
    private fun overviewContent() = DanaRsOverviewContent(
        host = host,
        danaRsHost = danaRsHost,
        danaPump = danaPump,
        rh = rh,
        commandQueue = commandQueue,
        rxBus = rxBus(),
        persistenceLayer = persistenceLayer(),
        ch = ch(),
        uel = uel(),
        dateUtil = dateUtil(),
        preferences = preferences,
        pairWizard = { onClose -> PairWizard(onBack = onClose) }
    )

    private fun pairWizardModel(scope: CoroutineScope) = DanaRsPairWizardModel(
        aapsLogger = aapsLogger,
        rh = rh,
        session = host.session.value,
        host = danaRsHost,
        preferences = preferences,
        pumpSync = pumpSync(),
        commandQueue = commandQueue,
        scope = scope
    )
}
