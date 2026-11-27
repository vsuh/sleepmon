package com.example.sleepmonitorsync.band.activity

import com.example.sleepmonitorsync.band.activity.SleepAwakenings.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real stage packets of the night 25->26.11.2026 captured with v103: bed 01:16, wake 07:53
 * (397 minutes), four awake segments of 4, 4, 4 and 1 minutes.
 */
class SleepAwakeningsTest {

    private val bed = 1795644960
    private val wake = 1795668780

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val stage3 = hex("1026202400042016103400041075") // snapshot with 2 awakenings, 273 min
    private val stage4 = hex("10152003100E2017100D0004201610340004101C201F103A0004103A") // 3 awakenings, 335 min
    private val stage5 = hex("102E200F100D00042016102020140004101C2009104C200400042010102A0001103D") // 4, 397 min

    private fun night(awakenings: List<SleepAwakenings.Awakening>?, wakeCount: Int = 4) =
        SleepDetailsParser.SleepSummary(bed, wake, 384, wakeCount, awakenings = awakenings)

    private fun sample(offsetMinutes: Int, steps: Int?, plusSeconds: Int = 0) =
        ActivitySample(bed + offsetMinutes * 60 + plusSeconds, steps)

    private val awakenings get() = SleepAwakenings.decode(bed, wake, 4, listOf(stage3, stage4, stage5))!!

    @Test
    fun decodesAwakeningsFromTheLongestStagePacket() {
        val result = SleepAwakenings.decode(bed, wake, 4, listOf(ByteArray(0), stage3, stage4, stage5))
        assertNotNull(result)
        assertEquals(listOf(74, 152, 273, 335), result!!.map { (it.startSeconds - bed) / 60 })
        assertEquals(listOf(4, 4, 4, 1), result.map { it.durationMinutes })
    }

    @Test
    fun inconsistentStageDataIsRejected() {
        assertNull(SleepAwakenings.decode(bed, wake, 3, listOf(stage5))) // wake count differs
        assertNull(SleepAwakenings.decode(bed, wake, 3, listOf(stage4))) // minutes do not add up to the night
        assertNull(SleepAwakenings.decode(bed, wake, 4, emptyList()))
        assertNull(SleepAwakenings.decode(bed, wake, 4, listOf(hex("102E20")))) // odd length
        assertNull(SleepAwakenings.decode(bed, bed, 4, listOf(stage5))) // wake not after bed
    }

    @Test
    fun noStepsMeansTurnedOver() {
        val evaluation = SleepAwakenings.evaluate(night(awakenings), listOf(sample(0, 0)))
        assertEquals(0, evaluation.count)
        assertEquals(Source.STEP_FILTER, evaluation.source)
    }

    @Test
    fun tenStepsDuringTheAwakeningCountAsGotUp() {
        val evaluation = SleepAwakenings.evaluate(
            night(awakenings),
            listOf(sample(74, 4), sample(75, 3), sample(77, 3), sample(152, 9), sample(159, 50), sample(273, 5)),
        )
        assertEquals(1, evaluation.count)
        assertTrue(evaluation.decisions[0].counted)
        assertEquals(10, evaluation.decisions[0].steps) // 74..78 min is the segment, steps at +3 min are inside
        assertFalse(evaluation.decisions[1].counted) // 9 steps; the 50 come after the segment end + 2 min
        assertFalse(evaluation.decisions[2].counted)
    }

    @Test
    fun windowEndsTwoMinutesAfterTheSegmentEnd() {
        val first = awakenings[1] // starts at +152 min, lasts 4 min
        val atTheEdge = SleepAwakenings.evaluateSteps(listOf(first), listOf(sample(158, 10)))
        assertTrue(atTheEdge[0].counted) // exactly segment end + 120 s
        val pastTheEdge = SleepAwakenings.evaluateSteps(listOf(first), listOf(sample(159, 10)))
        assertFalse(pastTheEdge[0].counted)
    }

    @Test
    fun gettingUpLateInALongAwakeningIsCounted() {
        // Night 26->27.11: awake segment 05:05-05:28 (23 min); the real getting-up was at 05:22.
        val long = SleepAwakenings.Awakening(bed + 300 * 60, 23)
        val gotUp = SleepAwakenings.evaluateSteps(listOf(long), listOf(sample(317, 12), sample(318, 8)))
        assertEquals(20, gotUp[0].steps)
        assertTrue(gotUp[0].counted)
        val lyingAwake = SleepAwakenings.evaluateSteps(listOf(long), listOf(sample(330, 40)))
        assertFalse(lyingAwake[0].counted) // steps long after the segment are not part of it
    }

    @Test
    fun unknownStepsAreIgnored() {
        val evaluation = SleepAwakenings.evaluate(night(awakenings), listOf(sample(335, null), sample(335, 30, 60)))
        assertEquals(1, evaluation.count)
        assertTrue(evaluation.decisions[3].counted)
    }

    @Test
    fun fallsBackToBandWakeCountWithoutUsableData() {
        val noMinuteData = SleepAwakenings.evaluate(night(awakenings), emptyList())
        assertEquals(4, noMinuteData.count)
        assertEquals(Source.BAND_WAKE_COUNT_NO_MINUTE_DATA, noMinuteData.source)

        val outsideTheNight = SleepAwakenings.evaluate(
            night(awakenings),
            listOf(ActivitySample(bed - 3600, 20), ActivitySample(wake + 600, 8)),
        )
        assertEquals(4, outsideTheNight.count)
        assertEquals(Source.BAND_WAKE_COUNT_NO_MINUTE_DATA, outsideTheNight.source)

        val noStages = SleepAwakenings.evaluate(night(null), listOf(sample(74, 50)))
        assertEquals(4, noStages.count)
        assertEquals(Source.BAND_WAKE_COUNT_NO_STAGES, noStages.source)
    }

    @Test
    fun nightWithoutAwakeningsGivesZero() {
        val evaluation = SleepAwakenings.evaluate(night(emptyList(), wakeCount = 0), listOf(sample(0, 0)))
        assertEquals(0, evaluation.count)
        assertEquals(Source.STEP_FILTER, evaluation.source)
    }
}
