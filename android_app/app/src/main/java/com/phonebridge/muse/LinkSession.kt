package com.phonebridge.muse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One control session with the user's Muse VM:
 * WebSocket /v1/noise -> Noise XX -> POST /link-control (long-lived) carrying
 * u32-LE length-prefixed JSON both ways. Other requests (chat messages, voice
 * notes, the /chat/subscribe reply stream) are multiplexed on the same session.
 * Port of link_client.py, plus the chat flow of the ESP32 SDK's muse_chat_link.c.
 */
class LinkSession(
    noiseHost: String,
    vmId: String,
    private val vmToken: String,
    private val register: JSONObject,
    private val runCommand: suspend (command: String, params: JSONObject) -> JSONObject,
    /** Each event line from /chat/subscribe (type "event"). */
    private val onChatEvent: (JSONObject) -> Unit,
    /** Called each time /chat/subscribe is (re)opened; event sequence numbers restart then. */
    private val onSubscribed: () -> Unit,
    private val log: (String) -> Unit,
) {
    enum class Outcome { CLOSED, AUTH_REJECTED, FORBIDDEN, UNPAIRED, STOPPED }

    /** Receives every frame of one request stream; [ended] once the response is complete. */
    fun interface StreamHandler {
        fun onFrame(status: Int?, data: ByteArray, ended: Boolean, error: String?)
    }

    private val url = "wss://$noiseHost/v1/noise?vm_id=" + URLEncoder.encode(vmId, "UTF-8").replace("+", "%20")

    private sealed interface Event {
        data class Open(val ws: WebSocket) : Event
        data class Bytes(val data: ByteArray) : Event
        data class Failure(val code: Int?, val error: Throwable?) : Event
        data object Closed : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val invokes = Semaphore(4)
    private val streams = ConcurrentHashMap<Long, StreamHandler>()
    private var ws: WebSocket? = null
    @Volatile private var transport: NoiseTransport? = null
    private var controlStream = 0L
    private var registerId = ""
    @Volatile private var alive = true

    @Volatile var registered = false
        private set

    fun stop() {
        alive = false
        ws?.close(1000, null)
        events.trySend(Event.Closed)
    }

    /** Runs until the session ends. */
    suspend fun run(): Outcome {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $vmToken")
            .header("User-Agent", MuseApi.USER_AGENT)
            .build()
        MuseApi.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Event.Open(webSocket)) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { events.trySend(Event.Bytes(bytes.toByteArray())) }
            override fun onMessage(webSocket: WebSocket, text: String) { log("ignoring text frame") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Event.Closed) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(Event.Failure(response?.code, t))
            }
        })
        try {
            return loop()
        } finally {
            alive = false
            registered = false
            streams.values.forEach { it.onFrame(null, ByteArray(0), true, "session ended") }
            streams.clear()
            ws?.cancel()
            scope.cancel()
        }
    }

    private suspend fun loop(): Outcome {
        val noise = NoiseXXInitiator()
        val decoder = MessageDecoder()
        var phase = 0 // 0 = waiting open, 1 = waiting msg2, 2 = established
        val handshakeDeadline = System.currentTimeMillis() + 20_000
        while (true) {
            val ev = if (phase < 2) {
                withTimeoutOrNull(maxOf(1, handshakeDeadline - System.currentTimeMillis())) { events.receive() }
                    ?: return Outcome.CLOSED.also { log("Noise handshake timed out") }
            } else events.receive()

            when (ev) {
                is Event.Open -> {
                    ws = ev.ws
                    ev.ws.send(noise.writeMessage1().toByteString())
                    phase = 1
                }
                is Event.Failure -> {
                    val code = ev.code
                    if (code == 401 || code == 403) {
                        log("Muse refused the connection: HTTP $code")
                        return if (code == 401) Outcome.AUTH_REJECTED else Outcome.FORBIDDEN
                    }
                    log("Connection closed: ${ev.error?.message ?: code}")
                    return Outcome.CLOSED
                }
                is Event.Closed -> return if (alive) Outcome.CLOSED else Outcome.STOPPED
                is Event.Bytes -> {
                    if (phase == 1) {
                        noise.readMessage2(ev.data)
                        ws!!.send(noise.writeMessage3().toByteString())
                        val (send, recv) = noise.split()
                        transport = NoiseTransport(send, recv)
                        log("Noise session established")
                        openControlStream()
                        phase = 2
                        continue
                    }
                    val frame = transport!!.decrypt(ev.data) ?: continue
                    if (frame.streamId != controlStream) {
                        deliver(frame)
                        continue
                    }
                    val (data, ended) = when (val v = frame.value) {
                        is Reset -> { log("control stream reset: ${v.reason}"); return Outcome.CLOSED }
                        is AppResponse -> {
                            if (v.status >= 400) {
                                log("/link-control refused: HTTP ${v.status}")
                                return if (v.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
                            }
                            v.body to v.endBody
                        }
                        is BodyChunk -> v.data to v.endBody
                        is AppRequest -> continue
                    }
                    for (msg in decoder.feed(data)) handle(msg)?.let { return it }
                    if (ended) { log("control stream ended by Muse"); return Outcome.CLOSED }
                }
            }
        }
    }

    private fun deliver(frame: InFrame) {
        val h = streams[frame.streamId] ?: return
        when (val v = frame.value) {
            is Reset -> { streams.remove(frame.streamId); h.onFrame(null, ByteArray(0), true, "stream reset: ${v.reason}") }
            is AppResponse -> { if (v.endBody) streams.remove(frame.streamId); h.onFrame(v.status, v.body, v.endBody, null) }
            is BodyChunk -> { if (v.endBody) streams.remove(frame.streamId); h.onFrame(null, v.data, v.endBody, null) }
            is AppRequest -> Unit
        }
    }

    // ------------------------------------------------------------------ sending (ordered)

    private val sendLock = Any()

    private fun sendFrames(frames: List<ByteArray>) = synchronized(sendLock) {
        frames.forEach { ws?.send(it.toByteString()) }
    }

    /** Opens a request stream; frames of the response go to [handler]. Returns the stream id. */
    fun openStream(verb: String, path: String, headers: List<Header>, body: ByteArray, endBody: Boolean, handler: StreamHandler): Long {
        val t = transport ?: throw IllegalStateException("not connected")
        synchronized(sendLock) {
            val (id, frames) = t.request(verb, path, body, headers, endBody)
            streams[id] = handler
            sendFrames(frames)
            return id
        }
    }

    fun sendBody(streamId: Long, data: ByteArray, end: Boolean) {
        val t = transport ?: return
        synchronized(sendLock) { sendFrames(t.bodyChunk(streamId, data, end)) }
    }

    fun cancelStream(streamId: Long) {
        val t = transport ?: return
        streams.remove(streamId)
        synchronized(sendLock) { sendFrames(t.reset(streamId)) }
    }

    private fun openControlStream() {
        val t = transport!!
        synchronized(sendLock) {
            val (id, frames) = t.request("POST", "/link-control", ByteArray(0), emptyList(), endBody = false)
            controlStream = id
            sendFrames(frames)
        }
        registerId = UUID.randomUUID().toString()
        send(JSONObject().put("type", "req").put("id", registerId).put("method", "link.register").put("params", register))
        log("Sent link.register")
    }

    private fun send(msg: JSONObject) = sendBody(controlStream, encodeMessage(msg), false)

    private fun handle(msg: JSONObject): Outcome? {
        if (msg.optString("id") == registerId && !msg.has("method")) {
            if (msg.has("error")) log("link.register rejected: ${msg.opt("error")}")
            else {
                registered = true
                log("Registered with Muse")
                openSubscription()
            }
            return null
        }
        val event = msg.optString("event")
        if (event == "link.unpaired" || event == "node.unpaired") { log("Muse removed this device"); return Outcome.UNPAIRED }
        if (msg.optString("method") == "link.invoke") {
            val id = msg.optString("id").ifEmpty { return null }
            val command = msg.optString("command")
            val params = msg.optJSONObject("params") ?: JSONObject()
            scope.launch {
                invokes.withPermit {
                    log("Muse ran $command")
                    val result = try { runCommand(command, params) } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message ?: "failed")
                    }
                    val out = JSONObject().put("method", "link.result").put("id", id)
                    result.keys().forEach { out.put(it, result.get(it)) }
                    send(out)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ chat

    private fun chatHeaders(accept: String) = listOf(
        Header("x-request-id", "muse-" + UUID.randomUUID().toString().take(17)),
        Header("x-app-id", "hatch-web"),
        Header("Content-Type", "application/json"),
        Header("accept", accept),
    )

    /** Long-lived NDJSON stream of chat events (replies). Reopened if it ends. */
    private fun openSubscription() {
        onSubscribed()
        val lines = StringBuilder()
        try {
            openStream("POST", "/chat/subscribe", chatHeaders("application/x-ndjson"), "{}".toByteArray(), true) { status, data, ended, error ->
                if (status != null && status >= 400) log("chat/subscribe refused: HTTP $status")
                lines.append(data.decodeToString())
                var nl = lines.indexOf("\n")
                while (nl >= 0) {
                    val line = lines.substring(0, nl).trim()
                    lines.delete(0, nl + 1)
                    if (line.isNotEmpty()) {
                        runCatching { JSONObject(line) }.getOrNull()
                            ?.takeIf { it.optString("type") == "event" }
                            ?.let(onChatEvent)
                    }
                    nl = lines.indexOf("\n")
                }
                if (ended && alive) {
                    log("Reply stream ended${error?.let { " ($it)" } ?: ""}; reopening")
                    scope.launch { delay(2_000); if (alive && registered) openSubscription() }
                }
            }
        } catch (e: Exception) {
            log("chat/subscribe failed: ${e.message}")
        }
    }

    /**
     * Starts a chat message from this device. For a voice note, pass
     * [Chat.NOTE_HEAD] as [firstBody] with endBody=false and stream the rest with
     * [sendBody]. Completes with (status, body) when Muse acknowledges it.
     */
    fun startChat(firstBody: ByteArray, endBody: Boolean): Pair<Long, CompletableDeferred<Pair<Int, String>>> {
        val done = CompletableDeferred<Pair<Int, String>>()
        val buf = ByteArrayOutputStream()
        var status = 0
        val id = openStream("POST", "/chat/stream", chatHeaders("application/json"), firstBody, endBody) { s, data, ended, error ->
            if (s != null) status = s
            buf.write(data)
            if (error != null) done.completeExceptionally(IllegalStateException(error))
            else if (ended) done.complete(status to buf.toByteArray().decodeToString().take(4000))
        }
        return id to done
    }

    /** A one-shot request with a JSON body on the session; returns (status, body). */
    suspend fun call(verb: String, path: String, body: JSONObject?): Pair<Int, String> {
        val done = CompletableDeferred<Pair<Int, String>>()
        val buf = ByteArrayOutputStream()
        var status = 0
        openStream(verb, path, chatHeaders("application/json"), body?.toString()?.toByteArray() ?: ByteArray(0), true) { s, data, ended, error ->
            if (s != null) status = s
            buf.write(data)
            if (error != null) done.completeExceptionally(IllegalStateException(error))
            else if (ended) done.complete(status to buf.toByteArray().decodeToString().take(2000))
        }
        return withTimeout(20_000) { done.await() }
    }

    /** Posts a text message as coming from this device; returns (status, ack body). */
    suspend fun sendChat(message: String, nodeId: String, sessionId: String? = null): Pair<Int, String> {
        val body = JSONObject().put("message", message).put("output_modality", "text").put("device_id", nodeId)
        if (sessionId != null) body.put("session_id", sessionId)
        val (_, done) = startChat(body.toString().toByteArray(), true)
        return withTimeout(60_000) { done.await() }
    }

    companion object {
        fun encodeMessage(obj: JSONObject): ByteArray {
            val data = obj.toString().toByteArray()
            return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array() + data
        }
    }

    /** Splits the control stream into length-prefixed JSON messages. */
    class MessageDecoder {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray): List<JSONObject> {
            buf += data
            val out = mutableListOf<JSONObject>()
            while (buf.size >= 4) {
                val len = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (len > 4 * 1024 * 1024) throw IllegalStateException("inbound message too large")
                if (buf.size < 4 + len) break
                val raw = buf.copyOfRange(4, 4 + len.toInt())
                buf = buf.copyOfRange(4 + len.toInt(), buf.size)
                if (raw.isEmpty()) continue // keepalive
                runCatching { JSONObject(raw.decodeToString()) }.getOrNull()?.let(out::add)
            }
            return out
        }
    }
}
