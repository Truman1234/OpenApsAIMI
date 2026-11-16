package app.aaps.implementation.notifications

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmAction
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.interfaces.notifications.AlarmSoundPlayer
import app.aaps.core.interfaces.notifications.IosNotificationDelegate
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.SystemNotificationPlatform
import app.aaps.core.keys.interfaces.TextRef
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionCustomDismissAction
import platform.UserNotifications.UNNotificationDismissActionIdentifier
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The system tray half of notifications on iOS.
 *
 * iOS keeps far more of this than Android does. There is no channel to create, no `PendingIntent`
 * to build, and no volume ramp to drive: a delivered notification carries its own sound, and the
 * system decides how to present it. So the interesting part here is what is *absent* compared with
 * the Android side.
 *
 * Permission is requested once, lazily, on the first notification. Asking in the constructor would
 * put the system prompt in front of the user during start up, before anything has explained why the
 * app wants it.
 */
class IosSystemNotificationPlatform(
    private val aapsLogger: AAPSLogger,
    private val alarmSoundPlayer: AlarmSoundPlayer,
    /**
     * The soft melody the user chose for an alarm category, `null` for the AAPS sound. Read fresh each
     * time, like [overrideDoNotDisturb]. It decides the sound of an urgent notification, which is what
     * the person hears while iOS has suspended the app.
     *
     * The default (no melody) keeps the iOS default sound, so a caller that does not pass this still
     * gets an audible urgent notification - only the melody choice is not applied to it.
     */
    private val chosenMelody: (AlarmSoundCategory) -> AlarmMelody? = { null },
    /**
     * The button text of an [AlarmAction] (for example "Hypo treated"). Read when the categories
     * are registered, at start up.
     */
    private val alarmActionLabel: (AlarmAction) -> String = { it.name },
    /**
     * Turns the [NotificationAction.buttonText] of a generic action into the string a real iOS
     * button shows. Read every time [show] posts, not once at start up like [alarmActionLabel],
     * because a generic action's label is chosen by whoever built the notification, not by a fixed
     * enum.
     *
     * The default only resolves a literal, so code built without a real resolver - a test, for
     * example - still gets something readable instead of a cryptic fallback.
     */
    private val resolveText: (TextRef) -> String = { (it as? TextRef.Literal)?.text ?: it.toString() },
    /**
     * What to do when the user presses the button of an [AlarmAction] on the notification. A
     * function rather than `LocalAlertUtils` itself, because `LocalAlertUtils` posts through the
     * notification manager that is built on top of this class.
     *
     * The default does nothing. Then the button is not offered at all, see [categoryFor], because a
     * button that does nothing would be worse than none: the user would think the alarm was handled.
     */
    private val onAlarmAction: ((AlarmAction) -> Unit)? = null,
    /**
     * `AlertOverrideDoNotDisturb`, read fresh each time so a change takes effect without a restart.
     *
     * A supplier rather than `Preferences` itself: this needs one boolean, while `Preferences` has 76
     * members and no test binary here can fake it - `iosTest` has no Mockito. Narrowing it is what
     * lets [breaksThroughFocus] be checked at all.
     */
    private val overrideDoNotDisturb: () -> Boolean
) : SystemNotificationPlatform {

    /** The alarm currently owning the audio, so an unchanged owner does not restart the sound. */
    private var soundingKey: Int? = null

    /**
     * Instance keys whose notification carries actions and has not been answered yet.
     *
     * These are the ones a swipe must not clear. See [onDismissed] for what went wrong without it.
     * Entries leave only through [cancel] or [cancelAll] - that is, when the notification is
     * genuinely finished with, rather than when the user brushed it off the screen.
     */
    private val unanswered = mutableSetOf<Int>()

    /**
     * The [NotificationAction]s currently posted for an instance key, so the dispatch handler in
     * [onDismissed] can find the one a button press refers to.
     *
     * Same lifecycle as [unanswered]: set in [show], cleared in [forget]/[forgetAll] - that is, when
     * the notification is genuinely finished with. A stale entry is harmless (it would answer a
     * button that is no longer shown to anyone), a missing one means the button silently does
     * nothing, which is why both [forget] and [forgetAll] clear this alongside [unanswered].
     */
    private val currentActions = mutableMapOf<Int, List<NotificationAction>>()

    /** Label sets already registered as a category, so posting the same buttons again does not. */
    private val knownActionCategoryIds = mutableSetOf<String>()

    /**
     * Resolved on first use, not in the constructor.
     *
     * `currentNotificationCenter()` needs an app bundle and throws
     * `bundleProxyForCurrentProcess is nil` without one, so constructing this class eagerly made it
     * impossible to build outside a running app - including in a test. Nothing here needs the centre
     * until something is actually posted.
     */
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }
    private var authorizationAsked = false

    /**
     * iOS shows every notification, unlike Android.
     *
     * `AlertUrgentAsAndroidNotification` is not consulted, and that is deliberate: it decides whether
     * AAPS raises an OS notification at all, and on iOS that choice belongs to the user in Settings.
     * The key says so itself now - it is marked Android only.
     *
     * `sound` is not read either, because every notification here is posted silently and the audio is
     * handed to [AlarmSoundPlayer] through [setAudibleAlarm] - the same split Android uses, so a
     * replaced alarm does not restart the sound. (An earlier version of this comment claimed there
     * was no ramping player to hand it to. There is: `IosAlarmSoundPlayer`, which ramps exactly as
     * the Android one does.)
     *
     * `actions` (the in-app buttons) now get a real button too: one [UNNotificationCategory] per
     * resolved label set, registered the first time it is seen and reused afterwards, see
     * [registerActionsCategoryIfNeeded]. A button's own closure travels only as far as this process -
     * which is fine, since it is answered while the app is alive, the same way `NotificationBottomSheet`
     * answers it in-app. The [AlarmAction] an alarm declares on its [NotificationId] (for example
     * "Hypo treated" on the low glucose alarm) still takes priority and keeps its own fixed category,
     * see [categoryFor].
     */
    override fun show(notification: AapsNotification, title: String) {
        ensureAuthorization()
        rememberIfUnanswered(notification)
        rememberActions(notification)
        val breakThroughFocus = breaksThroughFocus(notification.level)
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(notification.text)
            // The sound stays off here on purpose: audible alarms are driven by setAudibleAlarm, the
            // same split the Android side uses so a replaced alarm does not restart the sound.
            // Without the category the dismiss callback never fires - see onDismissed.
            setCategoryIdentifier(categoryFor(notification))
            // An urgent alarm also carries a sound. While iOS suspends the app, the alarm player
            // cannot start any audio, so with the phone locked this sound - played by iOS itself - is
            // the only sound the person hears.
            if (notification.level == NotificationLevel.URGENT) setSound(urgentSound(notification))
            setInterruptionLevel(
                if (breakThroughFocus) UNNotificationInterruptionLevelTimeSensitive
                else UNNotificationInterruptionLevelActive
            )
        }
        // A repeated identifier replaces the delivered notification rather than adding another,
        // which is the behaviour the registry expects when it reposts the same id.
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = identifier(notification.instanceKey),
            content = content,
            trigger = null
        )
        center.addNotificationRequest(request) { error ->
            if (error != null) aapsLogger.error(LTag.NOTIFICATION, "Cannot post notification: $error")
        }
    }

    /**
     * The sound of an urgent notification: the soft melody the user chose for its category, as on
     * Android, or the iOS default sound. If the melody file cannot be written, the default sound is
     * used, so the notification is never silent because of a melody.
     */
    private fun urgentSound(notification: AapsNotification): UNNotificationSound =
        chosenMelody(AlarmAudioPolicy.categoryOf(notification.id))?.let { IosMelodyAudio.notificationSound(it) }
            ?: UNNotificationSound.defaultSound

    /**
     * Whether this notification is allowed past a Focus mode.
     *
     * The Focus half of `AlertOverrideDoNotDisturb`. On iOS that one setting splits across two
     * mechanisms, because Focus suppresses **notifications** while the Ring/Silent switch silences
     * **audio**, and neither lever reaches the other:
     *
     * - the silent switch is answered by the audio session category, in `IosAlarmSoundPlayer`;
     * - Focus is answered here, and only `timeSensitive` gets through it.
     *
     * This used to read the urgency alone, so half of what the switch promised - "when disabled,
     * alarms respect silent/DND" - was not true whatever the user set.
     *
     * Non-urgent notifications never break through, exactly as before: the override widens what an
     * alarm may do, it does not promote ordinary notifications into alarms.
     */
    internal fun breaksThroughFocus(level: NotificationLevel): Boolean =
        level == NotificationLevel.URGENT && overrideDoNotDisturb()

    /**
     * Notes, before posting, that this notification must survive a swipe.
     *
     * Split from [show] only so it can be exercised: [show] reaches `UNUserNotificationCenter`,
     * which needs an app bundle a test binary does not have.
     */
    internal fun rememberIfUnanswered(notification: AapsNotification) {
        if (notification.actions.isNotEmpty()) unanswered += notification.instanceKey
    }

    /**
     * Notes, before posting, the actions [handleNotificationAction] must find back when a button is
     * pressed. Split from [show] for the same reason as [rememberIfUnanswered]: it does not reach
     * `UNUserNotificationCenter`, so a test can call it directly.
     */
    internal fun rememberActions(notification: AapsNotification) {
        if (notification.actions.isNotEmpty()) currentActions[notification.instanceKey] = notification.actions
    }

    /** The counterpart: this notification is finished with, however that came about. */
    internal fun forget(instanceKey: Int) {
        unanswered -= instanceKey
        currentActions -= instanceKey
    }

    /** Same, for the "mute all alarms" path. */
    internal fun forgetAll() {
        unanswered.clear()
        currentActions.clear()
    }

    override fun cancel(instanceKey: Int) {
        forget(instanceKey)
        val ids = listOf(identifier(instanceKey))
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    /**
     * Only the alarms this class posted, and only the delivered ones.
     *
     * This was `removeAllPendingNotificationRequests()` plus `removeAllDeliveredNotifications()`,
     * and both halves were wrong. This class never creates a pending request at all - every [show]
     * posts with `trigger = null`, so it is delivered straight away - but
     * `IosReminderScheduler` does, and its scheduled reminders are named `aaps-reminder-N`, which
     * sits inside this class's own `aaps-` prefix. Muting alarms therefore deleted every automation
     * reminder the user was still waiting on, and none of them ever rang. The delivered half swept
     * up the loop's `aaps-loop` notification for the same reason.
     *
     * Android draws exactly this line and says why in `AndroidSystemNotificationPlatform.cancelAll`:
     * "mute all alarms" means take *my* alarms out of the tray, not empty the tray. [instanceKeyOf]
     * is what decides ownership here, so a `aaps-reminder-3` or an `aaps-loop` is left alone because
     * neither tail parses to a key.
     */
    override fun cancelAll() {
        // "Mute all alarms" is an answer, given deliberately, so these are finished with.
        forgetAll()
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val posted = delivered.orEmpty().mapNotNull { (it as? UNNotification)?.request?.identifier }
            val ids = ownIdentifiers(posted)
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }

    /**
     * The identifiers [cancelAll] is allowed to remove.
     *
     * Split out so it can be checked without a notification centre, which a test binary has no
     * bundle for. The whole safety of [cancelAll] is in this one filter.
     */
    internal fun ownIdentifiers(identifiers: List<String>): List<String> =
        identifiers.filter { instanceKeyOf(it) != null }

    /**
     * Hands the ramping alarm to [AlarmSoundPlayer], or silences it.
     *
     * The registry calls this after every change with whichever alarm owns the sound, so calling it
     * again with the same key must not restart the audio - that is what stops a second alarm
     * cutting the first one off, and why the owner key is compared here rather than in the player.
     */
    override fun setAudibleAlarm(instanceKey: Int?, sound: AlarmSound?) =
        setAudibleAlarm(instanceKey, sound, AlarmSoundCategory.OTHER)

    /** Same, with the sound choice that applies, so `IosAlarmSoundPlayer` can play the chosen melody. */
    override fun setAudibleAlarm(instanceKey: Int?, sound: AlarmSound?, category: AlarmSoundCategory) {
        if (instanceKey == soundingKey) return
        soundingKey = instanceKey
        if (instanceKey == null || sound == null) {
            alarmSoundPlayer.stop(AlarmSoundPlayer.OWNER_INTERNAL)
            aapsLogger.debug(LTag.NOTIFICATION, "Alarm audio stopped")
        } else {
            alarmSoundPlayer.play(sound, AlarmSoundPlayer.OWNER_INTERNAL, category = category)
            aapsLogger.debug(LTag.NOTIFICATION, "Alarm audio owner is now $instanceKey ($sound)")
        }
    }

    /**
     * Learn about notifications the user swiped away outside the app.
     *
     * Two things are needed, and missing either one makes this silently never fire: a delegate on
     * the shared centre, and a category carrying `customDismissAction` - without that iOS reports
     * taps but not dismissals, which is the trap, because the code looks right and nothing arrives.
     *
     * The delegate is not set here. There is one slot for the whole app and `setDelegate` replaces
     * whatever was in it, so [IosNotificationDelegate] owns it and routes; a second owner would
     * silently stop the first one's callbacks.
     */
    override fun onDismissed(callback: (instanceKey: Int) -> Unit) {
        val categories = setOf(dismissibleCategory()) + if (onAlarmAction != null) AlarmAction.entries.map { alarmActionCategory(it) } else emptyList()
        IosNotificationDelegate.register(categories) { actionId, notificationId ->
            // Only answer a button of a notification this class posted.
            val instanceKey = instanceKeyOf(notificationId)
            if (instanceKey != null) {
                if (handleAlarmAction(actionId)) return@register true
                if (handleNotificationAction(instanceKey, actionId)) return@register true
            }
            if (actionId != UNNotificationDismissActionIdentifier) return@register false
            // A tap is deliberately not a dismissal: the notification goes away, but the user asked
            // to *see* the thing, so it stays in the in-app list.
            val key = instanceKey ?: return@register true
            if (clearedByDismissal(key)) callback(key)
            else aapsLogger.debug(LTag.NOTIFICATION, "Swipe ignored for $key: it still carries an unanswered action")
            true
        }
    }

    /**
     * Whether swiping this notification away is allowed to count as answering it.
     *
     * No, when it carries actions nobody has used yet - and that case is why this exists.
     *
     * Every notification posted here is swipeable, because the category has to carry
     * `customDismissAction` for dismissals to be reported at all. The registry turns a reported
     * dismissal into `dismiss(handle)`, which drops the notification, and `refreshAlarmSound` then
     * finds no audible alarm and **stops the sound**. So on an urgent Nightscout alarm the swipe -
     * the first gesture anyone reaches for - silenced it, threw away the card holding the three
     * snooze buttons, never acknowledged Nightscout and never recorded a snooze, so the same alarm
     * returned on the next push.
     *
     * Android forbids exactly this rather than handling it: `AlarmNotificationManager` posts with
     * `setOngoing(true)`, and says why - "so the user can't swipe to dismiss". iOS has no ongoing
     * flag, so the guard has to be here instead. The banner does go from the tray, which iOS will
     * not undo; what does not happen is the alarm being treated as answered.
     */
    internal fun clearedByDismissal(instanceKey: Int): Boolean = instanceKey !in unanswered

    /**
     * The category to post [id] with: one with the button of its [AlarmAction] when it declares one
     * and a handler exists, else the plain one. Both report dismissals, see [onDismissed].
     */
    internal fun categoryFor(id: NotificationId): String {
        val action = id.alarmAction ?: return CATEGORY
        return if (onAlarmAction != null) alarmActionCategoryId(action) else CATEGORY
    }

    /**
     * The category to post [notification] with. The [AlarmAction] on its id still wins when it has
     * one and a handler exists - a fixed button beats a dynamic one, and it is what [categoryFor]
     * already decided. Otherwise, a notification carrying generic [AapsNotification.actions] gets the
     * category built for its exact button labels, see [registerActionsCategoryIfNeeded].
     */
    internal fun categoryFor(notification: AapsNotification): String {
        val byAlarmAction = categoryFor(notification.id)
        if (byAlarmAction != CATEGORY) return byAlarmAction
        if (notification.actions.isEmpty()) return CATEGORY
        return registerActionsCategoryIfNeeded(notification.actions)
    }

    /**
     * Makes sure a category exists for this exact set of resolved button labels, and returns its
     * identifier.
     *
     * One category per label set, not per [NotificationId]: two notifications that share an id can
     * still ask for different buttons - `ios_todo.md` calls this out explicitly - and a category's
     * button titles are fixed at the moment it is registered, not per notification that later points
     * at it. [knownActionCategoryIds] is what makes registering the same labels twice a no-op rather
     * than wasted work on every [show].
     */
    private fun registerActionsCategoryIfNeeded(actions: List<NotificationAction>): String {
        val labels = actions.map { resolveText(it.buttonText) }
        val id = actionsCategoryId(labels)
        if (knownActionCategoryIds.add(id)) {
            IosNotificationDelegate.addCategories(setOf(actionsCategory(id, labels)))
        }
        return id
    }

    /**
     * A category with one real button per label, in order. It also carries `customDismissAction`,
     * the same as [alarmActionCategory], otherwise swiping these notifications away would no longer
     * be reported.
     */
    private fun actionsCategory(id: String, labels: List<String>): UNNotificationCategory =
        UNNotificationCategory.categoryWithIdentifier(
            identifier = id,
            actions = labels.mapIndexed { index, label ->
                UNNotificationAction.actionWithIdentifier(
                    identifier = notificationActionId(index),
                    title = label,
                    options = 0u
                )
            },
            intentIdentifiers = emptyList<Any>(),
            options = UNNotificationCategoryOptionCustomDismissAction
        )

    /**
     * Identifies a category by its exact labels, not by content or [NotificationId], so two
     * notifications that happen to share an id but want different buttons never collide. Visible for
     * tests.
     */
    internal fun actionsCategoryId(labels: List<String>): String =
        "$ACTIONS_CATEGORY_PREFIX${labels.joinToString("|").hashCode()}"

    /**
     * The identifier of the button at [index] within whichever category posted a notification.
     *
     * Positional rather than per-label: the title at a given index is fixed by whichever category
     * posted the notification, so the index alone, together with the instance key the dispatch
     * handler already has from [instanceKeyOf], is enough to find the matching action back in
     * [currentActions].
     */
    internal fun notificationActionId(index: Int) = "$NOTIFICATION_ACTION_PREFIX$index"

    /** The reverse of [notificationActionId]. Null for anything that is not one of these buttons. */
    internal fun actionIndexOf(actionId: String): Int? =
        if (actionId.startsWith(NOTIFICATION_ACTION_PREFIX)) actionId.removePrefix(NOTIFICATION_ACTION_PREFIX).toIntOrNull()
        else null

    /**
     * Runs the `NotificationAction` whose button was pressed, the generic counterpart of
     * [handleAlarmAction]. False when [actionId] is not one of these buttons, or [instanceKey] is
     * not currently holding one - for example because it was already answered in the app, which
     * clears [currentActions] through [forget].
     *
     * Mirrors what `NotificationBottomSheet` already does in-app: run the action's own closure, then
     * let the notification be dismissed as usual. The closure is almost always empty - the DKA
     * ketone-entry button and others like it do their real work through id-based navigation keyed on
     * the `NotificationId` itself, not on this button; the button only has to travel with a label.
     */
    internal fun handleNotificationAction(instanceKey: Int, actionId: String): Boolean {
        val index = actionIndexOf(actionId) ?: return false
        val action = currentActions[instanceKey]?.getOrNull(index) ?: return false
        aapsLogger.debug(LTag.NOTIFICATION, "Notification action pressed on $instanceKey: index $index")
        action.action()
        return true
    }

    /**
     * Runs the [AlarmAction] whose button was pressed. False when [actionId] is not such a button.
     *
     * Split from the delegate callback so it can be checked: a real response needs a delivered
     * notification, which a test binary cannot have.
     */
    internal fun handleAlarmAction(actionId: String): Boolean {
        val handler = onAlarmAction ?: return false
        val action = AlarmAction.entries.firstOrNull { alarmActionId(it) == actionId } ?: return false
        aapsLogger.debug(LTag.NOTIFICATION, "Alarm action pressed on the notification: $action")
        handler(action)
        return true
    }

    /**
     * A category with the button of [action]. It also carries `customDismissAction`, otherwise
     * swiping these notifications away would no longer be reported.
     *
     * The button has no options, so pressing it does not open the app: iOS wakes the app in the
     * background to run it, which is what a person in a hypo with a locked phone needs.
     */
    private fun alarmActionCategory(action: AlarmAction): UNNotificationCategory =
        UNNotificationCategory.categoryWithIdentifier(
            identifier = alarmActionCategoryId(action),
            actions = listOf(
                UNNotificationAction.actionWithIdentifier(
                    identifier = alarmActionId(action),
                    title = alarmActionLabel(action),
                    options = 0u
                )
            ),
            intentIdentifiers = emptyList<Any>(),
            options = UNNotificationCategoryOptionCustomDismissAction
        )

    internal fun alarmActionId(action: AlarmAction) = "$ALARM_ACTION_PREFIX${action.name}"

    private fun alarmActionCategoryId(action: AlarmAction) = "$CATEGORY-${action.name}"

    private fun dismissibleCategory(): UNNotificationCategory =
        UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY,
            actions = emptyList<Any>(),
            intentIdentifiers = emptyList<Any>(),
            options = UNNotificationCategoryOptionCustomDismissAction
        )

    internal fun identifier(instanceKey: Int) = "$IDENTIFIER_PREFIX$instanceKey"

    /** The reverse of [identifier]. Null for anything this class did not post. */
    internal fun instanceKeyOf(identifier: String): Int? =
        if (identifier.startsWith(IDENTIFIER_PREFIX)) identifier.removePrefix(IDENTIFIER_PREFIX).toIntOrNull()
        else null

    private fun ensureAuthorization() {
        if (authorizationAsked) return
        authorizationAsked = true
        requestIosNotificationPermission(aapsLogger)
    }

    companion object {

        private const val IDENTIFIER_PREFIX = "aaps-"

        /** Named once: it has to match between the posted content and the registered category. */
        private const val CATEGORY = "aaps-notification"

        /** Prefix of the identifier of an [AlarmAction] button. */
        private const val ALARM_ACTION_PREFIX = "aaps-alarm-action-"

        /** Prefix of the identifier of a category built for a notification's own generic actions. */
        private const val ACTIONS_CATEGORY_PREFIX = "aaps-actions-"

        /** Prefix of the identifier of a button within such a category. */
        private const val NOTIFICATION_ACTION_PREFIX = "aaps-naction-"
    }
}

/**
 * Asks the person to allow notifications. Called at start-up as well as before the first
 * notification: when the first notification is an alarm that fires while the app is in the
 * background, a question asked only then is never seen, and the alarm stays silent.
 * Asking again after an answer shows nothing; iOS keeps the first answer.
 */
fun requestIosNotificationPermission(aapsLogger: AAPSLogger) {
    val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { granted, error ->
        if (error != null) aapsLogger.error(LTag.NOTIFICATION, "Notification permission failed: $error")
        else aapsLogger.debug(LTag.NOTIFICATION, "Notification permission granted=$granted")
    }
}
