package com.phonebridge.integrations

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.phonebridge.muse.CommandProvider
import com.phonebridge.muse.Specs
import com.phonebridge.muse.Specs.P
import com.phonebridge.muse.Specs.need
import com.phonebridge.voice.Speech
import com.phonebridge.voice.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder

/**
 * Plays audio from a URL on the phone, on the board's speaker, or on a
 * Chromecast / Google TV / Nest speaker on the home network.
 */
class MediaCommands(
    context: Context,
    private val speech: () -> Speech,
    private val log: (String) -> Unit,
) : CommandProvider {

    private val app = context.applicationContext
    private var player: MediaPlayer? = null

    override val specs: JSONObject
        get() = JSONObject()
            .put("media.play", Specs.spec(
                "Play audio from a URL (MP3, AAC, OGG, WAV or a radio stream) on the desk display's speaker or the phone. " +
                    "The desk display plays at most 3 minutes; use target=phone for long audio.",
                listOf(P("url", "Direct audio URL.")),
                listOf(P("target", "board (default) or phone.")),
                timeoutMs = 120_000,
            ))
            .put("media.stop", Specs.spec("Stop audio playing on the phone or the desk display."))
            .put("media.cast_devices", Specs.spec("List Chromecast / Google TV / Nest speakers on the home network.", timeoutMs = 20_000))
            .put("media.cast", Specs.spec(
                "Cast a video or audio URL to a Chromecast, Google TV or Nest speaker.",
                listOf(P("url", "Direct media URL.")),
                listOf(P("device", "Device name from media.cast_devices. Default: the first one found."),
                    P("title", "Title to show."), P("mime", "MIME type, e.g. video/mp4 or audio/mpeg. Default guessed from the URL.")),
                timeoutMs = 45_000,
            ))
            .put("media.cast_stop", Specs.spec("Stop casting."))

    override suspend fun run(command: String, params: JSONObject): JSONObject? {
        if (!command.startsWith("media.")) return null
        return try {
            when (command) {
                "media.play" -> play(params)
                "media.stop" -> { stopPhone(); speech().stop(); Specs.ok() }
                "media.cast_devices" -> Specs.ok(JSONObject().put("devices", JSONArray(castRoutes().map { it.name })))
                "media.cast" -> cast(params)
                "media.cast_stop" -> withContext(Dispatchers.Main) {
                    CastContext.getSharedInstance(app).sessionManager.endCurrentSession(true)
                    Specs.ok()
                }
                else -> Specs.err("unknown command: $command")
            }
        } catch (e: Specs.Fail) {
            Specs.err(e.message!!)
        } catch (e: Exception) {
            log("$command failed: ${e.message}")
            Specs.err("$command failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ local playback

    private suspend fun play(p: JSONObject): JSONObject {
        val url = p.need("url")
        if (p.optString("target", "board") == "phone") {
            withContext(Dispatchers.Main) {
                stopPhone()
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    setDataSource(url)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { stopPhone() }
                    prepareAsync()
                }
            }
            log("Playing on phone: $url")
            return Specs.ok(JSONObject().put("playing", "phone"))
        }
        val pcm = withContext(Dispatchers.IO) { decodeMono16k(url, MAX_BOARD_SECONDS) }
        if (pcm.isEmpty()) throw Specs.Fail("Couldn't decode audio from that URL.")
        speech().playOnBoard(pcm)
        log("Playing on board: ${pcm.size / 16000} s")
        return Specs.ok(JSONObject().put("playing", "board").put("seconds", pcm.size / 16000))
    }

    private fun stopPhone() {
        player?.runCatching { stop(); release() }
        player = null
    }

    /** Decodes any Android-supported audio URL to 16 kHz mono PCM16. */
    private fun decodeMono16k(url: String, maxSeconds: Int): ShortArray {
        val ex = MediaExtractor()
        ex.setDataSource(url)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw Specs.Fail("No audio track in that URL.")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var mono = ShortArray(1 shl 16)
        var count = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = buf.remaining() / channels
                    if (count + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, count + frames))
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += buf.get(f * channels + c)
                        mono[count++] = (sum / channels).toShort()
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || count >= rate * maxSeconds) break
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return Wav.resample(mono.copyOf(count), rate, 16000)
    }

    // ------------------------------------------------------------------ Cast

    private suspend fun castRoutes(): List<MediaRouter.RouteInfo> = withContext(Dispatchers.Main) {
        CastContext.getSharedInstance(app) // initializes the framework
        val router = MediaRouter.getInstance(app)
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        val cb = object : MediaRouter.Callback() {}
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        delay(5_000)
        router.removeCallback(cb)
        router.routes.filter { it.matchesSelector(selector) && !it.isDefault }
    }

    private suspend fun cast(p: JSONObject): JSONObject {
        val url = p.need("url")
        val want = p.optString("device")
        val routes = castRoutes()
        val route = routes.firstOrNull { want.isNotEmpty() && it.name.equals(want, ignoreCase = true) } ?: routes.firstOrNull()
            ?: throw Specs.Fail("No Cast devices found on this Wi-Fi.")
        val mime = p.optString("mime").ifEmpty { guessMime(url) }
        val loaded = withContext(Dispatchers.Main) {
            val ctx = CastContext.getSharedInstance(app)
            MediaRouter.getInstance(app).selectRoute(route)
            withTimeoutOrNull(20_000) {
                var session = ctx.sessionManager.currentCastSession
                while (session?.remoteMediaClient == null) { delay(300); session = ctx.sessionManager.currentCastSession }
                val meta = MediaMetadata(if (mime.startsWith("audio")) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE)
                meta.putString(MediaMetadata.KEY_TITLE, p.optString("title").ifEmpty { "From Muse" })
                val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mime).setMetadata(meta).build()
                session.remoteMediaClient!!.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                true
            } ?: false
        }
        if (!loaded) throw Specs.Fail("Couldn't start casting to ${route.name}.")
        log("Casting to ${route.name}")
        return Specs.ok(JSONObject().put("device", route.name))
    }

    private fun guessMime(url: String): String {
        val u = url.lowercase().substringBefore('?')
        return when {
            u.endsWith(".mp3") -> "audio/mpeg"
            u.endsWith(".m4a") || u.endsWith(".aac") -> "audio/mp4"
            u.endsWith(".ogg") -> "audio/ogg"
            u.endsWith(".m3u8") -> "application/x-mpegURL"
            u.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    companion object {
        private const val MAX_BOARD_SECONDS = 180
    }
}

/** Cast framework configuration: the default media receiver. Registered in the manifest. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
