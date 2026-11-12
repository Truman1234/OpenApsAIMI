package app.aaps.pump.dana

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DanaPumpTest : TestBaseWithProfile() {

    private lateinit var sut: DanaPump

    @BeforeEach
    fun setup() {
        sut = DanaPump(aapsLogger, preferences, dateUtil, decimalFormatter, profileStoreProvider)
    }

    @Test
    fun detectDanaRS() {
        sut.hwModel = 0x05
        assertThat(sut.modelFriendlyName()).contains("DanaRS")
    }

    // ---- applyReadSteps (#946): the pump type says 0.05 U bolus and 0.01 U basal, this pump is set to 0.1 ----

    /** What the step messages of the pump do. */
    private fun readStepsFromPump() {
        sut.bolusStep = 0.1
        sut.basalStep = 0.1
    }

    private fun descriptionWithReadSteps(): PumpDescription = sut.applyReadSteps(PumpDescription().fillFor(PumpType.DANA_RS))

    @Test
    fun theStepsThePumpReportedGoIntoTheDescription() {
        readStepsFromPump()

        assertThat(descriptionWithReadSteps().bolusStep).isEqualTo(0.1)
        assertThat(descriptionWithReadSteps().basalStep).isEqualTo(0.1)
    }

    @Test
    fun beforeTheStepsAreReadThePumpTypeStepsStay() {
        // The messages with the steps did not come yet: the starting 0.1 is not a reading.
        assertThat(sut.bolusStep).isEqualTo(0.1)
        assertThat(sut.bolusStepRead).isFalse()

        assertThat(descriptionWithReadSteps().bolusStep).isEqualTo(0.05)
        assertThat(descriptionWithReadSteps().basalStep).isEqualTo(0.01)
    }

    /** A reset goes back to the starting 0.1, and that is not a reading, not even for a moment. */
    @Test
    fun afterAResetThePumpTypeStepsStay() {
        readStepsFromPump()
        sut.reset()

        assertThat(sut.bolusStep).isEqualTo(0.1)
        assertThat(sut.basalStep).isEqualTo(0.1)
        assertThat(sut.bolusStepRead).isFalse()
        assertThat(sut.basalStepRead).isFalse()
        assertThat(descriptionWithReadSteps().bolusStep).isEqualTo(0.05)
        assertThat(descriptionWithReadSteps().basalStep).isEqualTo(0.01)
    }

    /** A step of 0 cannot be right, and as a step it would floor every bolus to nothing. */
    @Test
    fun aStepOfZeroIsNotUsed() {
        readStepsFromPump()
        sut.bolusStep = 0.0
        sut.basalStep = 0.0

        assertThat(descriptionWithReadSteps().bolusStep).isEqualTo(0.05)
        assertThat(descriptionWithReadSteps().basalStep).isEqualTo(0.01)
    }
    // ---- createConvertedProfile: the profile that is sent to the profile store ----

    /** Runs the conversion and returns the JSON that the profile store got. */
    private fun convertedJson(configure: DanaPump.() -> Unit): JsonObject {
        val store = mock<ProfileStore>()
        val captor = argumentCaptor<JsonObject>()
        whenever(store.with(captor.capture())).thenReturn(store)
        val pump = DanaPump(aapsLogger, preferences, dateUtil, decimalFormatter) { store }
        // 48 values per profile, because the half hour variant reads that many
        pump.pumpProfiles = Array(4) { Array(48) { 0.5 } }
        pump.configure()
        pump.createConvertedProfile()
        return captor.firstValue
    }

    private fun JsonObject.profile(): JsonObject = this["store"]!!.jsonObject["DanaR-1"]!!.jsonObject

    private fun JsonObject.points(name: String): JsonArray = this[name]!!.jsonArray

    @Test
    fun profileWithFourPartsHasFivePointsForCarbRatioAndSens() {
        val json = convertedJson {
            hwModel = 0x05
            nightCIR = 10; morningCIR = 11; afternoonCIR = 12; eveningCIR = 13
            nightCF = 50.0; morningCF = 51.0; afternoonCF = 52.0; eveningCF = 53.0
        }

        assertThat(json["defaultProfile"]!!.jsonPrimitive.content).isEqualTo("DanaR-1")
        val carbs = json.profile().points("carbratio")
        assertThat(carbs.map { it.jsonObject["time"]!!.jsonPrimitive.content }).containsExactly("00:00", "06:00", "11:00", "14:00", "22:00").inOrder()
        assertThat(carbs.map { it.jsonObject["value"]!!.jsonPrimitive.intOrNull }).containsExactly(10, 11, 12, 13, 10).inOrder()
        val sens = json.profile().points("sens")
        assertThat(sens.map { it.jsonObject["timeAsSeconds"]!!.jsonPrimitive.intOrNull }).containsExactly(0, 6 * 3600, 11 * 3600, 17 * 3600, 22 * 3600).inOrder()
        assertThat(sens.map { it.jsonObject["value"]!!.jsonPrimitive.doubleOrNull }).containsExactly(50.0, 51.0, 52.0, 53.0, 50.0).inOrder()
    }

    @Test
    fun profileOfAPumpWith24ValuesHasOnePointForEveryHour() {
        val json = convertedJson {
            hwModel = 0x07
            for (i in 0..23) {
                cir24[i] = 20.0 + i
                cf24[i] = 100.0 + i
            }
        }

        val carbs = json.profile().points("carbratio")
        assertThat(carbs).hasSize(24)
        assertThat(carbs[7].jsonObject["time"]!!.jsonPrimitive.content).isEqualTo("07:00")
        assertThat(carbs[7].jsonObject["timeAsSeconds"]!!.jsonPrimitive.intOrNull).isEqualTo(7 * 3600)
        assertThat(carbs[7].jsonObject["value"]!!.jsonPrimitive.doubleOrNull).isEqualTo(27.0)
        assertThat(json.profile().points("sens")[23].jsonObject["value"]!!.jsonPrimitive.doubleOrNull).isEqualTo(123.0)
    }

    @Test
    fun basalHasHourlyOr48HalfHourPoints() {
        val hourly = convertedJson { hwModel = 0x05 }.profile().points("basal")
        assertThat(hourly).hasSize(24)
        assertThat(hourly[1].jsonObject["time"]!!.jsonPrimitive.content).isEqualTo("01:00")

        val halfHour = convertedJson { basal48Enable = true }.profile().points("basal")
        assertThat(halfHour).hasSize(48)
        assertThat(halfHour[3].jsonObject["time"]!!.jsonPrimitive.content).isEqualTo("01:30")
        assertThat(halfHour[3].jsonObject["timeAsSeconds"]!!.jsonPrimitive.intOrNull).isEqualTo(3 * 1800)
    }

    @Test
    fun profileHasUnitsAndOneTarget() {
        val json = convertedJson { currentTarget = 5.5 }

        assertThat(json.profile()["units"]).isEqualTo(JsonPrimitive("mg/dl"))
        assertThat(json.profile().points("target_low")).hasSize(1)
        assertThat(json.profile().points("target_high")[0].jsonObject["value"]!!.jsonPrimitive.doubleOrNull).isEqualTo(5.5)
    }
}
