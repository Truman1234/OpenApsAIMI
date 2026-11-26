package app.aaps.core.interfaces.aps

/**
 * The last result of the AIMI DKA guard, for places that only show it: the watch status and the
 * confirmation of a running mode that lowers insulin.
 *
 * The guard writes it once per loop run (`AimiDkaCoordinator.process`). Nothing here changes what the
 * loop does. With another APS plugin, or before the first AIMI loop run, nothing is written and
 * [current] returns null.
 */
interface DkaStatus {

    /** Stores the newest result. Called by the DKA guard. */
    fun update(snapshot: DkaStatusSnapshot)

    /**
     * The newest result, or null when there is none or it is older than [maxAgeMs]. A stopped loop or
     * another APS plugin must not leave an old risk on the watch, so old results count as unknown.
     */
    fun current(nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): DkaStatusSnapshot?

    companion object {

        /** Three loop runs. After that the result is too old to show. */
        const val DEFAULT_MAX_AGE_MS: Long = 15L * 60 * 1000
    }
}

/**
 * One result of the DKA guard.
 *
 * @param risk the name of the guard's risk level: `NONE`, `WATCH`, `WARNING` or `CRITICAL`. A string,
 *   because the level type lives in `plugins:aps`, which `core:interfaces` must not depend on.
 * @param reason the guard's reasons, already readable text; null when there are none.
 * @param timestampMs when the guard ran.
 */
data class DkaStatusSnapshot(
    val risk: String,
    val reason: String?,
    val timestampMs: Long,
) {

    /** True for `WATCH`, `WARNING` and `CRITICAL`. */
    val isRaised: Boolean get() = risk != RISK_NONE

    companion object {

        const val RISK_NONE: String = "NONE"
    }
}
