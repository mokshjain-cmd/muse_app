package com.phonebridge.muse

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Device side of Muse Gadget BLE pairing v5, community mode (confirm_app).
 * Port of muse-gadget-sdk/linux/src/musegadget/pairing.py.
 */
class PairingError(val status: String) : Exception(status)

object B64Url {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    private val VALID = Regex("[A-Za-z0-9_-]+")

    fun encode(b: ByteArray): String = encoder(b)
    fun decode(s: Any?, maxChars: Int = 4096): ByteArray {
        if (s !is String || s.isEmpty() || s.length > maxChars || s.length % 4 == 1 || !VALID.matches(s)) {
            throw IllegalArgumentException("invalid base64url")
        }
        return decoder(s)
    }

    // Swappable so JVM unit tests (no android.util.Base64) can use java.util.Base64.
    internal var encoder: (ByteArray) -> String = { Base64.encodeToString(it, FLAGS) }
    internal var decoder: (String) -> ByteArray = { Base64.decode(it, FLAGS) }
}

/** A P-256 key pair with its uncompressed public point (65 bytes, 0x04 prefix). */
class DeviceKey(val privateKey: PrivateKey, val publicPoint: ByteArray, val params: ECParameterSpec) {
    companion object {
        fun generate(): DeviceKey {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()
            val pub = kp.public as ECPublicKey
            return DeviceKey(kp.private, encodePoint(pub.w), pub.params)
        }

        fun p256Params(): ECParameterSpec {
            val ap = AlgorithmParameters.getInstance("EC")
            ap.init(ECGenParameterSpec("secp256r1"))
            return ap.getParameterSpec(ECParameterSpec::class.java)
        }

        fun encodePoint(w: ECPoint): ByteArray {
            fun fixed(x: BigInteger): ByteArray {
                val b = x.toByteArray()
                return when {
                    b.size == 32 -> b
                    b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                    else -> ByteArray(32 - b.size) + b
                }
            }
            return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
        }
    }
}

internal object PairingCrypto {
    const val VERSION = 5
    const val MODEL = "hatch_link"
    const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
    const val POLICY_APP = "confirm_app"
    const val POLICY_BUTTON = "confirm_press"
    const val AUTH_COMMUNITY = "none"
    const val RECORD_LABEL = "hatch-link ble setup v1"
    private val SESSION_ID_LABEL = "hatch-link session id v1".toByteArray()

    fun transcript(
        policy: String, deviceId: String, nodeId: String, mac: String, firmwareVersion: String,
        mobilePub: String, devicePub: String, mobileNonce: String, deviceNonce: String,
    ): String = listOf(
        "hatch-link-pairing-v$VERSION",
        "version=$VERSION",
        "initiator_role=mobile",
        "responder_role=link",
        "device_id=$deviceId",
        "node_id=$nodeId",
        "mac=$mac",
        "model=$MODEL",
        "firmware_version=$firmwareVersion",
        "selected_cipher_suite=$SUITE",
        "pairing_auth=$AUTH_COMMUNITY",
        "pairing_auth_epoch=0",
        "pairing_policy=$policy",
        "confirm_timeout_seconds=${if (policy == POLICY_BUTTON) 60 else 0}",
        "mobile_pub=$mobilePub",
        "device_pub=$devicePub",
        "mobile_nonce=$mobileNonce",
        "device_nonce=$deviceNonce",
    ).joinToString("\n")

    /** Returns (mobileTxKey, mobileRxKey, sessionId). */
    fun deriveKeys(ecdh: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val salt = Crypto.sha256(mobileNonce, deviceNonce, transcriptHash)
        val sessionSecret = Crypto.hkdf(ecdh, salt, RECORD_LABEL.toByteArray(), 32)
        val tx = Crypto.hkdfExpand(sessionSecret, "mobile->device".toByteArray(), 32)
        val rx = Crypto.hkdfExpand(sessionSecret, "device->mobile".toByteArray(), 32)
        val sid = Crypto.sha256(SESSION_ID_LABEL, transcriptHash, ecdh).copyOf(16)
        return Triple(tx, rx, sid)
    }

    fun nonce(direction: Int, counter: Long) =
        ByteArray(12).also { it[0] = direction.toByte(); for (i in 0 until 8) it[4 + i] = (counter ushr (56 - 8 * i)).toByte() }

    fun aad(sessionIdB64: String, direction: Int, counter: Long) =
        "$RECORD_LABEL|$sessionIdB64|${if (direction == 0) "m2d" else "d2m"}|$counter".toByteArray()

