package com.phonebridge.muse

import com.phonebridge.muse.Proto.boolField
import com.phonebridge.muse.Proto.bytesField
import com.phonebridge.muse.Proto.stringField
import com.phonebridge.muse.Proto.varintField

/**
 * Service envelopes carried inside the Noise session: HTTP-like requests,
 * responses, body chunks and resets, multiplexed by stream id.
 * Port of muse-gadget-sdk/linux/src/musegadget/noise/envelope.py.
 */
data class Header(val key: String, val value: String)

sealed interface FrameValue

data class AppRequest(
    val verb: String,
    val path: String,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
) : FrameValue

data class AppResponse(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
) : FrameValue

data class BodyChunk(val data: ByteArray = ByteArray(0), val endBody: Boolean = false) : FrameValue

data class Reset(val code: Int = 0, val reason: String = "") : FrameValue

data class ServiceFrame(val streamId: Long, val value: FrameValue?)

internal object Envelope {
    const val SERVICE_DAEMON = 0

    private fun encodeHeader(h: Header) = Proto.build {
        if (h.key.isNotEmpty()) stringField(1, h.key)
        if (h.value.isNotEmpty()) stringField(2, h.value)
    }

    private fun decodeHeader(data: ByteArray): Header {
        var key = ""
        var value = ""
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (f, w) = r.key()
            when (f) {
                1 -> { r.expect(w, Proto.DELIMITED, "Header.key"); key = r.delimited().decodeToString(throwOnInvalidSequence = true) }
                2 -> { r.expect(w, Proto.DELIMITED, "Header.value"); value = r.delimited().decodeToString(throwOnInvalidSequence = true) }
                else -> r.skip(w)
            }
        }
        return Header(key, value)
    }

    private fun encodeRequest(req: AppRequest) = Proto.build {
        if (req.verb.isNotEmpty()) stringField(1, req.verb)
        if (req.path.isNotEmpty()) stringField(2, req.path)
        req.headers.forEach { bytesField(3, encodeHeader(it)) }
        if (req.body.isNotEmpty()) bytesField(4, req.body)
        if (req.endBody) boolField(5, true)
    }

    private fun encodeBodyChunk(c: BodyChunk) = Proto.build {
        if (c.data.isNotEmpty()) bytesField(1, c.data)
        if (c.endBody) boolField(2, true)
    }

    private fun encodeReset(r: Reset) = Proto.build {
        if (r.code != 0) varintField(1, r.code.toLong())
        if (r.reason.isNotEmpty()) stringField(2, r.reason)
    }

    private fun decodeResponse(data: ByteArray): AppResponse {
        var status = 0
        val headers = mutableListOf<Header>()
        var body = ByteArray(0)
        var end = false
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (f, w) = r.key()
            when (f) {
                1 -> { r.expect(w, Proto.VARINT, "ApplicationResponse.status"); status = r.varint().toInt() }
                2 -> { r.expect(w, Proto.DELIMITED, "ApplicationResponse.headers"); headers += decodeHeader(r.delimited()) }
                3 -> { r.expect(w, Proto.DELIMITED, "ApplicationResponse.body"); body = r.delimited() }
                4 -> { r.expect(w, Proto.VARINT, "ApplicationResponse.end_body"); end = r.varint() != 0L }
                else -> r.skip(w)
            }
        }
        return AppResponse(status, headers, body, end)
    }

    private fun decodeBodyChunk(data: ByteArray): BodyChunk {
        var bytes = ByteArray(0)
        var end = false
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (f, w) = r.key()
            when (f) {
                1 -> { r.expect(w, Proto.DELIMITED, "BodyChunk.data"); bytes = r.delimited() }
                2 -> { r.expect(w, Proto.VARINT, "BodyChunk.end_body"); end = r.varint() != 0L }
                else -> r.skip(w)
            }
        }
        return BodyChunk(bytes, end)
    }

    private fun decodeReset(data: ByteArray): Reset {
        var code = 0
        var reason = ""
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (f, w) = r.key()
            when (f) {
                1 -> { r.expect(w, Proto.VARINT, "Reset.code"); code = r.varint().toInt() }
                2 -> { r.expect(w, Proto.DELIMITED, "Reset.reason"); reason = r.delimited().decodeToString() }
                else -> r.skip(w)
            }
        }
        return Reset(code, reason)
    }

    fun encodeFrame(frame: ServiceFrame): ByteArray = Proto.build {
        if (frame.streamId != 0L) varintField(1, frame.streamId)
        when (val v = frame.value) {
            is AppRequest -> bytesField(2, encodeRequest(v))
            is BodyChunk -> bytesField(4, encodeBodyChunk(v))
            is Reset -> bytesField(5, encodeReset(v))
            is AppResponse -> throw ProtoError("device never sends responses")
            null -> Unit
        }
    }

    fun decodeFrame(data: ByteArray): ServiceFrame {
        var streamId = 0L
        var value: FrameValue? = null
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (f, w) = r.key()
            when (f) {
                1 -> { r.expect(w, Proto.VARINT, "ServiceFrame.stream_id"); streamId = r.varint() }
                2 -> throw ProtoError("unexpected request frame from server")
                3 -> { r.expect(w, Proto.DELIMITED, "ServiceFrame.response"); value = decodeResponse(r.delimited()) }
                4 -> { r.expect(w, Proto.DELIMITED, "ServiceFrame.body_chunk"); value = decodeBodyChunk(r.delimited()) }
                5 -> { r.expect(w, Proto.DELIMITED, "ServiceFrame.reset"); value = decodeReset(r.delimited()) }
                else -> r.skip(w)
            }
        }
        return ServiceFrame(streamId, value)
    }

    /** ServiceRequest { service = 1 (omitted for daemon), payload = 2 }. */
    fun encodeServiceRequest(payload: ByteArray, service: Int = SERVICE_DAEMON) = Proto.build {
        if (service != SERVICE_DAEMON) varintField(1, service.toLong())
        if (payload.isNotEmpty()) bytesField(2, payload)
    }

    /** ServiceResponse { payload = 1 }. */
    fun decodeServiceResponse(data: ByteArray): ByteArray {
        var payload = ByteArray(0)
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (f, w) = r.key()
            if (f == 1) { r.expect(w, Proto.DELIMITED, "ServiceResponse.payload"); payload = r.delimited() } else r.skip(w)
        }
        return payload
    }
}
