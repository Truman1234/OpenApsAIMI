package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.formatDeviceAge

/** How far back a mode note can start and still be running: the longest mode (a custom one from the watch) is 12 h. */
private const val ACTIVE_MODE_LOOKBACK_MS = 12 * 60 * 60 * 1000L

/**
 * The AIMI meal or activity mode that runs now, as "Mode: Lunch (45 min)", or null when none runs.
 *
 * It is the newest note of the last 12 h that names a mode (`AimiDashboardRules.modeForNote`, the same
 * keywords as DEV) and has time left. Every home (AIMI dashboard, Glass, Essential, Overview, Dashboard
 * V2) shows this one line, so they can never disagree. A new home should show it too.
 */
internal suspend fun aimiActiveModeLine(persistenceLayer: PersistenceLayer, rh: TextResolver, now: Long): String? {
    val events = persistenceLayer.getTherapyEventDataFromTime(now - ACTIVE_MODE_LOOKBACK_MS, TE.Type.NOTE, false)
    // A mode that started before the newest stop note is over at once. AIMI deletes those notes only at
    // its next loop run, up to 5 min later. The watch uses the same rule (`RemoteControlCommands.activeModes`).
    val lastStop = events.filter { AimiDashboardRules.isStopNote(it.note) }.maxOfOrNull { it.timestamp }
    val (event, mode) = events
        .filter { lastStop == null || it.timestamp > lastStop }
        .mapNotNull { event ->
            val mode = AimiDashboardRules.modeForNote(event.note) ?: return@mapNotNull null
            if (event.timestamp + event.duration - now <= 0) null else event to mode
        }
        .maxByOrNull { it.first.timestamp } ?: return null
    val remaining = event.timestamp + event.duration - now
    return rh.gs(UiStrings.dashboard_adjustment_mode, rh.gs(mode), formatDeviceAge(rh, remaining))
}

/**
 * The temp target that runs now, as "Temp target: 110 mg/dL (45m)", or null when none runs. Shown under
 * the mode line on the homes that draw [DashboardStatusLines].
 */
internal suspend fun activeTempTargetLine(
    persistenceLayer: PersistenceLayer,
    rh: TextResolver,
    profileUtil: ProfileUtil,
    now: Long,
): String? {
    val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(now) ?: return null
    val remaining = tempTarget.end - now
    if (remaining <= 0) return null
    return rh.gs(
        UiStrings.dashboard_temp_target_line,
        profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL),
        profileUtil.unitLabel,
        formatDeviceAge(rh, remaining),
    )
}

/**
 * The running AIMI mode and the running temp target, one line each, as the Overview, Dashboard V2,
 * Essential and AIMI homes draw them above their glucose graph. A null line is left out; with both null
 * nothing is drawn. Each line has a switch in Appearance > Home screen lines
 * (`BooleanKey.OverviewShowAimiModeLine`, `BooleanKey.OverviewShowTempTargetLine`); the view models pass
 * null when it is off. Glass draws the same texts in its own chart header.
 */
@Composable
internal fun DashboardStatusLines(modeText: String?, tempTargetLine: String?, modifier: Modifier = Modifier) {
    if (modeText == null && tempTargetLine == null) return
    Column(modifier = modifier) {
        modeText?.let { StatusLineText(it, AapsTheme.generalColors.adjusted) }
        tempTargetLine?.let { StatusLineText(it, AapsTheme.generalColors.inProgress) }
    }
}

@Composable
private fun StatusLineText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