    fun ecdh(key: DeviceKey, peerPoint: ByteArray): ByteArray {
        val x = BigInteger(1, peerPoint.copyOfRange(1, 33))
        val y = BigInteger(1, peerPoint.copyOfRange(33, 65))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), key.params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(key.privateKey)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }
}

class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    private val firmwareVersion: String,
    private val sdkToken: String?,
    private val generateKey: () -> DeviceKey = DeviceKey::generate,
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also(SecureRandom()::nextBytes) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

    private var state = State.IDLE
    private var deadline = 0L
    private var rxKey: ByteArray? = null
    private var txKey: ByteArray? = null
    private var sessionId = ""
    private var rxCounter = 0L
    private var txCounter = 0L
    var generation = 0
        private set

    val confirmed: Boolean
        @Synchronized get() { expire(); return state == State.READY || state == State.PROVISIONING }

    fun deviceInfo(): JSONObject = JSONObject()
        .put("device_id", deviceId).put("mac", mac).put("model", PairingCrypto.MODEL)
        .put("pairing_protocol", PairingCrypto.VERSION).put("pairing_auth", PairingCrypto.AUTH_COMMUNITY)
        .put("pairing_auth_epoch", 0).put("pairing_policy", PairingCrypto.POLICY_APP)

    @Synchronized
    fun reset() { generation++; clear() }

    private fun clear() {
        state = State.IDLE; deadline = 0; rxKey = null; txKey = null; sessionId = ""; rxCounter = 0; txCounter = 0
    }

    private fun expire(): Boolean {
        if (state == State.IDLE || clock() <= deadline) return false
        clear()
        return true
    }

    @Synchronized
    fun handleHello(msg: JSONObject): JSONObject {
        val version = msg.opt("version")
        if (version !is Number || version is Boolean || version.toDouble() != PairingCrypto.VERSION.toDouble() ||
            msg.opt("pairing_auth") != PairingCrypto.AUTH_COMMUNITY || msg.opt("pairing_policy") != PairingCrypto.POLICY_APP
        ) throw PairingError("error_pairing_invalid_hello")

        reset()
        val mobilePub: ByteArray
        val mobileNonce: ByteArray
        try {
            mobilePub = B64Url.decode(msg.opt("mobile_pub"))
            mobileNonce = B64Url.decode(msg.opt("mobile_nonce"))
            if (mobilePub.size != 65 || mobilePub[0] != 4.toByte() || mobileNonce.size != 16) throw IllegalArgumentException()
        } catch (e: IllegalArgumentException) {
            reset(); throw PairingError("error_pairing_invalid_hello")
        }
        val key = generateKey()
        val deviceNonce = randomBytes(16)
        val transcript = PairingCrypto.transcript(
            PairingCrypto.POLICY_APP, deviceId, nodeId, mac, firmwareVersion,
            B64Url.encode(mobilePub), B64Url.encode(key.publicPoint), B64Url.encode(mobileNonce), B64Url.encode(deviceNonce),
        )
        val th = Crypto.sha256(transcript.toByteArray())
        val ecdh = try { PairingCrypto.ecdh(key, mobilePub) } catch (e: Exception) { reset(); throw PairingError("error_pairing_invalid_hello") }
        val (mobileTx, mobileRx, sid) = PairingCrypto.deriveKeys(ecdh, mobileNonce, deviceNonce, th)
        rxKey = mobileTx      // what the phone sends, we decrypt
        txKey = mobileRx      // what we send, the phone decrypts
        sessionId = B64Url.encode(sid)
        state = State.WAIT_CLIENT_FINISHED
        deadline = clock() + 60_000
        return JSONObject()
            .put("type", "pairing_ready").put("version", PairingCrypto.VERSION)
            .put("device_id", deviceId).put("node_id", nodeId).put("mac", mac).put("model", PairingCrypto.MODEL)
            .put("firmware_version", firmwareVersion).put("pairing_auth", PairingCrypto.AUTH_COMMUNITY)
            .put("pairing_auth_epoch", 0).put("pairing_policy", PairingCrypto.POLICY_APP)
            .put("device_pub", B64Url.encode(key.publicPoint)).put("device_nonce", B64Url.encode(deviceNonce))
            .put("transcript_hash", B64Url.encode(th)).put("session_id", sessionId)
    }

    @Synchronized
    fun decrypt(env: JSONObject): String {
        if (expire() || state == State.IDLE) { reset(); throw PairingError("error_pairing_decrypt") }
        try {
            if (env.opt("session_id") != sessionId) throw IllegalArgumentException("wrong session")
            val counterText = env.opt("counter") as? String ?: throw IllegalArgumentException()
            if (!counterText.matches(Regex("[0-9]+"))) throw IllegalArgumentException()
            val counter = counterText.toBigInteger()
            if (counter != BigInteger.valueOf(rxCounter)) throw IllegalArgumentException("unexpected counter")
            val ct = B64Url.decode(env.opt("ciphertext"), 16384)
            val tag = B64Url.decode(env.opt("tag"))
            if (tag.size != 16) throw IllegalArgumentException()
            val plain = Crypto.aesGcm(false, rxKey!!, PairingCrypto.nonce(0, rxCounter), PairingCrypto.aad(sessionId, 0, rxCounter), ct + tag)
            rxCounter++
            return plain.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            reset(); throw PairingError("error_pairing_decrypt")
        }
    }

    @Synchronized
    fun handleClientFinished(cmd: JSONObject): Int {
        val ok = cmd.length() == 1 && cmd.opt("action") == "pairing_client_finished" &&
            !expire() && state == State.WAIT_CLIENT_FINISHED && rxCounter == 1L
        if (!ok) { reset(); return 0 }
        generation++
        state = State.READY
        deadline = clock() + 120_000
        return generation
    }

    @Synchronized
    fun markProvisioning(): Int {
        if (!expire() && state == State.READY) { generation++; state = State.PROVISIONING; deadline = clock() + 120_000 }
        return if (state == State.PROVISIONING) generation else 0
    }

    @Synchronized
    fun isProvisioning(gen: Int) = gen != 0 && gen == generation && !expire() && state == State.PROVISIONING

    @Synchronized
    fun encryptJson(plaintext: String, gen: Int = 0): JSONObject? {
        if ((gen != 0 && gen != generation) || expire() || state == State.IDLE) return null
        val counter = txCounter
        val sealed = Crypto.aesGcm(true, txKey!!, PairingCrypto.nonce(1, counter), PairingCrypto.aad(sessionId, 1, counter), plaintext.toByteArray())
        txCounter++
        return JSONObject().put("type", "pairing_encrypted").put("session_id", sessionId).put("counter", counter.toString())
            .put("ciphertext", B64Url.encode(sealed.copyOfRange(0, sealed.size - 16)))
            .put("tag", B64Url.encode(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    fun encryptStatus(status: String, gen: Int = 0): JSONObject? {
        val msg = JSONObject().put("type", "status").put("status", status)
        if (!sdkToken.isNullOrEmpty() && status == "pairing_confirmed") msg.put("sdk_token", sdkToken)
        return encryptJson(compact(msg), gen)
    }
}

/** Python's json.dumps(separators=(",",":")) for flat objects; org.json already emits compact JSON. */
internal fun compact(o: JSONObject): String = o.toString()

/** Chunked BLE framing: 0xFE, index, total, fragment. Port of ble_framing.py. */
object BleFraming {
    private const val MAGIC = 0xFE
    private const val MAX_PACKET = 160

    fun encode(data: ByteArray, mtu: Int): List<ByteArray> {
        val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET)
        val usable = notifyMax - 3
        val frags = if (data.isEmpty()) listOf(ByteArray(0)) else (data.indices step usable).map { data.copyOfRange(it, minOf(data.size, it + usable)) }
        require(frags.size <= 255) { "message too large for BLE framing" }
        return frags.mapIndexed { i, f -> byteArrayOf(MAGIC.toByte(), i.toByte(), frags.size.toByte()) + f }
    }

    class Assembler(private val maxBytes: Int = 8192) {
        private var buf = java.io.ByteArrayOutputStream()
        private var total = 0
        private var next = 0

        fun reset() { buf = java.io.ByteArrayOutputStream(); total = 0; next = 0 }

        fun feed(p: ByteArray): ByteArray? {
            if (p.size < 3 || (p[0].toInt() and 0xFF) != MAGIC) return p
            val index = p[1].toInt() and 0xFF
            val t = p[2].toInt() and 0xFF
            if (t == 0) { reset(); return null }
            if (index == 0 || t != total) { reset(); total = t }
            if (index != next || index >= total) { reset(); return null }
            if (buf.size() + p.size - 3 > maxBytes) { reset(); return null }
            buf.write(p, 3, p.size - 3)
            next = index + 1
            if (next < total) return null
            return buf.toByteArray().also { reset() }
        }
    }
}
