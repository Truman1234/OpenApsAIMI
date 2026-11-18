package app.aaps.plugins.aps.openAPSAIMI.dka

import app.aaps.core.keys.interfaces.Preferences

/**
 * Writes a stored DKA basal floor percentage back into the range the key now allows.
 *
 * `AimiDkaIntKey.FloorPercent` used to allow 10 to 100. It now allows
 * [SemaglutideBasalFloor.MIN_PERCENT] to [SemaglutideBasalFloor.MAX_PERCENT], because a floor of
 * 100 % stops the loop ever going below the scheduled basal, which blocks hypo protection.
 *
 * A phone that ran an older build, or a backup made by one, can still hold a value above the new
 * maximum. [SemaglutideBasalFloor.apply] clamps what it uses, so dosing was never wrong, but the
 * stored value stayed higher than anything the loop would act on: the settings screen showed one
 * number and the loop used another. This writes the stored value down so the two agree.
 *
 * Guarded by its data alone, so it is safe to run on every start: a value already in range is left
 * alone, and writing [SemaglutideBasalFloor.MAX_PERCENT] again changes nothing. That is why it needs
 * no "done" flag, unlike `AimiMealFactorScaleRepair` - and why the AIMI plugin start is enough,
 * on Android and on iOS, with no preference-migration hook. A backup imported later is corrected by
 * the next start.
 *
 * Only the floor percentage can be out of range this way. `FloorDkaMinPercent` was added with its
 * current range and no older build ever stored it.
 */
object AimiDkaFloorPercentMigration {

    /** What was stored and what it became, for the log. */
    class Capped(val storedValue: Int, val newValue: Int) {

        override fun toString(): String = "${AimiDkaIntKey.FloorPercent.key}: $storedValue -> $newValue"
    }

    /**
     * Caps the stored value if it is above the maximum.
     *
     * The maximum comes from the key itself, which is what the settings screen offers, so the two
     * cannot drift apart. `SemaglutideBasalFloorTest` checks that it still matches
     * [SemaglutideBasalFloor.MAX_PERCENT], the bound the dosing path clamps with.
     *
     * @return what changed, or null when nothing was stored or the stored value was already in range
     */
    fun capStored(preferences: Preferences): Capped? {
        val max = AimiDkaIntKey.FloorPercent.max
        val stored = preferences.getIfExists(AimiDkaIntKey.FloorPercent) ?: return null
        if (stored <= max) return null
        preferences.put(AimiDkaIntKey.FloorPercent, max)
        return Capped(stored, max)
    }
}
