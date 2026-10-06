package com.phonebridge.muse

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec

/**
 * Checks the Kotlin port byte-for-byte against Meta's reference client:
 * noise_vectors.json comes from tools/gen_vectors.py (the Python SDK with
 * fixed keys), link_pairing_v5.json ships with the SDK.
 */
class MuseProtocolTest {

    companion object {
        @BeforeClass @JvmStatic
        fun useJvmBase64() {
            B64Url.encoder = { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            B64Url.decoder = { java.util.Base64.getUrlDecoder().decode(it) }
        }

        private fun resource(name: String) =
            JSONObject(MuseProtocolTest::class.java.classLoader!!.getResource(name)!!.readText())

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun JSONObject.hexList(key: String) = getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
    }

    @Test
    fun x25519_rfc7748() {
        val out = X25519.scalarMult(
            hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
        )
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", out.toHex())
    }

    @Test
    fun noiseHandshakeAndTransport_matchPython() {
        val v = resource("noise_vectors.json")
        val keys = v.getJSONObject("keys")
        val order = ArrayDeque(listOf(KeyPair(hex(keys.getString("init_e"))), KeyPair(hex(keys.getString("init_s")))))
        val init = NoiseXXInitiator { order.removeFirst() }

        assertEquals(v.getString("msg1"), init.writeMessage1().toHex())
        assertEquals(v.getString("msg2_payload"), init.readMessage2(hex(v.getString("msg2"))).toHex())
        assertEquals(v.getString("msg3"), init.writeMessage3().toHex())

        val (send, recv) = init.split()
        val ids = ArrayDeque(listOf(1000L, 1001L, 1002L))
        val t = NoiseTransport(send, recv) { ids.removeFirst() }

        val (ctrlId, ctrlFrames) = t.request("POST", "/link-control", ByteArray(0), emptyList(), endBody = false)
        assertEquals(v.getLong("control_open_stream_id"), ctrlId)
        assertEquals(v.hexList("control_open_frames"), ctrlFrames.map { it.toHex() })

        // Same JSON text as Python's compact json.dumps, so framing/encryption are what's compared.
        val register = """{"type":"req","id":"r1","method":"link.register","params":{"node_id":"homelink-abcdef"}}""".toByteArray()
        val prefixed = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(register.size).array() + register
        assertEquals(v.hexList("register_frames"), t.bodyChunk(ctrlId, prefixed).map { it.toHex() })

        val (chatId, chatFrames) = t.request(
            "POST", "/chat/stream", """{"message":"hi"}""".toByteArray(),
            listOf(Header("Content-Type", "application/json"), Header("x-app-id", "musegadget")), endBody = true,
        )
        assertEquals(v.getLong("chat_stream_id"), chatId)
        assertEquals(v.hexList("chat_frames"), chatFrames.map { it.toHex() })

        // VM -> device: response carrying a link.invoke, then a final body chunk.
        val resp = v.hexList("server_response_frames").mapNotNull { t.decrypt(hex(it)) }.single()
        assertEquals(ctrlId, resp.streamId)
        val app = resp.value as AppResponse
        assertEquals(200, app.status)
        val msgs = LinkSession.MessageDecoder().feed(app.body)
        assertEquals("sifli.show_text", msgs.single().getString("command"))
        assertEquals("Hello", msgs.single().getJSONObject("params").getString("text"))

        val chunk = v.hexList("server_chunk_frames").mapNotNull { t.decrypt(hex(it)) }.single().value as BodyChunk
        assertEquals("more", chunk.data.decodeToString())
        assertTrue(chunk.endBody)
    }

    private fun deviceKeyFromScalar(scalar: String, pubB64: String): DeviceKey {
        val params = DeviceKey.p256Params()
        val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(scalar, 16), params))
        return DeviceKey(priv, B64Url.decode(pubB64), params)
    }

    @Test
    fun pairingCrypto_matchesSdkVectors() {
        val v = resource("link_pairing_v5.json").getJSONArray("vectors").getJSONObject(0)
        assertEquals("community_v5", v.getString("name"))

        val transcript = PairingCrypto.transcript(
            v.getString("pairing_policy"), v.getString("device_id"), v.getString("node_id"), v.getString("mac"),
            v.getString("firmware_version"), v.getString("mobile_pub"), v.getString("device_pub"),
            v.getString("mobile_nonce"), v.getString("device_nonce"),
        )
        assertEquals(v.getString("transcript"), transcript)
        val th = Crypto.sha256(transcript.toByteArray())
        assertEquals(v.getString("transcript_hash"), B64Url.encode(th))

        val device = deviceKeyFromScalar(v.getString("device_private_scalar_hex"), v.getString("device_pub"))
        val ecdh = PairingCrypto.ecdh(device, B64Url.decode(v.getString("mobile_pub")))
        assertEquals(v.getString("ecdh_secret_hex"), ecdh.toHex())

        val (tx, rx, sid) = PairingCrypto.deriveKeys(ecdh, B64Url.decode(v.getString("mobile_nonce")), B64Url.decode(v.getString("device_nonce")), th)
        assertEquals(v.getString("mobile_tx_key_hex"), tx.toHex())
        assertEquals(v.getString("mobile_rx_key_hex"), rx.toHex())
        assertEquals(v.getString("session_id"), B64Url.encode(sid))

        val aad = PairingCrypto.aad(B64Url.encode(sid), 0, 0)
        assertEquals(v.getString("client_finished_aad"), aad.decodeToString())
        val sealed = Crypto.aesGcm(true, tx, PairingCrypto.nonce(0, 0), aad, v.getString("client_finished_plaintext").toByteArray())
        assertEquals(v.getString("client_finished_ciphertext"), B64Url.encode(sealed.copyOfRange(0, sealed.size - 16)))
        assertEquals(v.getString("client_finished_tag"), B64Url.encode(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    /** Plays the Muse app's side of a community pairing against PairingSession. */
    @Test
    fun pairingSession_fullHandshake() {
        val v = resource("link_pairing_v5.json").getJSONArray("vectors").getJSONObject(0)
        val session = PairingSession(
            nodeId = "homelink-000001", deviceId = "hatch-link:02:00:00:00:00:01", mac = "02:00:00:00:00:01",
            firmwareVersion = "1.0.0", sdkToken = "mgst_test",
            generateKey = { deviceKeyFromScalar(v.getString("device_private_scalar_hex"), v.getString("device_pub")) },
            randomBytes = { B64Url.decode(v.getString("device_nonce")) },
        )
        val ready = session.handleHello(
            JSONObject().put("action", "pairing_client_hello").put("version", 5).put("pairing_auth", "none")
                .put("pairing_policy", "confirm_app").put("mobile_pub", v.getString("mobile_pub"))
                .put("mobile_nonce", v.getString("mobile_nonce"))
        )
        assertEquals("pairing_ready", ready.getString("type"))

        // Mobile side: recompute keys from its own private scalar (1).
        val mobile = deviceKeyFromScalar(v.getString("mobile_private_scalar_hex"), v.getString("mobile_pub"))
        val ecdh = PairingCrypto.ecdh(mobile, B64Url.decode(ready.getString("device_pub")))
        val th = B64Url.decode(ready.getString("transcript_hash"))
        val transcript = PairingCrypto.transcript("confirm_app", "hatch-link:02:00:00:00:00:01", "homelink-000001",
            "02:00:00:00:00:01", "1.0.0", v.getString("mobile_pub"), ready.getString("device_pub"),
            v.getString("mobile_nonce"), ready.getString("device_nonce"))
        assertArrayEquals(Crypto.sha256(transcript.toByteArray()), th)
        val (tx, rx, sid) = PairingCrypto.deriveKeys(ecdh, B64Url.decode(v.getString("mobile_nonce")), B64Url.decode(ready.getString("device_nonce")), th)
        val sidB64 = B64Url.encode(sid)
        assertEquals(ready.getString("session_id"), sidB64)

        val sealed = Crypto.aesGcm(true, tx, PairingCrypto.nonce(0, 0), PairingCrypto.aad(sidB64, 0, 0),
            """{"action":"pairing_client_finished"}""".toByteArray())
        val env = JSONObject().put("type", "pairing_encrypted").put("session_id", sidB64).put("counter", "0")
            .put("ciphertext", B64Url.encode(sealed.copyOfRange(0, sealed.size - 16)))
            .put("tag", B64Url.encode(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        val plain = session.decrypt(env)
        val gen = session.handleClientFinished(JSONObject(plain))
        assertNotEquals(0, gen)
        assertTrue(session.confirmed)

        val status = session.encryptStatus("pairing_confirmed", gen)!!
        val opened = Crypto.aesGcm(false, rx, PairingCrypto.nonce(1, 0), PairingCrypto.aad(sidB64, 1, 0),
            B64Url.decode(status.getString("ciphertext"), 16384) + B64Url.decode(status.getString("tag")))
        val msg = JSONObject(opened.decodeToString())
        assertEquals("pairing_confirmed", msg.getString("status"))
        assertEquals("mgst_test", msg.getString("sdk_token"))
    }

    @Test
    fun bleFraming_matchesPython() {
        val v = resource("noise_vectors.json")
        val packets = BleFraming.encode(ByteArray(50) { 'x'.code.toByte() }, 23)
        assertEquals(v.hexList("ble_chunks_mtu23"), packets.map { it.toHex() })
        val asm = BleFraming.Assembler()
        val whole = packets.mapNotNull { asm.feed(it) }.single()
        assertEquals("x".repeat(50), whole.decodeToString())
    }

    @Test
    fun identity_fromBoardAddress() {
        val id = MuseIdentity.fromBoardAddress("11:22:33:ab:cd:ef")
        assertEquals("12:22:33:ab:cd:ef", id.mac)
        assertEquals("homelink-abcdef", id.nodeId)
        assertEquals("MuseGadgetABCDEF", id.bleName)
    }
}
