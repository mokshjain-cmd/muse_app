package com.phonebridge.muse

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire encoding for the Muse Noise envelopes.
 * Port of muse-gadget-sdk/linux/src/musegadget/noise/_proto.py.
 */
class ProtoError(message: String) : IllegalArgumentException(message)

internal object Proto {
    const val VARINT = 0
    const val FIXED64 = 1
    const val DELIMITED = 2
    const val FIXED32 = 5

    fun ByteArrayOutputStream.varint(value: Long) {
        // Unsigned LEB128 over the 64-bit two's-complement value.
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) write(b or 0x80) else { write(b); return }
        }
    }

    fun ByteArrayOutputStream.key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun ByteArrayOutputStream.varintField(field: Int, value: Long) { key(field, VARINT); varint(value) }

    fun ByteArrayOutputStream.boolField(field: Int, value: Boolean) = varintField(field, if (value) 1 else 0)

    fun ByteArrayOutputStream.bytesField(field: Int, value: ByteArray) {
        key(field, DELIMITED); varint(value.size.toLong()); write(value)
    }

    fun ByteArrayOutputStream.stringField(field: Int, value: String) = bytesField(field, value.toByteArray(Charsets.UTF_8))

    /** Sequential reader over a protobuf message. */
    class Reader(private val data: ByteArray) {
        var pos = 0
            private set

        fun hasMore() = pos < data.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            for (i in 0 until 10) {
                if (pos >= data.size) throw ProtoError("truncated varint")
                val b = data[pos++].toInt() and 0xFF
                if (i == 9 && (b and 0xFE) != 0) throw ProtoError("malformed varint")
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw ProtoError("malformed varint")
        }

        /** Returns (field, wireType). */
        fun key(): Pair<Int, Int> {
            val k = varint()
            val field = (k ushr 3).toInt()
            val wire = (k and 7).toInt()
            if (field == 0 || field in 19000..19999) throw ProtoError("invalid field number")
            if (wire !in setOf(VARINT, FIXED64, DELIMITED, FIXED32)) throw ProtoError("invalid wire type")
            return field to wire
        }

        fun delimited(): ByteArray {
            val len = varint()
            if (len < 0 || pos + len > data.size) throw ProtoError("truncated delimited field")
            val out = data.copyOfRange(pos, pos + len.toInt())
            pos += len.toInt()
            return out
        }

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                FIXED64 -> advance(8)
                DELIMITED -> delimited()
                FIXED32 -> advance(4)
                else -> throw ProtoError("invalid wire type")
            }
        }

        private fun advance(n: Int) {
            if (pos + n > data.size) throw ProtoError("truncated fixed field")
            pos += n
        }

        fun expect(wire: Int, expected: Int, what: String) {
            if (wire != expected) throw ProtoError("$what wrong wire type")
        }
    }

    inline fun build(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()
}
