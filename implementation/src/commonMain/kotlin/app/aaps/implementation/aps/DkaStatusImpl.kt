package app.aaps.implementation.aps

import app.aaps.core.interfaces.aps.DkaStatus
import app.aaps.core.interfaces.aps.DkaStatusSnapshot
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.Volatile

/** Keeps the newest [DkaStatusSnapshot] in memory. It is not stored: after a restart the next loop run writes it again. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class DkaStatusImpl : DkaStatus {

    // Written on the loop thread, read on the watch thread. One reference, so @Volatile is enough.
    @Volatile
    private var last: DkaStatusSnapshot? = null

    override fun update(snapshot: DkaStatusSnapshot) {
        last = snapshot
    }

    override fun current(nowMs: Long, maxAgeMs: Long): DkaStatusSnapshot? =
        last?.takeIf { nowMs - it.timestampMs in 0..maxAgeMs }
}
