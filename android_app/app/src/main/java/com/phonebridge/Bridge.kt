package com.phonebridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Base64
import androidx.core.content.edit
import com.phonebridge.google.GoogleAuth
import com.phonebridge.google.GoogleCommands
import com.phonebridge.integrations.AsanaCommands
import com.phonebridge.integrations.CameraCommands
import com.phonebridge.integrations.FollowUpCommands
import com.phonebridge.integrations.HomeCommands
import com.phonebridge.integrations.MediaCommands
import com.phonebridge.muse.BoardPort
import com.phonebridge.muse.Conversation
import com.phonebridge.muse.MuseClient
import com.phonebridge.muse.MuseStore
import com.phonebridge.voice.Adpcm
import com.phonebridge.voice.Speech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-wide bridge: owns the BLE link to the board, the Muse session and
 * the phone-side actions. Lives in [BridgeService] so it keeps running with the
 * screen off.
 *
 * Board protocol (one JSON object per line):
 *   phone -> board: time, weather, avatar, caption, msg, notify, confirm, confirm_cancel, buttons,
 *                   muse, status, pair {on}, mtx {d}, audio_start, audio_end, audio_stop
 *   board -> phone: hello {fw,name,addr}, talk {on}, btn {id}, key {id}, confirm_answer {id,answer},
 *                   audio_done, mrx {d}, mconn, mdisc, pair_adv {on,ok}
 *   plus ADPCM audio on the AUDIO characteristic (mic up, speaker down).
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Bridge : BleLink.Listener, BoardPort, Conversation.Listener, Speech.Board {

    /** Confirm ids for questions Muse asked in a reply (answered by sending a chat message). */
    private const val CHAT_CONFIRM = "chat-"
    /** Confirm ids for Muse approvals, mapped to the approval id. */
    private const val APPROVAL = "ap-"
    private val approvals = ConcurrentHashMap<String, Pair<String, List<String>>>()

    data class LinkUi(val state: BleLink.State = BleLink.State.IDLE, val deviceName: String = "")

    private lateinit var app: Context
    private lateinit var link: BleLink
    lateinit var museStore: MuseStore
        private set
    lateinit var muse: MuseClient
        private set
    private lateinit var speech: Speech
    lateinit var google: GoogleAuth
        private set
    lateinit var settings: Settings
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var pairingTimeout: Job? = null
    private val confirms = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val _link = MutableStateFlow(LinkUi())
    val linkUi: StateFlow<LinkUi> = _link.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val prefs by lazy { app.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var city: String
        get() = prefs.getString("city", "Pune")!!
        set(v) = prefs.edit { putString("city", v.trim()) }
    var speechOutput: Speech.Output
        get() = runCatching { Speech.Output.valueOf(prefs.getString("speech", "BOARD")!!) }.getOrDefault(Speech.Output.BOARD)
        set(v) { prefs.edit { putString("speech", v.name) }; if (::speech.isInitialized) speech.output = v }

    val isBluetoothOn get() = link.isBluetoothOn

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        link = BleLink(app, this)
        museStore = MuseStore(app)
        google = GoogleAuth(app)
        settings = Settings(app)
        val gmail = GoogleCommands(google, this, ::onLog)
        val providers = listOf(
            gmail,
            AsanaCommands(settings, ::onLog),
            HomeCommands(settings, this, ::onLog),
            MediaCommands(app, { speech }, ::onLog),
            CameraCommands(app, ::onLog),
            FollowUpCommands(app, scope, gmail, ::report, ::instruct, ::onLog),
        )
        muse = MuseClient(scope, museStore, this, ::isOnline, this, providers, ::onLog)
        speech = Speech(app, scope, this, ::onLog).also { it.output = speechOutput }
        scope.launch { muse.status.collect { pushMuseState(it) } }
    }

    /** While true (the service is running), a lost or missing board is retried in the background. */
    @Volatile private var keepConnected = false
    private var retryJob: Job? = null

    /** The board seen before, if any. */
    val knownBoard: String? get() = prefs.getString("board_name", null)

    fun connect() {
        keepConnected = true
        retryJob?.cancel()
        link.connect(knownBoard)
    }

    /** Forget the remembered board so the next Connect picks any MuseGadget board. */
    fun forgetBoard() = prefs.edit { remove("board_name") }
    fun disconnect() {
        keepConnected = false
        retryJob?.cancel()
        link.disconnect()
    }

    // ------------------------------------------------------------------ BleLink.Listener

    override fun onStateChanged(state: BleLink.State, deviceName: String) {
        _link.value = LinkUi(state, deviceName)
        if (state == BleLink.State.IDLE) {
            refreshJob?.cancel()
            confirms.values.forEach { it.complete("timeout") }
            confirms.clear()
            if (::muse.isInitialized) muse.conversation.cancel()
            // Board out of range, switched off or not found: keep trying quietly.
            if (keepConnected && retryJob?.isActive != true) {
                retryJob = scope.launch {
                    delay(10_000)
                    if (keepConnected && link.state == BleLink.State.IDLE) link.connect(knownBoard)
                }
            }
        }
    }

    override fun onLog(text: String) {
        android.util.Log.i("PhoneBridge", text)
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        scope.launch { _log.value = (listOf("$t  $text") + _log.value).take(300) }
    }

    override fun onLine(line: String) {
        val m = runCatching { JSONObject(line) }.getOrElse { onLog("Bad line from board: $line"); return }
        when (m.optString("t")) {
            "hello" -> onHello(m)
            "talk" -> if (m.optBoolean("on")) startTalking() else {
                muse.conversation.endVoice()
                saveLastNote()
            }
            "audio_done" -> speech.onBoardAudioDone()
            "btn" -> runAction(m.optInt("id"))
            "key" -> if (m.optInt("id") == 2) stopAll()
            "confirm_answer" -> {
                val id = m.optString("id")
                when {
                    id.startsWith(CHAT_CONFIRM) -> answerChatQuestion(m.optString("answer") == "yes")
                    id.startsWith(APPROVAL) -> answerApproval(id, m.optString("answer"))
                    else -> confirms.remove(id)?.complete(m.optString("answer"))
                }
            }
            "mrx" -> {
                autoPair()
                muse.onSetupPacket(Base64.decode(m.optString("d"), Base64.DEFAULT))
            }
            "mconn" -> { onLog("Muse app connected to the board"); autoPair() }
            "mdisc" -> { onLog("Muse app disconnected from the board"); muse.onSetupDisconnect() }
            "pair_adv" -> if (!m.optBoolean("ok")) onLog("Board could not start pairing advertising")
            else -> onLog("Board: $line")
        }
    }

    private fun onHello(m: JSONObject) {
        onLog("Board ${m.optString("name")} firmware ${m.optString("fw")}")
        m.optString("name").takeIf { it.startsWith(BleLink.NAME_PREFIX) }?.let { prefs.edit { putString("board_name", it) } }
        send(JSONObject().put("t", "buttons").put("labels", JSONArray(listOf("Hold to talk", "Stop", "Weather"))))
        pushTime()
        pushWeather()
        m.optString("addr").takeIf { it.isNotEmpty() }?.let(muse::onBoardIdentity)
        pushMuseState(muse.status.value)
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                delay(15 * 60_000L)
                pushTime()
                pushWeather()
            }
        }
    }

    /** Board buttons: 0 is push-to-talk (handled as "talk"), 1 = Stop, 2 = Weather. */
    private fun runAction(id: Int) {
        when (id) {
            1 -> stopAll()
            2 -> pushWeather()
        }
    }

    /** Stop button / KEY2: stop speaking and drop the current request. */
    private fun stopAll() {
        speech.stop()
        muse.conversation.cancel()
        avatar("idle")
        send(JSONObject().put("t", "status").put("text", ""))
    }

    /** A watch fired: tell the user now, and let Muse follow up. */
    private fun report(title: String, text: String, forMuse: String) {
        scope.launch {
            send(JSONObject().put("t", "notify").put("text", ascii("$title: $text").take(160)))
            onLog("$title: $text")
            whenIdle { muse.conversation.sendText(forMuse) }
        }
    }

    /** A routine is due: hand its instruction to Muse as if the user said it. */
    private fun instruct(instruction: String) {
        scope.launch {
            whenIdle {
                send(JSONObject().put("t", "steps_clear"))
                caption("Routine", ascii(instruction), append = false)
                muse.conversation.sendText(instruction)
            }
        }
    }

    /** Runs [block] once no conversation turn is in progress (waits up to 3 minutes). */
    private suspend fun whenIdle(block: () -> Unit) {
        var waited = 0
        while ((muse.conversation.busy || speaking) && waited < 180) { delay(1_000); waited++ }
        block()
    }

    override fun onAudio(packet: ByteArray) {
        Adpcm.decodePacket(packet, Adpcm.KIND_MIC)?.let { muse.conversation.addPcm(it) }
    }

    private fun startTalking() {
        speech.stop()
        send(JSONObject().put("t", "steps_clear"))
        if (!muse.conversation.startVoice()) return
        avatar("listening")
        caption("You", "Listening...", append = false)
    }

    // ------------------------------------------------------------------ Muse

    private fun pushMuseState(s: MuseClient.Status) {
        val state = when (s) {
            MuseClient.Status.CONNECTED -> "connected"
            MuseClient.Status.CONNECTING -> "connecting"
            MuseClient.Status.PAIRING -> "pairing"
            MuseClient.Status.OFFLINE -> "offline"
            MuseClient.Status.NOT_PAIRED, MuseClient.Status.NO_BOARD -> "not_paired"
        }
        if (link.state == BleLink.State.CONNECTED) send(JSONObject().put("t", "muse").put("state", state))
    }

    fun askMuse(text: String) {
        if (text.isBlank()) return
        speech.stop()
        send(JSONObject().put("t", "steps_clear"))
        caption("You", ascii(text), append = false)
        muse.conversation.sendText(text)
    }

    // ------------------------------------------------------------------ Conversation.Listener

    private val captionBuf = StringBuilder()
    private var captionJob: Job? = null

    private fun avatar(state: String) = send(JSONObject().put("t", "avatar").put("state", state))

    private fun caption(title: String?, text: String, append: Boolean) {
        // The board's line buffer is 1 KB; keep each caption line well under it.
        text.chunked(400).forEachIndexed { i, part ->
            val o = JSONObject().put("t", "caption").put("text", part).put("append", append || i > 0)
            if (title != null && i == 0) o.put("title", title)
            send(o)
        }
    }

    override fun onThinking() {
        scope.launch {
            avatar("thinking")
            caption(null, "\n...", append = true)
        }
    }

    override fun onReplyText(text: String, newMessage: Boolean) {
        scope.launch {
            if (newMessage) {
                flushCaption()
                caption("Muse", ascii(text), append = false)
                avatar("speaking")
                return@launch
            }
            captionBuf.append(ascii(text))
            if (captionJob?.isActive != true) captionJob = scope.launch { delay(150); flushCaption() }
        }
    }

    private fun flushCaption() {
        captionJob?.cancel()
        if (captionBuf.isEmpty()) return
        caption(null, captionBuf.toString(), append = true)
        captionBuf.setLength(0)
    }

    override fun onReplyDone(text: String) {
        scope.launch {
            flushCaption()
            onLog("Muse: ${text.take(120)}")
            speech.speak(text)
            approvalQuestion(text)?.let { q ->
                send(JSONObject().put("t", "confirm").put("id", CHAT_CONFIRM + System.currentTimeMillis()).put("question", ascii(q)))
            }
        }
    }

    /** The last sentence of a reply, if it asks the user to approve or choose yes/no. */
    private fun approvalQuestion(text: String): String? {
        val last = text.trim().replace(Regex("[*_`]"), "")
            .split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        if (!last.endsWith("?")) return null
        val asks = Regex(
            "\\b(should i|shall i|do you want|want me to|would you like|ok to|okay to|go ahead|approve|confirm|" +
                "send it|proceed|is that (ok|okay|right|correct)|sound good|good to go)\\b",
            RegexOption.IGNORE_CASE,
        )
        return if (asks.containsMatchIn(last)) last.take(100) else null
    }

    /**
     * Muse needs the user's approval for one of its own connectors. Muse only
     * accepts decisions from the Muse app (device tokens get HTTP 403), so the
     * board shows the request and gets the user to the app quickly.
     */
    override fun onApproval(id: String, title: String, question: String, options: List<Pair<String, String>>) {
        muse.conversation.holdForApproval()
        scope.launch {
            val confirmId = APPROVAL + id.takeLast(12)
            approvals[confirmId] = id to emptyList()
            onLog("Approval needed: $title - $question")
            send(JSONObject().put("t", "confirm").put("id", confirmId).put("title", ascii("Approve in Muse app - $title"))
                .put("question", ascii(question).take(200))
                .put("options", JSONArray(listOf("Open on phone", "Later"))))
            ApprovalNotice.show(app, id, title, question)
            speech.speak(question.substringBefore('\n') + ". Approve it in the Muse app.")
        }
    }

    override fun onApprovalGone(id: String) {
        val confirmId = APPROVAL + id.takeLast(12)
        ApprovalNotice.clear(app, id)
        if (approvals.remove(confirmId) != null) {
            scope.launch { send(JSONObject().put("t", "confirm_cancel").put("id", confirmId)) }
        }
    }

    /** "Open on phone" (index 0) or "Later" (index 1) on the board. */
    private fun answerApproval(confirmId: String, answer: String) {
        val (approvalId, _) = approvals[confirmId] ?: return
        if (answer == "0") ApprovalNotice.openMuseApp(app, approvalId)
    }

    /** Writes the last voice note to the app cache (cache/last_note.wav) to check the mic. */
    private fun saveLastNote() {
        val pcm = muse.conversation.lastNote
        if (pcm.isEmpty()) return
        runCatching {
            val header = com.phonebridge.muse.Conversation.wavHeader(16000)
            // Fix the sizes so ordinary players read it.
            fun le(off: Int, v: Int) { for (i in 0 until 4) header[off + i] = (v shr (8 * i)).toByte() }
            le(4, 36 + pcm.size); le(40, pcm.size)
            java.io.File(app.cacheDir, "last_note.wav").writeBytes(header + pcm)
        }
    }

    override fun onLateReply(text: String) {
        scope.launch {
            onLog("Muse (late reply): ${text.take(120)}")
            caption("Muse", ascii(text), append = false)
            avatar("speaking")
            speech.speak(text)
        }
    }

    override fun onOtherMessage(text: String) {
        scope.launch {
            onLog("Muse (not from the board): ${text.take(120)}")
            send(JSONObject().put("t", "popup").put("title", "Quill").put("text", ascii(text).take(600)).put("timeout_s", 45))
            speech.speak(text)
        }
    }

    /** The user tapped Yes/No on a question Muse asked in its reply. */
    private fun answerChatQuestion(yes: Boolean) {
        askMuse(if (yes) "Yes, go ahead." else "No, don't do that.")
    }

    override fun onTurnEnd() {
        scope.launch { if (!speaking) avatar("idle") }
    }

    override fun onError(message: String) {
        scope.launch {
            onLog("Muse: $message")
            avatar("error")
            caption("Muse", message, append = false)
            delay(3_000)
            if (!muse.conversation.busy && !speaking) avatar("idle")
        }
    }

    // ------------------------------------------------------------------ Speech.Board

    @Volatile private var speaking = false

    override val audioPayload get() = link.audioPayload

    override fun sendLine(json: String) = link.sendLine(json)

    override fun sendAudio(packet: ByteArray) = link.sendAudio(packet)

    override fun onSpeaking(active: Boolean) {
        speaking = active
        scope.launch { avatar(if (active) "speaking" else if (muse.conversation.busy) "thinking" else "idle") }
    }

    /** The Muse app is talking to the board: answer it without needing "Pair with Muse". */
    private fun autoPair() {
        if (muse.status.value != MuseClient.Status.PAIRING) {
            onLog("Muse app is adding the board; pairing")
            startPairing()
        }
    }

    fun startPairing(): Boolean {
        if (link.state != BleLink.State.CONNECTED) { onLog("Connect to the board first"); return false }
        val id = muse.identity ?: run { onLog("Waiting for the board's hello"); return false }
        val ok = muse.startPairing(
            send = { packets ->
                for ((i, p) in packets.withIndex()) {
                    if (i > 0) delay(50)
                    send(JSONObject().put("t", "mtx").put("d", Base64.encodeToString(p, Base64.NO_WRAP)))
                }
            },
            mtu = { 163 },
            onDone = { stopPairingAdvertising() },
        )
        if (!ok) return false
        send(JSONObject().put("t", "pair").put("on", true))
        sendMessage("Pair with Muse",
            "In the Muse app: Settings > Devices > Developer mode on. Then Add Device (+) and choose ${id.bleName}.",
            style = "card")
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch {
            delay(10 * 60_000L)
            if (muse.status.value == MuseClient.Status.PAIRING) { onLog("Pairing window closed"); cancelPairing() }
        }
        return true
    }

    fun cancelPairing() {
        muse.stopPairing()
        stopPairingAdvertising()
    }

    private fun stopPairingAdvertising() {
        pairingTimeout?.cancel()
        send(JSONObject().put("t", "pair").put("on", false))
    }

    // ------------------------------------------------------------------ BoardPort (Muse commands)

    override val connected get() = link.state == BleLink.State.CONNECTED

    override suspend fun showText(title: String, text: String, style: String) =
        sendMessage(title.ifEmpty { "Muse" }, text, style)

    override suspend fun notify(text: String) {
        send(JSONObject().put("t", "notify").put("text", ascii(text)))
    }

    override suspend fun askConfirm(question: String, timeoutS: Int): String {
        val id = UUID.randomUUID().toString().take(8)
        val answer = CompletableDeferred<String>()
        confirms[id] = answer
        send(JSONObject().put("t", "confirm").put("id", id).put("question", ascii(question)).put("timeout_s", timeoutS))
        val result = withTimeoutOrNull(timeoutS * 1000L) { answer.await() }
        confirms.remove(id)
        if (result == null) send(JSONObject().put("t", "confirm_cancel").put("id", id))
        return result ?: "timeout"
    }

    override fun step(id: String, text: String, state: String) {
        val o = JSONObject().put("t", "step").put("id", id).put("state", state)
        if (text.isNotEmpty()) o.put("text", ascii(text).take(60))
        send(o)
    }

    override fun status(): JSONObject {
        val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return JSONObject()
            .put("board_connected", connected)
            .put("phone_battery_percent", if (level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("phone_charging", plugged)
    }

    // ------------------------------------------------------------------ phone actions

    private fun send(msg: JSONObject) {
        if (!link.sendLine(msg.toString())) onLog("Not connected to the board")
    }

    fun pushTime() {
        val now = System.currentTimeMillis()
        send(JSONObject().put("t", "time").put("epoch", now / 1000 + TimeZone.getDefault().getOffset(now) / 1000))
    }

    fun pushWeather() {
        scope.launch {
            try {
                val w = Actions.weather(city)
                send(JSONObject().put("t", "weather").put("temp", w.temp).put("desc", w.desc).put("place", ascii(w.place)))
                onLog("Weather sent: ${w.temp} ${w.place}")
            } catch (e: Exception) {
                onLog("Weather error: ${e.message}")
            }
        }
    }

    fun sendMessage(title: String, body: String, style: String = "caption") {
        send(JSONObject().put("t", "msg").put("title", ascii(title)).put("body", ascii(body)).put("style", style))
    }

    private fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** The board's fonts only have ASCII and the degree sign, so strip the rest. */
    fun ascii(s: String): String = s
        .replace(Regex("[‘’]"), "'")
        .replace(Regex("[“”]"), "\"")
        .replace(Regex("[–—]"), "-")
        .replace(Regex("[^\\x20-\\x7E\\n°]"), "")
}
