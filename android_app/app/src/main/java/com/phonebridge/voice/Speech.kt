package com.phonebridge.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Speaks Muse's replies. Muse answers gadgets in text, so the phone turns the
 * text into speech with Android's TextToSpeech and either plays it itself or
 * streams it to the board's speaker as ADPCM.
 */
class Speech(
    context: Context,
    private val scope: CoroutineScope,
    private val board: Board,
    private val log: (String) -> Unit,
) {
    enum class Output { BOARD, PHONE, OFF }

    interface Board {
        val audioPayload: Int
        fun sendLine(json: String): Boolean
        fun sendAudio(packet: ByteArray): Boolean
        fun onSpeaking(active: Boolean)
    }

    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    /** Queued items: a String is spoken, a ShortArray (16 kHz mono PCM) is played on the board. */
    private val queue = Channel<Any>(Channel.UNLIMITED)
    private val pending = HashMap<String, CompletableDeferred<Boolean>>()
    private var boardDone: CompletableDeferred<Unit>? = null
    private var worker: Job? = null
    @Volatile var output = Output.BOARD

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { synchronized(pending) { pending.remove(id) }?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { synchronized(pending) { pending.remove(id) }?.complete(false) }
        })
        worker = scope.launch {
            for (item in queue) when (item) {
                is String -> speakNow(item)
                is ShortArray -> streamToBoard(item)
            }
        }
    }

    fun speak(text: String) {
        if (output != Output.OFF && text.isNotBlank()) queue.trySend(text)
    }

    /** Plays 16 kHz mono PCM on the board's speaker, after anything already queued. */
    fun playOnBoard(pcm: ShortArray) {
        if (pcm.isNotEmpty()) queue.trySend(pcm)
    }

    /** Stops speaking and drops anything queued. */
    fun stop() {
        while (queue.tryReceive().isSuccess) Unit
        tts.stop()
        boardDone?.complete(Unit)
        board.sendLine("""{"t":"audio_stop"}""")
    }

    /** The board finished playing what we streamed. */
    fun onBoardAudioDone() { boardDone?.complete(Unit) }

    private suspend fun utterance(block: (String) -> Int): Boolean {
        if (!ready.await()) { log("Text-to-speech is not available on this phone"); return false }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[id] = done }
        if (block(id) != TextToSpeech.SUCCESS) { synchronized(pending) { pending.remove(id) }; return false }
        return withTimeoutOrNull(120_000) { done.await() } ?: false
    }

    private suspend fun speakNow(text: String) {
        when (output) {
            Output.OFF -> return
            Output.PHONE -> {
                board.onSpeaking(true)
                utterance { id -> tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id) }
                board.onSpeaking(false)
            }
            Output.BOARD -> {
                val file = File(appContext.cacheDir, "tts.wav")
                val ok = utterance { id -> tts.synthesizeToFile(text, Bundle(), file, id) }
                if (!ok) { log("Text-to-speech failed"); return }
                val pcm = withContext(Dispatchers.Default) { Wav.readMono16k(file) }
                if (pcm == null || pcm.isEmpty()) { log("Couldn't read speech audio"); return }
                streamToBoard(pcm)
            }
        }
    }

    /** Streams at most ~0.75 s ahead of playback; the board buffers ~1 s and starts after 0.4 s. */
    private suspend fun streamToBoard(pcm: ShortArray) {
        val packets = Adpcm.encodePackets(pcm, board.audioPayload)
        if (packets.isEmpty()) return
        val samplesPer = (board.audioPayload - Adpcm.HEADER) * 2
        val done = CompletableDeferred<Unit>()
        boardDone = done
        board.onSpeaking(true)
        board.sendLine("""{"t":"audio_start","rate":16000}""")
        val start = System.nanoTime()
        for ((i, p) in packets.withIndex()) {
            val aheadMs = (i.toLong() * samplesPer * 1000 / Adpcm.RATE) - (System.nanoTime() - start) / 1_000_000
            if (aheadMs > 750) delay(aheadMs - 750)
            if (done.isCompleted) break // stopped
            var tries = 0
            while (!board.sendAudio(p) && tries++ < 50) delay(10)
        }
        board.sendLine("""{"t":"audio_end"}""")
        val playMs = pcm.size * 1000L / Adpcm.RATE
        withTimeoutOrNull(playMs + 5_000) { done.await() }
        boardDone = null
        board.onSpeaking(false)
    }

    fun shutdown() {
        worker?.cancel()
        tts.shutdown()
    }
}

/** Minimal WAV reader: PCM16, any rate/channels -> mono 16 kHz. */
object Wav {
    fun readMono16k(file: File): ShortArray? {
        val bytes = file.readBytes()
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 1
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = buf.getInt(pos + 4)
            if (id == "fmt ") {
                channels = buf.getShort(pos + 10).toInt()
                rate = buf.getInt(pos + 12)
                bits = buf.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataOff = pos + 8
                dataLen = if (len < 0 || dataOff + len > bytes.size) bytes.size - dataOff else len
                break
            }
            pos += 8 + len + (len and 1)
        }
        if (dataOff < 0 || bits != 16 || rate <= 0 || channels < 1) return null
        val frames = dataLen / (2 * channels)
        val mono = ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += buf.getShort(dataOff + (f * channels + c) * 2)
            (sum / channels).toShort()
        }
        return resample(mono, rate, Adpcm.RATE)
    }

    /** Linear resampling; fine for speech. */
    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        return ShortArray(outLen) { i ->
            val src = i.toDouble() * from / to
            val i0 = src.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = src - i0
            (input[i0] * (1 - frac) + input[i1] * frac).toInt().toShort()
        }
    }
}
