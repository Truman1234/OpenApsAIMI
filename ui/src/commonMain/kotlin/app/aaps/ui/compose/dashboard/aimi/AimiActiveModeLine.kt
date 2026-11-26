package app.aaps.ui.compose.dashboard.aimi

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.formatDeviceAge

/** How far back a mode note can start and still be running: the longest mode (sleep) is 8 h. */
private const val ACTIVE_MODE_LOOKBACK_MS = 12 * 60 * 60 * 1000L

/**
 * The AIMI meal or activity mode that runs now, as "Mode: Lunch (45 min)", or null when none runs.
 *
 * It is the newest note of the last 12 h that names a mode (`AimiDashboardRules.modeForNote`, the same
 * keywords as DEV) and has time left. The AIMI dashboard and the Glass home show this one line, so the
 * two can never disagree.
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
