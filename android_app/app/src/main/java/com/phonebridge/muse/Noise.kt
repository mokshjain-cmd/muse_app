package com.phonebridge.muse

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise_XX_25519_AESGCM_SHA256, initiator side, plus the session transport.
 * Port of muse-gadget-sdk/linux/src/musegadget/noise/{noise_xx,framing,transport}.py.
 */
class NoiseProtocolError(message: String) : RuntimeException(message)

/** RFC 7748 X25519. Pure Kotlin so it works on every Android version. */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    private fun decodeLE(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun encodeLE(x: BigInteger): ByteArray {
        val be = x.mod(P).toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) {
            val j = be.size - 1 - i
            if (i < 32) out[i] = be[j]
        }
        return out
    }

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val uc = u.copyOf()
        uc[31] = (uc[31].toInt() and 127).toByte()

        val kk = decodeLE(k)
        val x1 = decodeLE(uc)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (kk.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return encodeLE(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)))
    }

    fun publicKey(privateKey: ByteArray) = scalarMult(privateKey, BASE)
}

internal class KeyPair(val privateKey: ByteArray) {
    val publicKey: ByteArray = X25519.publicKey(privateKey)

    companion object {
        fun generate(random: SecureRandom = SecureRandom()) = KeyPair(ByteArray(32).also(random::nextBytes))
    }
}

internal object Crypto {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256 extract + expand. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm), info, length)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun aesGcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(input)
    }
}

private val LOW_ORDER_POINTS: List<ByteArray> = listOf(
    ByteArray(32),
    ByteArray(32).also { it[0] = 1 },
    "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
    "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
).map { if (it is String) hex(it) else it as ByteArray }

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    if (publicKey.size != 32) throw NoiseProtocolError("x25519: invalid public key length")
    if (LOW_ORDER_POINTS.any { MessageDigest.isEqual(it, publicKey) }) throw NoiseProtocolError("x25519: rejected low-order public key")
    val shared = X25519.scalarMult(privateKey, publicKey)
    if (MessageDigest.isEqual(shared, ByteArray(32))) throw NoiseProtocolError("x25519: DH produced all-zeros output")
    return shared
}

/** Noise CipherState with AES-256-GCM; nonce is 4 zero bytes + u64 big-endian. */
class CipherState internal constructor(private var key: ByteArray? = null) {
    private var nonce = 0L
    private var poisoned = false

    private fun iv(n: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (n ushr (56 - 8 * i)).toByte() }

    private fun next(): Long {
        if (poisoned) throw NoiseProtocolError("CipherState: poisoned after prior failure")
        if (nonce >= (1L shl 53) - 1) { poisoned = true; throw NoiseProtocolError("CipherState: nonce exhausted") }
        return nonce++
    }

    @Synchronized
    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        val n = next()
        return try { Crypto.aesGcm(true, k, iv(n), ad, plaintext) } catch (e: Exception) { poisoned = true; throw e }
    }

    @Synchronized
    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        val n = next()
        return try {
            Crypto.aesGcm(false, k, iv(n), ad, ciphertext)
        } catch (e: AEADBadTagException) {
            poisoned = true; throw NoiseProtocolError("CipherState: decrypt failed")
        } catch (e: Exception) {
            poisoned = true; throw e
        }
    }
}

private class SymmetricState {
    var ck = ByteArray(32)
    var h = ByteArray(32)
    var cipher = CipherState()

    fun initialize() {
        val name = "Noise_XX_25519_AESGCM_SHA256".toByteArray()
        h = ByteArray(32).also { name.copyInto(it) }
        ck = h.copyOf()
        mixHash(ByteArray(0))
    }

    fun mixHash(data: ByteArray) { h = Crypto.sha256(h, data) }

    private fun hkdf2(ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = Crypto.hmac(ck, ikm)
        val o1 = Crypto.hmac(temp, byteArrayOf(1))
        val o2 = Crypto.hmac(temp, o1, byteArrayOf(2))
        return o1 to o2
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf2(ikm)
        ck = newCk
        cipher = CipherState(tempK)
    }

    fun encryptAndHash(p: ByteArray) = cipher.encrypt(h, p).also(::mixHash)

    fun decryptAndHash(c: ByteArray) = cipher.decrypt(h, c).also { mixHash(c) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ByteArray(0))
        return CipherState(k1) to CipherState(k2)
    }
}

/** Device side of the handshake; the Muse VM is the responder. */
class NoiseXXInitiator internal constructor(private val keys: () -> KeyPair = { KeyPair.generate() }) {
    private val ss = SymmetricState().apply { initialize() }
    private var e: KeyPair? = null
    private var re: ByteArray? = null
    private var step = 0

    fun writeMessage1(): ByteArray {
        check(step == 0) { "wrong phase" }
        val eph = keys().also { e = it }
        ss.mixHash(eph.publicKey)
        ss.encryptAndHash(ByteArray(0))
        step = 1
        return eph.publicKey
    }

    fun readMessage2(msg: ByteArray): ByteArray {
        check(step == 1) { "wrong phase" }
        if (msg.size < 32 + 48 + 16) throw NoiseProtocolError("NoiseXX: message 2 too short (${msg.size})")
        val remoteE = msg.copyOfRange(0, 32).also { re = it }
        ss.mixHash(remoteE)
        ss.mixKey(dh(e!!.privateKey, remoteE))
        val remoteS = ss.decryptAndHash(msg.copyOfRange(32, 80))
        ss.mixKey(dh(e!!.privateKey, remoteS))
        val payload = ss.decryptAndHash(msg.copyOfRange(80, msg.size))
        step = 2
        return payload
    }

