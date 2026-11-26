package app.aaps.plugins.sync.wear.applewatch

import app.aaps.plugins.sync.wear.wearintegration.WatchHandlerPlatform
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import platform.UserNotifications.UNUserNotificationCenter

/** [WatchHandlerPlatform] on the iPhone. */
@ContributesBinding(AppScope::class)
@Inject
class IosWatchHandlerPlatform : WatchHandlerPlatform {

    // Lazy: `currentNotificationCenter()` needs an app bundle and throws without one, so building
    // the graph in a test must not touch it (the same reason as in `IosLoopNotifier`).
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }

    /**
     * Always true. The colours only go to the watch graph, and the Apple Watch always draws on a
     * black background, whatever theme the phone uses.
     */
    override fun isDarkTheme(): Boolean = true

    /** True: the Apple Watch shows "Done" when the phone has stored a command, not before. */
    override val reportsMasterSuccess: Boolean = true

    /** Removes the notification `IosLoopNotifier` posts for an open loop suggestion. */
    override fun cancelOpenLoopNotification() {
        val ids = listOf(LOOP_NOTIFICATION_ID)
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    private companion object {

        /** The same id as `IosLoopNotifier.NOTIFICATION_ID` in `:plugins:aps`, which this module cannot see. */
        const val LOOP_NOTIFICATION_ID = "aaps-loop"
    }
}
