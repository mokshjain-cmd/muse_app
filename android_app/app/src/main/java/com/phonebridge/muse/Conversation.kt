package com.phonebridge.muse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Chat turns with Muse from the board: voice notes and typed questions, and
 * the replies that stream back on /chat/subscribe.
 *
 * Mirrors the ESP32 SDK (components/muse/muse_chat_link.c): a voice note is a
 * POST /chat/stream whose JSON body carries a base64 WAV (16 kHz mono PCM16,
 * streaming "unknown" size), written while the user talks. Replies arrive as
 * delta.message_start / delta.text_append / delta.message_done events.
 */
class Conversation(
    private val scope: CoroutineScope,
    private val session: () -> LinkSession?,
    private val nodeId: () -> String?,
    private val listener: Listener,
    private val log: (String) -> Unit,
) {
    interface Listener {
        fun onThinking()
        /** Reply text as it streams in. [newMessage] starts a fresh caption. */
        fun onReplyText(text: String, newMessage: Boolean)
        /** One assistant message is complete. */
        fun onReplyDone(text: String)
        /** The turn has settled (all messages done and quiet for a moment). */
        fun onTurnEnd()
        fun onError(message: String)
        /** Muse needs a decision (e.g. its Gmail connector wants to send). [options] are (kind, label). */
        fun onApproval(id: String, title: String, question: String, options: List<Pair<String, String>>) {}
        /** An approval was decided elsewhere or expired. */
        fun onApprovalGone(id: String) {}
        /** A complete Muse message that wasn't a reply to the board (Muse app chat, reminders...). */
        fun onOtherMessage(text: String) {}
        /** A reply to the board's last request that arrived after the turn ended (e.g. after an approval). */
        fun onLateReply(text: String) {}
    }

    private class Msg(val id: String) {
        val text = StringBuilder()
        var done = false
    }

    private enum class Phase { IDLE, RECORDING, WAIT_REPLY }

    private var phase = Phase.IDLE
    private var noteStream = 0L
    private var staging = ByteArrayOutputStream()
    private var pcmBytes = 0
    /** The last voice note as 16 kHz mono PCM16 (for saving and checking the mic). */
    var lastNote = ByteArray(0)
        private set
    private var noteBuf = ByteArrayOutputStream()
    /** User message ids of recent board turns, so late replies still count as replies. */
    private val recentAcks = LinkedHashMap<String, Long>()
    private var ackIds = setOf<String>()
    private var acked = false
    private val msgs = LinkedHashMap<String, Msg>()
    private val rejected = HashSet<String>()
    private var lastSeq = 0L
    private var settleJob: Job? = null
    private var timeoutJob: Job? = null

    val busy: Boolean @Synchronized get() = phase != Phase.IDLE

    // ------------------------------------------------------------------ voice note

    /** Opens the voice note request at the press, so audio streams while recording. */
    @Synchronized
    fun startVoice(): Boolean {
        val s = session()
        if (s == null || !s.registered) { listener.onError("Muse is not connected"); return false }
        reset()
        return try {
            val (id, done) = s.startChat(NOTE_HEAD.toByteArray(), endBody = false)
            noteStream = id
            staging = ByteArrayOutputStream().apply { write(wavHeader(16000)) }
            noteBuf = ByteArrayOutputStream()
            phase = Phase.RECORDING
            scope.launch { awaitAck(done) }
            true
        } catch (e: Exception) {
            listener.onError("Can't reach Muse"); false
        }
    }

    /** 16 kHz mono PCM from the board's mic. */
    @Synchronized
    fun addPcm(pcm: ShortArray) {
        if (phase != Phase.RECORDING) return
        val remaining = MAX_BYTES - pcmBytes
        val n = minOf(pcm.size, remaining / 2)
        for (i in 0 until n) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = (pcm[i].toInt() shr 8) and 0xFF
            staging.write(lo); staging.write(hi)
            noteBuf.write(lo); noteBuf.write(hi)
        }
        pcmBytes += n * 2
        if (staging.size() >= PART) flush(final = false)
    }

    @Synchronized
    fun endVoice() {
        if (phase != Phase.RECORDING) return
        lastNote = noteBuf.toByteArray()
        val secs = pcmBytes / 32000.0
        if (secs < 0.3) {
            session()?.cancelStream(noteStream)
            reset()
            listener.onError("Didn't catch that")
            return
        }
        flush(final = true)
        log("Voice note sent (%.1f s)".format(secs))
        waitForReply()
    }

    /** Sends whole 3-byte groups (so base64 stays continuous); everything plus the tail when final. */
    private fun flush(final: Boolean) {
        val s = session() ?: return
        val bytes = staging.toByteArray()
        val take = if (final) bytes.size else bytes.size - bytes.size % 3
        val b64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(0, take))
        staging = ByteArrayOutputStream().apply { write(bytes, take, bytes.size - take) }
        val chunk = if (final) b64 + NOTE_TAIL else b64
        s.sendBody(noteStream, chunk.toByteArray(), end = final)
    }

    // ------------------------------------------------------------------ typed

    @Synchronized
    fun sendText(text: String): Boolean {
        val s = session()
        val node = nodeId()
        if (s == null || !s.registered || node == null) { listener.onError("Muse is not connected"); return false }
        reset()
        val body = JSONObject().put("message", text).put("output_modality", "text").put("device_id", node)
        return try {
            val (_, done) = s.startChat(body.toString().toByteArray(), endBody = true)
            scope.launch { awaitAck(done) }
            waitForReply()
            true
        } catch (e: Exception) {
            listener.onError("Can't reach Muse"); false
        }
    }

    private suspend fun awaitAck(done: kotlinx.coroutines.CompletableDeferred<Pair<Int, String>>) {
        val (status, body) = try {
            withTimeout(90_000) { done.await() }
        } catch (e: Exception) {
            synchronized(this) { if (phase != Phase.IDLE) { reset(); listener.onError("Muse didn't answer") } }
            return
        }
        if (status !in 200..299) {
            log("chat/stream: HTTP $status ${body.take(200)}")
            synchronized(this) { reset(); listener.onError("Muse returned HTTP $status") }
            return
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
        val obj = root?.optJSONObject("result") ?: root
        synchronized(this) {
            ackIds = setOfNotNull(
                obj?.optString("message_id")?.takeIf { it.isNotEmpty() },
                obj?.optString("reply_to_message_id")?.takeIf { it.isNotEmpty() },
            )
            acked = true
            val now = System.currentTimeMillis()
            ackIds.forEach { recentAcks[it] = now }
            recentAcks.entries.removeAll { now - it.value > LATE_REPLY_MS }
        }
    }

    private fun waitForReply() {
        phase = Phase.WAIT_REPLY
        listener.onThinking()
        restartTimeout()
    }

    /** Gives Muse another full reply window (called on activity, and while an approval is pending). */
    private fun restartTimeout(ms: Long = REPLY_TIMEOUT_MS) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            synchronized(this@Conversation) {
                if (phase == Phase.WAIT_REPLY && msgs.isEmpty()) { reset(); listener.onError("No reply from Muse") }
            }
        }
    }

    /** Muse is waiting for the user's approval in the Muse app: wait up to 10 minutes. */
    @Synchronized
    fun holdForApproval() {
        if (phase == Phase.WAIT_REPLY) restartTimeout(10 * 60_000L)
    }

    // ------------------------------------------------------------------ replies

    /** The reply stream was (re)opened: its sequence numbers start over (as the ESP32 client resets them). */
    @Synchronized
    fun onSubscribed() {
        lastSeq = 0
    }

    /** One event line from /chat/subscribe. */
    @Synchronized
    fun onChatEvent(line: JSONObject) {
        val seq = line.optLong("seq", 0)
        if (seq > 0) { if (seq <= lastSeq) return; lastSeq = seq }
        val event = line.optString("event")
        val payload = line.optJSONObject("payload") ?: JSONObject()
        if (event == "approvals.snapshot") { onApprovals(payload); return }
        if (event !in KNOWN_EVENTS) log("Muse event $event: ${payload.toString().take(700)}")
        if (phase != Phase.WAIT_REPLY) { otherMessage(event, payload, line); return }

        if (event == "agent.status" || event == "task.status") { restartTimeout(); touch(); return }
        val start = event == "delta.message_start"
        val append = event == "delta.text_append"
        val done = event == "delta.message_done"
        val full = event == "message.assistant"
        if (!start && !append && !done && !full) return

        val id = payload.optString("message_id").ifEmpty { line.optString("message_id") }.ifEmpty { payload.optString("id") }
        if (id.isEmpty()) return
        val m = bind(id, payload) ?: return
        timeoutJob?.cancel()
        touch()
        when {
            append -> {
                val t = payload.optString("text")
                if (t.isNotEmpty()) {
                    val first = m.text.isEmpty()
                    m.text.append(t)
                    listener.onReplyText(t, newMessage = first)
                }
            }
            done || full -> {
                if (full && payload.has("display_text_ready") && !payload.optBoolean("display_text_ready")) return
                val final = payload.optString("display_text").ifEmpty { payload.optString("content") }
                if (m.text.isEmpty() && final.isNotEmpty()) { m.text.append(final); listener.onReplyText(final, true) }
                if (!m.done) {
                    m.done = true
                    listener.onReplyDone(if (final.isNotEmpty()) final else m.text.toString())
                }
            }
        }
    }

    // ------------------------------------------------------------------ approvals

    private val shownApprovals = HashSet<String>()

    private fun onApprovals(payload: JSONObject) {
        // Logged in full so new fields (e.g. how to answer) are visible.
        payload.toString().chunked(900).forEachIndexed { i, part -> log("approvals.snapshot[$i] $part") }
        val pending = payload.optJSONArray("pending_approvals") ?: org.json.JSONArray()
        val now = HashSet<String>()
        for (i in 0 until pending.length()) {
            val a = pending.getJSONObject(i)
            val id = a.optString("approval_id")
            if (id.isEmpty()) continue
            now += id
            if (id in shownApprovals) continue
            shownApprovals += id
            val display = a.optJSONObject("display") ?: JSONObject()
            val title = display.optString("display_name").ifEmpty { "Muse" }
            // Prefer Muse's own sentence, e.g. "Quill wants to send an email to Ayush Gachale at ...".
            val purpose = findString(a, setOf("purpose_summary", "short_explanation", "scope_summary"))
            val question = purpose ?: (display.optString("action_description").ifEmpty { display.optString("action_label") }
                .ifEmpty { "Allow this" } + "?")
            val detail = if (purpose != null) "" else summary(display)
            val opts = a.optJSONArray("decision_options")?.let { o ->
                (0 until o.length()).map { o.getJSONObject(it).let { d -> d.optString("kind") to d.optString("label_text").ifEmpty { d.optString("kind") } } }
            } ?: listOf("allow_once" to "Allow", "deny" to "Deny")
            listener.onApproval(id, title, if (detail.isEmpty()) question else "$question\n$detail", opts)
        }
        (shownApprovals - now).forEach { listener.onApprovalGone(it) }
        shownApprovals.retainAll(now)
    }

    /** First non-empty string under any of [keys], searching nested objects (first key wins). */
    private fun findString(o: Any?, keys: Set<String>): String? {
        for (k in keys) findKey(o, k)?.let { return it }
        return null
    }

    private fun findKey(o: Any?, key: String): String? = when (o) {
        is JSONObject -> o.optString(key).takeIf { it.isNotBlank() && o.opt(key) is String }
            ?: o.keys().asSequence().firstNotNullOfOrNull { findKey(o.opt(it), key) }
        is org.json.JSONArray -> (0 until o.length()).firstNotNullOfOrNull { findKey(o.opt(it), key) }
        else -> null
    }

    /** A short line from the approval's detail rows, e.g. the recipient, if present. */
    private fun summary(display: JSONObject): String {
        val rows = display.optJSONArray("detail_rows") ?: return ""
        val skip = setOf("host", "method", "path", "policy")
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("label").lowercase() !in skip }
            .joinToString("  ") { "${it.optString("label")}: ${it.optString("value")}" }.take(120)
    }

    // ------------------------------------------------------------------ other messages

    private val otherText = HashMap<String, StringBuilder>()
    private val otherDone = LinkedHashSet<String>()

    /** Assistant messages outside a board turn: collected and passed on once complete. */
    private fun otherMessage(event: String, payload: JSONObject, line: JSONObject) {
        val id = payload.optString("message_id").ifEmpty { line.optString("message_id") }.ifEmpty { payload.optString("id") }
        if (id.isEmpty() || id in otherDone) return
        when (event) {
            "delta.text_append" -> otherText.getOrPut(id) { StringBuilder() }.append(payload.optString("text"))
            "delta.message_done", "message.assistant" -> {
                if (event == "message.assistant" && payload.has("display_text_ready") && !payload.optBoolean("display_text_ready")) return
                val text = payload.optString("display_text").ifEmpty { payload.optString("content") }
                    .ifEmpty { otherText[id]?.toString().orEmpty() }
                otherText.remove(id)
                if (text.isBlank()) return
                otherDone += id
                if (otherDone.size > 50) otherDone.remove(otherDone.first())
                val parent = payload.optString("reply_to_message_id").ifEmpty { payload.optString("parent_message_id") }
                val late = parent.isNotEmpty() && recentAcks[parent]?.let { System.currentTimeMillis() - it < LATE_REPLY_MS } == true
                if (late) listener.onLateReply(text) else listener.onOtherMessage(text)
            }
        }
    }

    /** Same rule as the ESP32 client: once acked, replies to other messages belong to someone else. */
    private fun bind(id: String, payload: JSONObject): Msg? {
        if (id in rejected) return null
        msgs[id]?.let { return it }
        val parent = payload.optString("reply_to_message_id").ifEmpty { payload.optString("parent_message_id") }
        if (parent.isNotEmpty() && acked && parent !in ackIds && parent !in msgs) { rejected += id; return null }
        if (msgs.size >= 8) return null
        return Msg(id).also { msgs[id] = it }
    }

    /** A turn ends once every message is done and nothing arrives for a few seconds. */
    private fun touch() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            synchronized(this@Conversation) {
                if (phase == Phase.WAIT_REPLY && msgs.isNotEmpty() && msgs.values.all { it.done }) {
                    reset()
                    listener.onTurnEnd()
                }
            }
        }
    }

    @Synchronized
    fun cancel() {
        if (phase == Phase.RECORDING) session()?.cancelStream(noteStream)
        reset()
    }

    private fun reset() {
        phase = Phase.IDLE
        pcmBytes = 0
        ackIds = emptySet()
        acked = false
        msgs.clear()
        rejected.clear()
        settleJob?.cancel()
        timeoutJob?.cancel()
    }

    companion object {
        const val NOTE_HEAD = "{\"message\":\"\",\"output_modality\":\"text\",\"items\":[{\"type\":\"file\"," +
            "\"mime_type\":\"audio/wav\",\"filename\":\"voice_note.wav\",\"data_base64\":\""
        const val NOTE_TAIL = "\"}]}"
        private const val PART = 6144                 // staged bytes per body chunk (multiple of 3)
        private const val MAX_BYTES = 16000 * 2 * 62  // 62 s: the board stops recording at 60 s
        private const val REPLY_TIMEOUT_MS = 60_000L
        private const val SETTLE_MS = 3_000L
        private const val LATE_REPLY_MS = 15 * 60_000L
        private val KNOWN_EVENTS = setOf(
            "delta.message_start", "delta.text_append", "delta.message_done", "message.assistant",
            "agent.status", "task.status", "message.user",
        )

        /** WAV header with the streaming "unknown" size, as the ESP32 client writes it. */
        fun wavHeader(rate: Int): ByteArray {
            val h = ByteArray(44)
            fun le(off: Int, v: Long, n: Int) { for (i in 0 until n) h[off + i] = (v shr (8 * i)).toByte() }
            "RIFF".toByteArray().copyInto(h, 0)
            le(4, 0xFFFFFFFFL, 4)
            "WAVEfmt ".toByteArray().copyInto(h, 8)
            le(16, 16, 4); le(20, 1, 2); le(22, 1, 2); le(24, rate.toLong(), 4)
            le(28, rate * 2L, 4); le(32, 2, 2); le(34, 16, 2)
            "data".toByteArray().copyInto(h, 36)
            le(40, 0xFFFFFFFFL, 4)
            return h
        }
    }
}
