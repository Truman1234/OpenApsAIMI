package app.aaps.plugins.sync.wear.applewatch

/**
 * WatchConnectivity, as the Kotlin plugin is allowed to see it.
 *
 * Swift owns `WCSession` (`SwiftAppleWatchCompanion` in `ios/app/Shared/AppleWatchBridge.swift`).
 * Kotlin decides what goes to the watch; Swift only moves the text. Every payload is one
 * `EventData` as JSON, the same JSON the Wear OS watch gets.
 *
 * Calls come from any thread.
 */
interface AppleWatchCompanion {

    /**
     * Called once when the plugin starts. [watchCallback] stays valid until [stop].
     *
     * The parameter is not called `callback` on purpose: `LiveActivityCompanion` already has a
     * `start(callback:)` with another type. Kotlin/Native then renames one of them in Objective-C
     * (`start(callback_:)`), and the Swift class no longer matches the protocol.
     */
    fun start(watchCallback: AppleWatchCallback)

    /**
     * The newest value of one kind of data, for example the last glucose value. Swift keeps the newest
     * JSON for each [key] and gives all of them to the watch together (`updateApplicationContext`).
     * The watch gets them even when it was not reachable at the time.
     */
    fun sendState(key: String, json: String)

    /**
     * A message that only makes sense now, for example a confirmation screen. Sent only while the
     * watch app is reachable, and dropped otherwise. It is never queued: an old confirmation must not
     * show up later.
     */
    fun sendLive(json: String)

    /** Drops the callback. Safe to call more than once. */
    fun stop()
}

/** What Swift tells the plugin. Called on whatever thread WatchConnectivity uses. */
interface AppleWatchCallback {

    /** One `EventData` as JSON, sent by the watch app. */
    fun messageReceived(json: String)

    /**
     * The session is active, or the watch was paired, unpaired, or the watch app was installed or
     * removed.
     */
    fun watchStateChanged(paired: Boolean, appInstalled: Boolean)
}

/**
 * Set from the Swift app entry, before the graph starts the plugin. The same shape as
 * `ConnectIqRegistry`: Swift builds it, Kotlin cannot.
 */
object AppleWatchRegistry {
    var companion: AppleWatchCompanion? = null
}

fun installAppleWatchCompanion(companion: AppleWatchCompanion) {
    AppleWatchRegistry.companion = companion
}
