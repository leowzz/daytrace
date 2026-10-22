package com.daytrace.app

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RFC 7845: mono Opus, 48 kHz granule clock, encoder preskip, EOS trimming, Ogg CRC. */
class OggOpus(private val encoder: PacketEncoder, private val serial: Int,
    private val output: (ByteArray, Long) -> Unit) : Closeable {
    private var sequence = 0
    private var encodedSamples = 0L
    private var actualSamples = 0L
    private var closed = false
    private val packets = mutableListOf<ByteArray>()
    private val preSkip = encoder.lookahead * 3
    init {
        require(preSkip in 0..65535)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray()); put(1); put(1); putShort(preSkip.toShort())
            putInt(16_000); putShort(0); put(0)
        }.array()
        output(page(listOf(head), 0, 2), 0)
        val vendor = "DayTrace / libopus 1.6.1".toByteArray()
        val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray()); putInt(vendor.size); put(vendor); putInt(0)
        }.array()
        output(page(listOf(tags), 0, 0), 0)
    }
    fun append(frame: ByteArray) {
        check(!closed); require(frame.size == 640)
        addPacket(frame)
        actualSamples += 320
    }
    private fun addPacket(frame: ByteArray) {
        if (packets.size == 50) flush(encodedSamples * 3, false)
        packets += encoder.encode(frame)
        encodedSamples += 320
    }
    private fun flush(granule: Long, eos: Boolean) {
        if (packets.isEmpty()) return
        output(page(packets, granule, if (eos) 4 else 0), maxOf(0, granule - preSkip) / 3 * 2)
        packets.clear()
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (actualSamples > 0) {
                // Feed enough zeros to drain encoder delay, then trim padded samples through EOS granule.
                while (encodedSamples < actualSamples + encoder.lookahead) addPacket(ByteArray(640))
                flush(actualSamples * 3 + preSkip, true)
            }
        } finally { encoder.close() }
    }
    private fun page(packets: List<ByteArray>, granule: Long, flags: Int): ByteArray {
        val lacing = ByteArrayOutputStream()
        packets.forEach { packet ->
            var remaining = packet.size
            while (remaining >= 255) { lacing.write(255); remaining -= 255 }
            lacing.write(remaining)
        }
        require(lacing.size() <= 255)
        val buffer = ByteBuffer.allocate(27 + lacing.size() + packets.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OggS".toByteArray()); buffer.put(0); buffer.put(flags.toByte()); buffer.putLong(granule)
        buffer.putInt(serial); buffer.putInt(sequence++); buffer.putInt(0); buffer.put(lacing.size().toByte())
        buffer.put(lacing.toByteArray()); packets.forEach { buffer.put(it) }
        return buffer.array().also { updateCrc(it) }
    }
    companion object {
        /** Replace our single-packet OpusTags page, preserving serial/sequence and all audio pages. */
        fun withComments(page: ByteArray, comments: Map<String, String>): ByteArray {
            require(page.size >= 28 && String(page, 0, 4, Charsets.US_ASCII) == "OggS")
            val segments = page[26].toInt() and 255
            val payload = 27 + segments
            require(segments > 0 && payload + 16 <= page.size)
            require((0 until segments - 1).all { (page[27 + it].toInt() and 255) == 255 })
            require((page[26 + segments].toInt() and 255) < 255)
            require(String(page, payload, 8, Charsets.US_ASCII) == "OpusTags")
            val vendorSize = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getInt(payload + 8)
            require(vendorSize in 0..(page.size - payload - 16))
            val vendor = page.copyOfRange(payload + 12, payload + 12 + vendorSize)
            val tags = comments.map { (key, value) ->
                require(key.matches(Regex("[A-Z0-9_]+")))
                "$key=$value".toByteArray(Charsets.UTF_8)
            }
            val packet = ByteBuffer.allocate(16 + vendor.size + tags.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusTags".toByteArray()); putInt(vendor.size); put(vendor); putInt(tags.size)
                tags.forEach { putInt(it.size); put(it) }
            }.array()
            val lacingCount = packet.size / 255 + 1
            require(lacingCount <= 255)
            val result = ByteArray(27 + lacingCount + packet.size)
            page.copyInto(result, 0, 0, 27)
            result[26] = lacingCount.toByte()
            repeat(lacingCount - 1) { result[27 + it] = 255.toByte() }
            result[27 + lacingCount - 1] = (packet.size % 255).toByte()
            packet.copyInto(result, 27 + lacingCount)
            return result.also(::updateCrc)
        }

        fun markEndOfStream(page: ByteArray) {
            require(page.size >= 27 && String(page, 0, 4, Charsets.US_ASCII) == "OggS")
            page[5] = (page[5].toInt() or 4).toByte(); updateCrc(page)
        }
        fun updateCrc(page: ByteArray) {
            for (i in 22..25) page[i] = 0
            var crc = 0
            for (byte in page) {
                crc = crc xor ((byte.toInt() and 255) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        }
    }
}
