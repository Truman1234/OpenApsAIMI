package app.aaps.plugins.sync.wear.wearintegration

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import app.aaps.core.data.configuration.Constants
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Android side of [WatchHandlerPlatform]. The code is what `DataHandlerMobile` did itself before it moved to commonMain. */
@ContributesBinding(AppScope::class)
@Inject
class AndroidWatchHandlerPlatform(
    private val context: Context,
) : WatchHandlerPlatform {

    override fun isDarkTheme(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun cancelOpenLoopNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Constants.NOTIFICATION_ID)
    }
}
