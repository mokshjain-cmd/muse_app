package com.phonebridge.muse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Answers the Muse app's BLE setup commands. The board relays raw GATT packets
 * between the Muse app and this class (see BoardProtocol "mrx"/"mtx").
 * Port of muse-gadget-sdk/linux/src/musegadget/ble_setup.py.
 */
class SetupController(
    private val scope: CoroutineScope,
    private val pairing: PairingSession,
    private val identity: MuseIdentity,
    private val version: String,
    private val sendPackets: suspend (List<ByteArray>) -> Unit,
    private val mtu: () -> Int,
    private val isOnline: () -> Boolean,
    /** Verifies and saves credentials; throws [ProvisionFailed] on failure. */
    private val provision: suspend (Credentials) -> Unit,
    private val onComplete: () -> Unit,
    private val log: (String) -> Unit,
) {
    data class Credentials(
        val accessToken: String, val refreshToken: String, val username: String,
        val apiUrl: String, val apiUrlV2: String, val noiseHost: String,
    )

    class ProvisionFailed(val status: String) : Exception(status)

    private val assembler = BleFraming.Assembler()
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var plaintextBlocked = false
    private var provisioning = false

    init {
        scope.launch { for (m in inbox) runCatching { handle(m, false) }.onFailure { log("setup error: ${it.message}") } }
    }

    fun onWrite(packet: ByteArray) {
        assembler.feed(packet)?.let { inbox.trySend(it) }
    }

    fun onDisconnect() {
        assembler.reset()
        plaintextBlocked = false
        pairing.reset()
    }

    fun close() = inbox.close()

    private suspend fun handle(raw: ByteArray, decrypted: Boolean) {
        val cmd = try {
            JSONObject(raw.decodeToString(throwOnInvalidSequence = true))
        } catch (e: Exception) {
            sendStatus("error_invalid_command"); return
        }
        val action = cmd.opt("action") as? String ?: ""
        log("Muse app: $action${if (decrypted) " (encrypted)" else ""}")
        when {
            !decrypted && action == "pairing_client_hello" -> try {
                val ready = pairing.handleHello(cmd)
                plaintextBlocked = true
                sendJson(ready)
            } catch (e: PairingError) { sendStatus(e.status) }

            !decrypted && action == "pairing_encrypted" -> {
                val plain = try { pairing.decrypt(cmd) } catch (e: PairingError) { sendStatus(e.status); return }
                handle(plain.toByteArray(), true)
            }

            action == "get_device_info" -> sendJson(deviceInfo())
            !decrypted && plaintextBlocked -> log("plaintext command ignored after pairing started")
            !decrypted && action in SENSITIVE -> sendStatus("error_encryption_required")
            decrypted && action == "pairing_client_finished" -> {
                val gen = pairing.handleClientFinished(cmd)
                if (gen == 0) sendStatus("error_pairing_decrypt")
                else { log("Pairing confirmed in the Muse app"); sendStatus("pairing_confirmed", gen) }
            }
            decrypted && action in SENSITIVE && !pairing.confirmed -> sendStatus("error_pairing_confirm_required")
            decrypted && action == "wifi_scan" -> {
                val nets = if (isOnline()) JSONArray().put(
                    JSONObject().put("ssid", "Use current connection").put("rssi", -40).put("secure", false)
                ) else JSONArray()
                sendEncrypted(JSONObject().put("type", "wifi_scan_result").put("networks", nets))
            }
            decrypted && action == "provision_v2" -> handleProvision(cmd)
            else -> sendStatus("error_unknown_action")
        }
    }

    private fun deviceInfo(): JSONObject {
        val o = JSONObject().put("type", "device_info").put("node_id", identity.nodeId).put("version", version)
        val p = pairing.deviceInfo()
        p.keys().forEach { o.put(it, p.get(it)) }
        return o.put("build_sha", "").put("network_ready", isOnline())
    }

    private suspend fun handleProvision(cmd: JSONObject) {
        fun text(k: String) = cmd.opt(k) as? String ?: ""
        if (cmd.opt("ssid") !is String || cmd.opt("password") !is String || text("access_token").isEmpty() ||
            text("refresh_token").isEmpty() || text("token_type") != "device"
        ) { sendStatus("error_missing_credentials"); return }
        if (provisioning) { sendStatus("error_operation_in_progress"); return }
        val gen = pairing.markProvisioning()
        if (gen == 0) { sendStatus("error_pairing_confirm_required"); return }
        provisioning = true
        // Wi-Fi fields are ignored: the phone is already online.
        val creds = Credentials(text("access_token"), text("refresh_token"), text("username"),
            text("api_url"), text("api_url_v2"), text("noise_host"))
        scope.launch {
            try {
                sendStatus("wifi_connecting", gen)
                if (!isOnline()) { sendStatus("wifi_failed", gen); return@launch }
                sendStatus("wifi_connected", gen)
                try {
                    if (!pairing.isProvisioning(gen)) throw ProvisionFailed("error_storage")
                    provision(creds)
                } catch (e: ProvisionFailed) {
                    log("Setup failed: ${e.status}")
                    sendStatus(e.status, gen)
                    return@launch
                }
                sendStatus("auth_ok", gen)
                log("Muse setup complete")
                onComplete()
            } finally {
                provisioning = false
            }
        }
    }

    // ---- sending (serialized so encrypted counters reach the phone in order)

    private val txLock = Mutex()

    private suspend fun sendStatus(status: String, gen: Int = 0) = txLock.withLock {
        val env = pairing.encryptStatus(status, gen)
        if (env != null) { sendRaw(compact(env)); return@withLock }
        if (gen != 0 || plaintextBlocked || status !in PLAINTEXT_STATUSES) return@withLock
        sendPackets(listOf(status.toByteArray()))
    }

    private suspend fun sendJson(o: JSONObject) = txLock.withLock { sendRaw(compact(o)) }

    private suspend fun sendEncrypted(o: JSONObject) = txLock.withLock {
        pairing.encryptJson(compact(o))?.let { sendRaw(compact(it)) }
    }

    private suspend fun sendRaw(text: String) = sendPackets(BleFraming.encode(text.toByteArray(), mtu()))

    companion object {
        private val SENSITIVE = setOf("provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth")
        private val PLAINTEXT_STATUSES = setOf(
            "error_encryption_required", "error_pairing_invalid_hello", "error_pairing_unavailable", "error_pairing_decrypt",
        )
    }
}
