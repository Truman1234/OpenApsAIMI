package app.aaps.plugins.aps.openAPSAIMI.dka

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.plugins.aps.R

/**
 * Posts/updates the DKA notification. Call update() once per loop cycle with the
 * DkaDecision. Re-alerts are rate limited; the notification content is always kept fresh.
 *
 *  WATCH    - silent-ish, repeats every 2 h, dismissible, snoozable
 *  WARNING  - alerts, ongoing, repeats every 60 min, snoozable for 1 h
 *  CRITICAL - alarm sound, ongoing, repeats every 15 min, NOT snoozable
 */
class DkaGuardNotifier(
    private val context: Context,
    /** Optional: also mirror into AAPS' own notification list (overview screen). */
    private val mirrorToAaps: ((DkaDecision) -> Unit)? = null
) {
    companion object {
        const val CH_WATCH = "dka_guard_watch"
        const val CH_WARN = "dka_guard_warning"
        const val CH_CRIT = "dka_guard_critical"
        const val NOTIF_ID = 74301
        const val SNOOZE_MS = 60 * 60_000L
        private const val K_LAST_RISK = "last_risk"
        private const val K_LAST_ALERT = "last_alert"
        private const val K_SNOOZE_UNTIL = "snooze_until"
        internal fun stateOf(c: Context) = DkaKetoneStore.state(c)
    }

    private val nm = NotificationManagerCompat.from(context)
    private val state = stateOf(context)

    fun update(d: DkaDecision, notifyOnWatch: Boolean = true, nowMs: Long = System.currentTimeMillis()) {
        if (d.risk == DkaRisk.NONE || (d.risk == DkaRisk.WATCH && !notifyOnWatch)) {
            nm.cancel(NOTIF_ID)
            state.edit().putInt(K_LAST_RISK, 0).apply()
            return
        }
        mirrorToAaps?.invoke(d)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels()

        val lastRisk = DkaRisk.values().getOrElse(state.getInt(K_LAST_RISK, 0)) { DkaRisk.NONE }
        val lastAlert = state.getLong(K_LAST_ALERT, 0L)
        val snoozedUntil = state.getLong(K_SNOOZE_UNTIL, 0L)
        val escalated = d.risk.ordinal > lastRisk.ordinal
        val snoozed = d.risk != DkaRisk.CRITICAL && nowMs < snoozedUntil && !escalated
        if (snoozed) return

        val due = nowMs - lastAlert >= repeatMs(d.risk)
        val shouldAlert = escalated || due

        val channel = when (d.risk) {
            DkaRisk.CRITICAL -> CH_CRIT
            DkaRisk.WARNING -> CH_WARN
            else -> CH_WATCH
        }
        val title = when (d.risk) {
            DkaRisk.CRITICAL -> context.getString(R.string.dka_notif_title_critical)
            DkaRisk.WARNING -> context.getString(R.string.dka_notif_title_warning)
            else -> context.getString(R.string.dka_notif_title_watch)
        }
        val reasons = d.reasons.joinToString("\n") { "• $it" }
        val actions = d.messages.joinToString("\n") { "→ $it" }
        val body = listOf(reasons, actions).filter { it.isNotBlank() }.joinToString("\n\n")

        val ketoneIntent = Intent(context, KetoneEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ketonePi = PendingIntent.getActivity(
            context, 1, ketoneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(d.reasons.firstOrNull() ?: d.messages.firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .setOnlyAlertOnce(!shouldAlert)
            .setOngoing(d.risk != DkaRisk.WATCH)
            .setAutoCancel(d.risk == DkaRisk.WATCH)
            .setCategory(if (d.risk == DkaRisk.CRITICAL) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (d.risk == DkaRisk.WATCH) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, context.getString(R.string.dka_action_enter_ketone), ketonePi)

        if (d.risk != DkaRisk.CRITICAL) {
            val snoozePi = PendingIntent.getBroadcast(
                context, 3, Intent(context, DkaSnoozeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, context.getString(R.string.dka_action_snooze), snoozePi)
        }

        nm.notify(NOTIF_ID, b.build())
        state.edit().putInt(K_LAST_RISK, d.risk.ordinal).apply {
            if (shouldAlert) putLong(K_LAST_ALERT, nowMs)
        }.apply()
    }

    private fun repeatMs(r: DkaRisk) = when (r) {
        DkaRisk.CRITICAL -> 15 * 60_000L
        DkaRisk.WARNING -> 60 * 60_000L
        else -> 120 * 60_000L
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sys = context.getSystemService(NotificationManager::class.java)
        if (sys.getNotificationChannel(CH_CRIT) != null) return

        sys.createNotificationChannel(
            NotificationChannel(CH_WATCH, context.getString(R.string.dka_channel_watch), NotificationManager.IMPORTANCE_DEFAULT)
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_WARN, context.getString(R.string.dka_channel_warning), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_CRIT, context.getString(R.string.dka_channel_critical), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setBypassDnd(true) // only effective if the user grants DND access
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }
}

/** Snoozes WATCH/WARNING notifications for 1 hour. CRITICAL ignores snooze. */
class DkaSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DkaGuardNotifier.stateOf(context).edit()
            .putLong("snooze_until", System.currentTimeMillis() + DkaGuardNotifier.SNOOZE_MS).apply()
        NotificationManagerCompat.from(context).cancel(DkaGuardNotifier.NOTIF_ID)
    }
}

/**
 * Small dialog-style activity to enter a blood ketone measurement (mmol/L).
 * Launched from the notification action and from the preference screen.
 */
class KetoneEntryActivity : AppCompatActivity() {

    private companion object {
        const val MAX_KETONE_MMOL = 8.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showEntryDialog()
    }

    private fun showEntryDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val mmolButton = RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.dka_ketone_unit_mmol)
        }
        val mgdlButton = RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.dka_ketone_unit_mgdl)
        }
        val unitGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(mmolButton)
            addView(mgdlButton)
        }
        fun selectedUnit() = if (unitGroup.checkedRadioButtonId == mgdlButton.id) KetoneUnit.MGDL else KetoneUnit.MMOL
        unitGroup.setOnCheckedChangeListener { _, _ ->
            input.hint = getString(if (selectedUnit() == KetoneUnit.MGDL) R.string.dka_ketone_hint_mgdl else R.string.dka_ketone_hint_mmol)
        }
        unitGroup.check(if (DkaKetoneStore.readUnit(this) == KetoneUnit.MGDL) mgdlButton.id else mmolButton.id)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            addView(unitGroup)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dka_ketone_title)
            .setMessage(R.string.dka_ketone_message)
            .setView(content)
            .setPositiveButton(R.string.dka_ketone_save) { _, _ ->
                val unit = selectedUnit()
                // Accept "1,2" as well as "1.2" (German keyboard)
                val entered = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val mmol = entered?.let { unit.toMmol(it) }
                if (mmol == null || mmol < 0.0 || mmol > MAX_KETONE_MMOL) {
                    Toast.makeText(this, R.string.dka_ketone_invalid, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    DkaKetoneStore.saveUnit(this, unit)
                    DkaKetoneStore.save(this, mmol)
                    showResult(mmol)
                }
            }
            .setNeutralButton(R.string.dka_ketone_clear) { _, _ ->
                DkaKetoneStore.clear(this)
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Immediate, loop-independent guidance for the entered value. */
    private fun showResult(mmol: Double) {
        val msg = when {
            mmol >= 3.0 -> R.string.dka_ketone_result_critical
            mmol >= 1.5 -> R.string.dka_ketone_result_warning
            mmol >= 0.6 -> R.string.dka_ketone_result_watch
            else -> R.string.dka_ketone_result_ok
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dka_ketone_result_title, mmol, KetoneUnit.MGDL.fromMmol(mmol)))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _, _ -> setResult(Activity.RESULT_OK); finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
