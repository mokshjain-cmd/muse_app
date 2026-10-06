package com.phonebridge.muse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A group of extra commands offered to Muse (Gmail, Calendar, ...). [specs] maps
 * each command name to {description, required, optional}; [run] returns null for
 * commands it doesn't own.
 */
interface CommandProvider {
    val specs: JSONObject
    suspend fun run(command: String, params: JSONObject): JSONObject?
}

/** What Muse can do on the board; implemented by the bridge. */
interface BoardPort {
    val connected: Boolean
    suspend fun showText(title: String, text: String, style: String)
    suspend fun notify(text: String)
    /** Returns "yes", "no" or "timeout". */
    suspend fun askConfirm(question: String, timeoutS: Int): String
    fun status(): JSONObject
    /** Shows one step of what Muse is doing ([state] = run, ok or fail). */
    fun step(id: String, text: String, state: String) {}
}

/**
 * Keeps this phone registered with the user's Muse as a gadget, and runs
 * pairing when asked. Port of service.py + cli.py (pair).
 */
class MuseClient(
    private val scope: CoroutineScope,
    private val store: MuseStore,
    private val board: BoardPort,
    private val isOnline: () -> Boolean,
    chatListener: Conversation.Listener,
    private val providers: List<CommandProvider>,
    private val log: (String) -> Unit,
) {
    enum class Status { NO_BOARD, NOT_PAIRED, PAIRING, CONNECTING, CONNECTED, OFFLINE }

    private val _status = MutableStateFlow(Status.NO_BOARD)
    val status: StateFlow<Status> = _status.asStateFlow()

    var identity: MuseIdentity? = null
        private set
    private var loop: Job? = null
    private var session: LinkSession? = null
    private var setup: SetupController? = null
    private val startedAt = System.currentTimeMillis()

    /** Voice and typed turns with Muse; replies stream back over the session. */
    val conversation = Conversation(scope, { session }, { identity?.nodeId }, chatListener, log)

    /** Called once the board says hello with its Bluetooth address. */
    fun onBoardIdentity(boardAddr: String) {
        val id = MuseIdentity.fromBoardAddress(boardAddr)
        if (store.boardAddress != boardAddr && store.pairing != null && store.boardAddress.isNotEmpty()) {
            log("A different board connected; it needs its own Muse pairing")
            store.pairing = null
        }
        store.boardAddress = boardAddr
        identity = id
        startLoop()
    }

    fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    /** Re-registers with Muse (after settings change, so the command list is current). */
    fun reregister() {
        session?.stop()
    }

    fun unpair() {
        store.pairing = null
        session?.stop()
        _status.value = Status.NOT_PAIRED
        log("Muse pairing removed from this phone. Also remove the device in the Muse app.")
    }

    // ------------------------------------------------------------------ pairing

    /**
     * Starts answering the Muse app. The board advertises as identity.bleName and
     * relays setup packets: [send] delivers our packets to the Muse app.
     */
    fun startPairing(send: suspend (List<ByteArray>) -> Unit, mtu: () -> Int, onDone: () -> Unit): Boolean {
        val id = identity ?: return false
        stopPairing()
        val sdk = store.sdkToken.ifEmpty { null }
        if (sdk == null) log("No SDK token set: get one at gadgets.muse.ai (Account > SDK tokens)")
        val pairing = PairingSession(id.nodeId, id.deviceId, id.mac, VERSION, sdk)
        setup = SetupController(
            scope = scope, pairing = pairing, identity = id, version = VERSION,
            sendPackets = send, mtu = mtu, isOnline = isOnline,
            provision = { creds -> verifyAndSave(creds) },
            onComplete = {
                onDone()
                stopPairing()
                session?.stop()
                startLoop()
            },
            log = log,
        )
        _status.value = Status.PAIRING
        log("Pairing open. In the Muse app: Settings > Devices > Developer mode on, then Add Device > ${id.bleName}")
        return true
    }

    fun onSetupPacket(packet: ByteArray) { setup?.onWrite(packet) }

    fun onSetupDisconnect() { setup?.onDisconnect() }

    fun stopPairing() {
        setup?.close()
        setup = null
        if (_status.value == Status.PAIRING) _status.value = if (store.pairing == null) Status.NOT_PAIRED else Status.CONNECTING
    }

    private suspend fun verifyAndSave(c: SetupController.Credentials) {
        val apiV2 = if (c.apiUrlV2.startsWith("https://")) c.apiUrlV2 else ""
        val (vms, status) = MuseApi.fetchVms(c.accessToken, MuseApi.root(apiV2))
        if (vms.isEmpty()) {
            log("Device token check failed (HTTP $status)")
            throw SetupController.ProvisionFailed("auth_failed")
        }
        store.pairing = MusePairing(c.accessToken, c.refreshToken, apiV2, c.noiseHost, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ session loop

    private suspend fun runLoop() {
        var failures = 0
        var reportedSdk = false
        while (scope.isActive) {
            val id = identity
            var pairing = store.pairing
            if (id == null) { _status.value = Status.NO_BOARD; delay(5_000); continue }
            if (setup != null) { delay(2_000); continue }
            if (pairing == null) { _status.value = Status.NOT_PAIRED; delay(5_000); continue }
            if (!isOnline()) { _status.value = Status.OFFLINE; delay(10_000); continue }
            _status.value = Status.CONNECTING

            // Rotate tokens every 3 hours, and once per start to report the SDK token.
            val canRefresh = pairing.refreshToken.isNotEmpty()
            val due = canRefresh && System.currentTimeMillis() - pairing.savedAt > 3 * 3600_000L
            if (due || (canRefresh && !reportedSdk && store.sdkToken.isNotEmpty())) {
                reportedSdk = true
                val refreshed = refresh(pairing, id, mustSucceed = due)
                if (refreshed == null) { delay(300_000); continue }
                pairing = refreshed
            }

            val root = MuseApi.root(pairing.apiUrlV2)
            val (vms, status) = MuseApi.fetchVms(pairing.accessToken, root)
            if (status == 401) {
                if (!canRefresh) { log("Device token rejected by Muse"); delay(300_000); continue }
                log("Device token rejected; refreshing")
                if (refresh(pairing, id, mustSucceed = true) == null) delay(300_000)
                continue
            }
            val vm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
            if (vm == null) { delay(backoff(failures++)); continue }

            val s = LinkSession(
                noiseHost = pairing.noiseHost.ifEmpty { MuseApi.DEFAULT_NOISE_HOST },
                vmId = vm.id.ifEmpty { vm.name },
                vmToken = vm.token,
                register = registerParams(id),
                runCommand = ::runCommand,
                onChatEvent = conversation::onChatEvent,
                onSubscribed = conversation::onSubscribed,
                log = log,
            )
            session = s
            val watcher = scope.launch {
                while (isActive) {
                    if (s.registered) { _status.value = Status.CONNECTED; failures = 0; break }
                    delay(300)
                }
            }
            log("Connecting to Muse (${vm.name.ifEmpty { vm.id }})")
            val outcome = try { s.run() } catch (e: Exception) { log("Session failed: ${e.message}"); LinkSession.Outcome.CLOSED }
            watcher.cancel()
            session = null
            when (outcome) {
                LinkSession.Outcome.UNPAIRED -> { store.pairing = null; log("Pairing removed by Muse; pair again") }
                LinkSession.Outcome.AUTH_REJECTED, LinkSession.Outcome.FORBIDDEN -> delay(maxOf(15_000, backoff(failures++)))
                else -> delay(backoff(failures++))
            }
        }
    }

    private fun backoff(n: Int) = minOf(2_000L shl minOf(n, 5), 60_000L)

    private suspend fun refresh(p: MusePairing, id: MuseIdentity, mustSucceed: Boolean): MusePairing? {
        val (tokens, status) = MuseApi.refresh(p.refreshToken, id.nodeId, MuseApi.root(p.apiUrlV2), store.sdkToken.ifEmpty { null })
        if (tokens != null) {
            val np = p.copy(accessToken = tokens.first, refreshToken = tokens.second, savedAt = System.currentTimeMillis())
            store.pairing = np
            log("Device token rotated")
            return np
        }
        if (!mustSucceed) return p
        if (status == 401) { store.pairing = null; log("Pairing revoked; pair again") }
        return null
    }

    // ------------------------------------------------------------------ commands

    private fun registerParams(id: MuseIdentity) = JSONObject()
        .put("node_id", id.nodeId)
        .put("display_name", "SiFli desk display")
        .put("platform", "linux")
        .put("version", VERSION)
        .put("device_family", "homehub") // never "link": the server pushes ESP32 OTA to those
        .put("model_id", "linux")
        .put("is_wakeup_supported", false)
        .put("commands_v2", JSONObject(COMMANDS.toString()).also { all ->
            providers.forEach { p -> p.specs.keys().forEach { all.put(it, p.specs.get(it)) } }
        })

    /** Runs a command Muse asked for, showing it as a step on the board. */
    private suspend fun runCommand(command: String, p: JSONObject): JSONObject {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val label = describe(command, p)
        if (label != null) board.step(id, label, "run")
        val result = runCommandInner(command, p)
        if (label != null) board.step(id, "", if (result.optBoolean("ok")) "ok" else "fail")
        return result
    }

    /** Short human description of a command for the board, or null for silent ones. */
    private fun describe(command: String, p: JSONObject): String? {
        fun s(k: String) = p.optString(k).take(40)
        return when (command) {
            "sifli.show_text", "sifli.notify", "sifli.read_sensors", "sifli.ask_confirm", "device.health",
            "watch.list", "routine.list", "media.stop" -> null
            "gmail.send" -> "Sending email to ${s("to")}"
            "gmail.draft" -> "Drafting email to ${s("to")}"
            "gmail.search" -> "Searching email"
            "gmail.read" -> "Reading email"
            "calendar.create_event" -> "Adding \"${s("title")}\" to calendar"
            "calendar.list_events" -> "Checking calendar"
            "asana.create_task" -> "Creating Asana task \"${s("name")}\""
            "asana.list_tasks", "asana.list_projects" -> "Checking Asana"
            "asana.complete_task" -> "Completing Asana task"
            "asana.add_comment" -> "Commenting in Asana"
            "watch.email_reply" -> "Watching for a reply"
            "routine.create" -> "Scheduling routine at ${s("time")}"
            "home.call_service" -> "Home: ${s("service")} ${s("entity_id")}"
            "home.states" -> "Checking home devices"
            "mqtt.publish" -> "Sending to ${s("topic")}"
            "media.play" -> "Playing audio"
            "media.cast" -> "Casting to ${s("device").ifEmpty { "TV" }}"
            "camera.read_text" -> "Reading with the camera"
            "camera.scan_code" -> "Scanning a code"
            else -> command
        }
    }

    private suspend fun runCommandInner(command: String, p: JSONObject): JSONObject {
        fun ok(payload: JSONObject = JSONObject()) = JSONObject().put("ok", true).put("payload", payload)
        fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)
        return when (command) {
            "sifli.show_text" -> {
                val text = p.optString("text")
                if (text.isEmpty()) return err("text is required")
                val style = p.optString("style", "caption")
                if (style !in setOf("caption", "card", "alert")) return err("style must be caption, card or alert")
                if (!board.connected) return err("the board is not connected to the phone")
                board.showText(p.optString("title", "Muse"), text, style)
                ok(JSONObject().put("shown", true))
            }
            "sifli.notify" -> {
                val text = p.optString("text")
                if (text.isEmpty()) return err("text is required")
                if (!board.connected) return err("the board is not connected to the phone")
                board.notify(text)
                ok(JSONObject().put("notified", true))
            }
            "sifli.ask_confirm" -> {
                val q = p.optString("question")
                if (q.isEmpty()) return err("question is required")
                if (!board.connected) return err("the board is not connected to the phone")
                val timeout = p.optInt("timeout_s", 30).coerceIn(5, 120)
                ok(JSONObject().put("answer", board.askConfirm(q, timeout)))
            }
            "sifli.read_sensors" -> ok(board.status())
            "device.health" -> ok(
                JSONObject().put("software_version", VERSION)
                    .put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000)
                    .put("board_connected", board.connected)
            )
            else -> providers.firstNotNullOfOrNull { it.run(command, p) } ?: err("unknown command: $command")
        }
    }

    companion object {
        const val VERSION = "0.2.0"

        private fun spec(description: String, required: JSONObject = JSONObject(), optional: JSONObject = JSONObject()) =
            JSONObject().put("description", description).put("required", required).put("optional", optional)

        private fun param(type: String, description: String) = JSONObject().put("type", type).put("description", description)

        val COMMANDS: JSONObject = JSONObject()
            .put("sifli.show_text", spec(
                "Show text on the user's SiFli desk display (a 390x450 screen next to them). Use it to put " +
                    "answers, summaries or confirmations in front of the user. Keep it under about 300 characters; " +
                    "plain ASCII renders best.",
                JSONObject().put("text", param("string", "What to show.")),
                JSONObject().put("title", param("string", "Short heading above the text."))
                    .put("style", param("string", "caption (default), card or alert.")),
            ))
            .put("sifli.notify", spec(
                "Show a short notification banner on the SiFli desk display and flash its LED.",
                JSONObject().put("text", param("string", "Notification text.")),
            ))
            .put("sifli.ask_confirm", spec(
                "Ask the user a yes/no question on the SiFli desk display and wait for them to tap Yes or No. " +
                    "Returns answer: yes, no or timeout. The user talks to you through this display and may not see " +
                    "your app, so whenever you need their approval or confirmation (sending an email, creating or " +
                    "deleting something, buying, any irreversible step), ask with this command and act on its answer.",
                JSONObject().put("question", param("string", "The question, under 80 characters.")),
                JSONObject().put("timeout_s", param("integer", "How long to wait. Default 30, max 120.")),
            ).put("timeout_ms", 130_000))
            .put("sifli.read_sensors", spec(
                "Read the SiFli desk display's status: whether it is connected, the phone's battery percent " +
                    "and charging state. (This dev board has no health sensors.)",
            ))
            .put("device.health", spec("Bridge status: software version, uptime and whether the board is connected."))
    }
}
