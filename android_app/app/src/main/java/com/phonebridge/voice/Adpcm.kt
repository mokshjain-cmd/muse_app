package com.phonebridge.voice

/**
 * IMA ADPCM, matching firmware/src/audio.c. One BLE packet:
 *   [kind u8][seq u8][predictor i16 LE][step index u8][nibbles, low first]
 * kind 0x01 = mic (board -> phone), 0x02 = speaker (phone -> board).
 */
object Adpcm {
    const val KIND_MIC = 0x01
    const val KIND_SPEAKER = 0x02
    const val HEADER = 5
    const val RATE = 16000

    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )
    private val INDEX_ADJ = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    class State(var predictor: Int = 0, var index: Int = 0)

    private fun clamp(s: State) {
        s.predictor = s.predictor.coerceIn(-32768, 32767)
        s.index = s.index.coerceIn(0, 88)
    }

    fun encodeSample(s: State, sample: Int): Int {
        var step = STEP[s.index]
        var diff = sample - s.predictor
        var code = 0
        if (diff < 0) { code = 8; diff = -diff }
        var delta = step shr 3
        if (diff >= step) { code = code or 4; diff -= step; delta += step }
        step = step shr 1
        if (diff >= step) { code = code or 2; diff -= step; delta += step }
        step = step shr 1
        if (diff >= step) { code = code or 1; delta += step }
        s.predictor += if (code and 8 != 0) -delta else delta
        s.index += INDEX_ADJ[code]
        clamp(s)
        return code
    }

    fun decodeSample(s: State, code: Int): Short {
        val step = STEP[s.index]
        var delta = step shr 3
        if (code and 4 != 0) delta += step
        if (code and 2 != 0) delta += step shr 1
        if (code and 1 != 0) delta += step shr 2
        s.predictor += if (code and 8 != 0) -delta else delta
        s.index += INDEX_ADJ[code]
        clamp(s)
        return s.predictor.toShort()
    }

    /** Decodes one packet; returns null if it isn't of [kind]. */
    fun decodePacket(pkt: ByteArray, kind: Int = KIND_MIC): ShortArray? {
        if (pkt.size <= HEADER || (pkt[0].toInt() and 0xFF) != kind) return null
        val st = State(
            ((pkt[2].toInt() and 0xFF) or (pkt[3].toInt() shl 8)).toShort().toInt(),
            (pkt[4].toInt() and 0xFF).coerceAtMost(88),
        )
        val out = ShortArray((pkt.size - HEADER) * 2)
        var n = 0
        for (i in HEADER until pkt.size) {
            val b = pkt[i].toInt() and 0xFF
            out[n++] = decodeSample(st, b and 0x0F)
            out[n++] = decodeSample(st, b shr 4)
        }
        return out
    }

    /** Splits PCM into self-contained packets of at most [maxPacket] bytes. */
    fun encodePackets(pcm: ShortArray, maxPacket: Int, kind: Int = KIND_SPEAKER, seqStart: Int = 0): List<ByteArray> {
        val samplesPer = (maxPacket - HEADER) * 2
        val st = State()
        val out = ArrayList<ByteArray>()
        var seq = seqStart
        var i = 0
        while (i < pcm.size) {
            val n = minOf(samplesPer, pcm.size - i) and 1.inv() // even
            if (n == 0) break
            val pkt = ByteArray(HEADER + n / 2)
            pkt[0] = kind.toByte()
            pkt[1] = (seq++ and 0xFF).toByte()
            pkt[2] = (st.predictor and 0xFF).toByte()
            pkt[3] = ((st.predictor shr 8) and 0xFF).toByte()
            pkt[4] = st.index.toByte()
            for (j in 0 until n step 2) {
                val lo = encodeSample(st, pcm[i + j].toInt())
                val hi = encodeSample(st, pcm[i + j + 1].toInt())
                pkt[HEADER + j / 2] = (lo or (hi shl 4)).toByte()
            }
            out += pkt
            i += n
        }
        return out
    }
}
