package com.example.sleepmonitorsync.band.activity

import com.example.sleepmonitorsync.AppVersion
import com.example.sleepmonitorsync.VersionedLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real awakenings versus turning over in bed.
 *
 * The sleep file carries stage packets (type 0x11): a sequence of 2-byte pairs
 * `[stage][minutes]` laid end to end from bedtime (stage high nibble 0 = awake, 1 and 2 = sleep
 * stages). Verified on a real capture (v103, night 25->26.11): the minutes add up to
 * (wake - bed) / 60 and the number of awake pairs equals the wake count of the type 0x10 summary.
 * The file holds several revisions of the night; the longest packet is the final one.
 *
 * An awakening counts as "got up" only when the band recorded at least [MIN_STEPS] steps within
 * [WINDOW_SECONDS] after it started; fewer steps means the sleeper just turned over.
 */
object SleepAwakenings {

    const val MIN_STEPS = 10
    const val WINDOW_SECONDS = 120

    private val TAG = AppVersion.logTag("SleepAwakenings")

    data class Awakening(val startSeconds: Int, val durationMinutes: Int)

    data class Decision(val awakening: Awakening, val steps: Int, val counted: Boolean)

    enum class Source {
        /** Awakenings with fewer than [MIN_STEPS] steps were dropped. */
        STEP_FILTER,

        /** The stage list could not be decoded consistently: the band's own wake count is used. */
        BAND_WAKE_COUNT_NO_STAGES,

        /**
         * This sync fetched no minute sample inside the night (bed..wake), so "no steps" cannot be
         * told from "no data": the band's own wake count is used.
         */
        BAND_WAKE_COUNT_NO_MINUTE_DATA,
    }

    data class Evaluation(val count: Int, val source: Source, val decisions: List<Decision>)

    /**
     * Decodes the awakenings of one night from its stage payloads, or returns null when the data
     * is not self-consistent (so the caller falls back to the band's wake count).
     */
    fun decode(
        bedTimeSeconds: Int,
        wakeupTimeSeconds: Int,
        wakeCount: Int,
        stagePayloads: List<ByteArray>,
    ): List<Awakening>? {
        fun totalMinutes(payload: ByteArray): Int =
            (1 until payload.size step 2).sumOf { payload[it].toInt() and 0xFF }

        val payload = stagePayloads
            .filter { it.size >= 2 && it.size % 2 == 0 }
            .maxByOrNull { totalMinutes(it) } ?: return null

        if (wakeupTimeSeconds <= bedTimeSeconds) return null
        val expectedMinutes = (wakeupTimeSeconds.toLong() - bedTimeSeconds.toLong()) / 60L
        if (kotlin.math.abs(totalMinutes(payload) - expectedMinutes) > 1L) return null

        val awakenings = mutableListOf<Awakening>()
        var offsetMinutes = 0
        for (i in payload.indices step 2) {
            val stage = payload[i].toInt() and 0xFF
            val minutes = payload[i + 1].toInt() and 0xFF
            if ((stage and 0xF0) == 0 && minutes > 0) {
                awakenings += Awakening(bedTimeSeconds + offsetMinutes * 60, minutes)
            }
            offsetMinutes += minutes
        }
        return if (awakenings.size == wakeCount) awakenings else null
    }

    /** Steps recorded from the start of each awakening up to [WINDOW_SECONDS] after it. */
    fun evaluateSteps(awakenings: List<Awakening>, samples: Collection<ActivitySample>): List<Decision> =
        awakenings.map { awakening ->
            val end = awakening.startSeconds + WINDOW_SECONDS
            val steps = samples
                .filter { it.timestampSeconds in awakening.startSeconds..end }
                .sumOf { it.steps ?: 0 }
            Decision(awakening, steps, steps >= MIN_STEPS)
        }

    fun evaluate(sleep: SleepDetailsParser.SleepSummary, samples: Collection<ActivitySample>): Evaluation {
        val awakenings = sleep.awakenings
        if (awakenings == null) return Evaluation(sleep.wakeCount, Source.BAND_WAKE_COUNT_NO_STAGES, emptyList())
        val nightSamples = samples.count { it.timestampSeconds in sleep.bedTimeSeconds..sleep.wakeupTimeSeconds }
        if (nightSamples == 0) return Evaluation(sleep.wakeCount, Source.BAND_WAKE_COUNT_NO_MINUTE_DATA, emptyList())
        val decisions = evaluateSteps(awakenings, samples)
        return Evaluation(decisions.count { it.counted }, Source.STEP_FILTER, decisions)
    }

    /** The number of awakenings to upload for [sleep]; logs how it was reached. */
    fun resolve(sleep: SleepDetailsParser.SleepSummary, samples: Collection<ActivitySample>): Int {
        val evaluation = evaluate(sleep, samples)
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        evaluation.decisions.forEach { d ->
            VersionedLog.i(
                TAG,
                "Awakening ${time.format(Date(d.awakening.startSeconds * 1000L))} (${d.awakening.durationMinutes} min): " +
                    "steps=${d.steps} within ${WINDOW_SECONDS}s -> " +
                    (if (d.counted) "got up" else "turned over")
            )
        }
        VersionedLog.i(
            TAG,
            "Awakenings: band wakeCount=${sleep.wakeCount}, counted=${evaluation.count}, source=${evaluation.source}, " +
                "minuteSamples=${samples.size}, inNight=${samples.count { it.timestampSeconds in sleep.bedTimeSeconds..sleep.wakeupTimeSeconds }}"
        )
        return evaluation.count
    }
}