    fun writeMessage3(): ByteArray {
        check(step == 2) { "wrong phase" }
        val s = keys()
        val encS = ss.encryptAndHash(s.publicKey)
        ss.mixKey(dh(s.privateKey, re!!))
        val encPayload = ss.encryptAndHash(ByteArray(0))
        step = 3
        return encS + encPayload
    }

    /** Returns (send, receive) cipher states. */
    fun split(): Pair<CipherState, CipherState> {
        check(step == 3) { "wrong phase" }
        step = 4
        e = null
        re = null
        return ss.split()
    }
}

/** NoiseTransportFrame protobuf chunking (chunk_id, index, total, payload). */
internal object NoiseFraming {
    const val MAX_CHUNK_PAYLOAD = 65489
    private const val MAX_TOTAL_CHUNKS = 256
    private const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024

    fun encode(data: ByteArray, chunkId: Long): List<ByteArray> {
        val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
        if (total > MAX_TOTAL_CHUNKS) throw IllegalArgumentException("payload too large for noise framing")
        return (0 until total).map { i ->
            val chunk = data.copyOfRange(i * MAX_CHUNK_PAYLOAD, minOf(data.size, (i + 1) * MAX_CHUNK_PAYLOAD))
            Proto.build {
                with(Proto) {
                    if (chunkId != 0L) varintField(1, chunkId)
                    if (i != 0) varintField(2, i.toLong())
                    varintField(3, total.toLong())
                    if (chunk.isNotEmpty()) bytesField(4, chunk)
                }
            }
        }
    }

    class Decoder {
        private class Assembly(val total: Int, val chunks: MutableMap<Int, ByteArray> = HashMap(), var bytes: Int = 0)

        private val pending = HashMap<Long, Assembly>()

        fun decode(frame: ByteArray): ByteArray? {
            var chunkId = 0L
            var index = 0
            var total = 1
            var payload = ByteArray(0)
            val r = Proto.Reader(frame)
            while (r.hasMore()) {
                val (f, w) = r.key()
                when (f) {
                    1 -> { r.expect(w, Proto.VARINT, "chunk_id"); chunkId = r.varint() }
                    2 -> { r.expect(w, Proto.VARINT, "chunk_index"); index = r.varint().toInt() }
                    3 -> { r.expect(w, Proto.VARINT, "total_chunks"); total = r.varint().toInt() }
                    4 -> { r.expect(w, Proto.DELIMITED, "payload"); payload = r.delimited() }
                    else -> r.skip(w)
                }
            }
            if (total < 1 || total > MAX_TOTAL_CHUNKS) throw ProtoError("invalid totalChunks $total")
            if (index < 0 || index >= total) throw ProtoError("chunkIndex out of range")
            if (total == 1) return payload
            val a = pending.getOrPut(chunkId) {
                if (pending.size >= 16) throw ProtoError("too many pending assemblies")
                Assembly(total)
            }
            if (a.total != total || a.chunks.containsKey(index)) { pending.remove(chunkId); throw ProtoError("bad chunk sequence") }
            a.bytes += payload.size
            if (a.bytes > MAX_ASSEMBLY_BYTES) { pending.remove(chunkId); throw ProtoError("assembly too large") }
            a.chunks[index] = payload
            if (a.chunks.size < total) return null
            pending.remove(chunkId)
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until total) out.write(a.chunks[i]!!)
            return out.toByteArray()
        }
    }
}

/** A decrypted frame from the VM. */
data class InFrame(val streamId: Long, val value: FrameValue)

/** Encrypts device requests and decrypts VM frames over an established Noise session. */
class NoiseTransport internal constructor(
    private val send: CipherState,
    private val recv: CipherState,
    private val chunkIds: () -> Long = { SecureRandom().nextLong() },
) {
    private val decoder = NoiseFraming.Decoder()
    private var nextStreamId = 1L

    @Synchronized
    fun request(verb: String, path: String, body: ByteArray, headers: List<Header>, endBody: Boolean): Pair<Long, List<ByteArray>> {
        val id = nextStreamId++
        return id to encrypt(ServiceFrame(id, AppRequest(verb, path, headers, body, endBody)))
    }

    @Synchronized
    fun bodyChunk(streamId: Long, data: ByteArray, endBody: Boolean = false): List<ByteArray> =
        encrypt(ServiceFrame(streamId, BodyChunk(data, endBody)))

    /** Cancels a request stream (Reset code CANCELLED). */
    @Synchronized
    fun reset(streamId: Long, reason: String = "cancelled"): List<ByteArray> =
        encrypt(ServiceFrame(streamId, Reset(1, reason)))

    private fun encrypt(frame: ServiceFrame): List<ByteArray> {
        val request = Envelope.encodeServiceRequest(Envelope.encodeFrame(frame))
        return NoiseFraming.encode(request, chunkIds()).map { send.encrypt(ByteArray(0), it) }
    }

    /** Returns null while a multi-chunk frame is still being assembled. */
    @Synchronized
    fun decrypt(ciphertext: ByteArray): InFrame? {
        val plain = recv.decrypt(ByteArray(0), ciphertext)
        val whole = decoder.decode(plain) ?: return null
        val payload = Envelope.decodeServiceResponse(whole)
        if (payload.isEmpty()) throw NoiseProtocolError("empty ServiceResponse payload")
        val frame = Envelope.decodeFrame(payload)
        val value = frame.value ?: return null
        return InFrame(frame.streamId, value)
    }
}
