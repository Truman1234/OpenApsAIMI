package app.aaps.plugins.sync.wear.wearintegration

/**
 * The few things [DataHandlerMobile] needs from the platform.
 *
 * Everything else the handler does - checks, confirmation text, the command queue, the messages to the
 * watch - is shared code. Android implements this in `AndroidWatchHandlerPlatform`. iOS gets its own
 * implementation together with the Apple Watch bridge (`docs/kmp-migration/apple-watch-app.md`).
 */
interface WatchHandlerPlatform {

    /**
     * True when the phone shows its dark theme. It picks the colour set for the prediction lines
     * that are sent to the watch graph.
     */
    fun isDarkTheme(): Boolean

    /**
     * Removes the open loop "new change request" notification, after the watch has accepted the
     * request.
     */
    fun cancelOpenLoopNotification()

    /**
     * True when the watch wants to hear that a confirmed command was stored, also when this phone is the
     * master. A Wear OS watch on a master shows its own success at once, so Android keeps false. The
     * Apple Watch waits for the phone and shows "Done" only then.
     */
    val reportsMasterSuccess: Boolean get() = false
}
