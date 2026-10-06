package com.phonebridge.voice

import com.phonebridge.muse.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class VoiceTest {

    private fun tone(n: Int, hz: Double = 440.0, amp: Double = 12000.0) =
        ShortArray(n) { (amp * sin(2 * PI * hz * it / Adpcm.RATE)).toInt().toShort() }

    @Test
    fun adpcm_roundTripKeepsSpeechQuality() {
        val pcm = tone(16000)
        val packets = Adpcm.encodePackets(pcm, 244, Adpcm.KIND_MIC)
        // 244-byte packets carry (244 - 5) * 2 = 478 samples each.
        assertEquals(5 + 239, packets.first().size)
        val decoded = packets.flatMap { Adpcm.decodePacket(it, Adpcm.KIND_MIC)!!.toList() }
        assertTrue(decoded.size >= pcm.size - 478)

        var sig = 0.0
        var noise = 0.0
        for (i in decoded.indices) {
            sig += pcm[i].toDouble() * pcm[i]
            val e = pcm[i] - decoded[i]
            noise += e.toDouble() * e
        }
        val snr = 10 * log10(sig / noise)
        assertTrue("SNR $snr dB too low", snr > 20)
    }

    @Test
    fun adpcm_packetsAreSelfContained() {
        val packets = Adpcm.encodePackets(tone(4000), 100, Adpcm.KIND_SPEAKER)
        // Decoding a later packet alone must still work (state is carried in its header).
        val alone = Adpcm.decodePacket(packets[3], Adpcm.KIND_SPEAKER)!!
        assertEquals((100 - 5) * 2, alone.size)
        assertEquals(null, Adpcm.decodePacket(packets[3], Adpcm.KIND_MIC))
        assertEquals(3, packets[3][1].toInt())
    }

    @Test
    fun resample_changesLength() {
        assertEquals(16000, Wav.resample(ShortArray(22050), 22050, 16000).size)
        assertEquals(16000, Wav.resample(ShortArray(24000), 24000, 16000).size)
    }

    @Test
    fun wavHeader_matchesEsp32Client() {
        val h = Conversation.wavHeader(16000)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals("data", String(h, 36, 4))
        assertEquals(0xFF.toByte(), h[40]) // streaming "unknown" size
        assertEquals(16000, (h[24].toInt() and 0xFF) or ((h[25].toInt() and 0xFF) shl 8))
    }
}
