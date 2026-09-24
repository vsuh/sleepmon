package com.example.sleepmonitorsync.band.activity

/**
 * Reads the header-driven, variable-width bit-packed "group" format used by Xiaomi's
 * per-minute activity records (steps/HR/spO2/stress/etc). Validated byte-for-byte
 * against real captured device data (see 10-projects/sleep-monitor/task.md, "Этап B"
 * entries) - 654 consecutive per-minute samples decoded with zero errors, landing
 * exactly on the expected file boundary.
 *
 * Format: a fixed-size header (6 bytes for file version 4) holds one 4-bit nibble per
 * "group" (up to 12 groups). Per sample, groups are read in a fixed order; each
 * nibble's top bit says whether this group is present in THIS sample at all (if not,
 * the group contributes nothing and no bits are consumed from the data stream); the
 * bottom 3 bits say which of up to 3 sub-fields packed into that group's value are
 * present, from most-significant end inward.
 *
 * Ported from Gadgetbridge's XiaomiComplexActivityParser
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge, AGPLv3).
 */
class XiaomiComplexActivityParser(
    private val header: ByteArray,
    private val data: ByteArray,
    startPos: Int,
) {
    var pos: Int = startPos
        private set

    private var currentGroup = -1
    private var currentGroupBits = 0
    private var currentVal = 0L

    fun reset() {
        currentGroup = -1
        currentGroupBits = 0
        currentVal = 0
    }

    private fun nibble(): Int {
        val hb = currentGroup / 2
        val byte = header[hb].toInt() and 0xFF
        return if (currentGroup % 2 == 0) (byte shr 4) and 0xF else byte and 0xF
    }

    private fun consume(bits: Int): Long = when (bits) {
        8 -> (data[pos++].toLong() and 0xFF)
        16 -> {
            val v = ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)).toLong()
            pos += 2
            v
        }
        32 -> {
            val v = ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or ((data[pos + 3].toInt() and 0xFF) shl 24)).toLong()
            pos += 4
            v
        }
        else -> throw IllegalArgumentException("Unsupported bit width: $bits")
    }

    /**
     * Advances to the next group (of [bits] width), consuming its raw value from the
     * data stream if the header says this group is present for this sample.
     * @return true if the group is present (and its value/sub-fields can now be read via [has]/[get])
     */
    fun nextGroup(bits: Int): Boolean {
        currentGroup++
        if (currentGroup >= header.size * 2) return false
        if ((nibble() and 0x8) == 0) return false
        currentGroupBits = bits
        currentVal = consume(bits)
        return true
    }

    /** Whether sub-field [idx] (0=first, 1=second, 2=third) is present within the current group's value. */
    fun has(idx: Int): Boolean = (nibble() and (1 shl (2 - idx))) != 0

    /** Extracts an [nBits]-wide sub-field starting at bit-offset [idx] from the MSB side of the current group's value. */
    fun get(idx: Int, nBits: Int): Int {
        val shift = currentGroupBits - idx - nBits
        val mask = (1L shl nBits) - 1
        return ((currentVal ushr shift) and mask).toInt()
    }
}
