package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
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
    val (event, mode) = events
        .mapNotNull { event ->
            val mode = AimiDashboardRules.modeForNote(event.note) ?: return@mapNotNull null
            if (event.timestamp + event.duration - now <= 0) null else event to mode
        }
        .maxByOrNull { it.first.timestamp } ?: return null
    val remaining = event.timestamp + event.duration - now
    return rh.gs(UiStrings.dashboard_adjustment_mode, rh.gs(mode), formatDeviceAge(rh, remaining))
}

/**
 * The mode line as the Overview, Dashboard V2 and Essential homes draw it, above their glucose graph.
 * Glass draws it in its own chart header.
 */
@Composable
internal fun AimiActiveModeText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = AapsTheme.generalColors.adjusted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
