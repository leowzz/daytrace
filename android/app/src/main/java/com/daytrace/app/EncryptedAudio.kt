package com.daytrace.app

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned, independently authenticated one-second PCM blocks; no plaintext on disk. */
object EncryptedAudio {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    private const val MAGIC = 0x44544131 // DTA1
    private const val OPUS_MAGIC = 0x44544132 // DTA2: authenticated Ogg pages + cumulative decoded PCM bytes
    data class Header(val startedAt: Long, val sessionId: String, val segmentId: String,
        val codec: String = "pcm", val vad: Boolean = false, val bitrate: Int = 0)
    data class Info(val header: Header, val pcmBytes: Long, val validLength: Long) {
        val durationMs: Long get() = pcmBytes * 1000 / BYTES_PER_SECOND
    }

    class Writer(private val file: File, private val key: SecretKey, val header: Header) : Closeable {
        private val raw = FileOutputStream(file)
        private val out = DataOutputStream(BufferedOutputStream(raw))
        private var index = 0
        var pcmBytes = 0L
            private set
        init {
            out.writeInt(if (header.codec == "opus") OPUS_MAGIC else MAGIC)
            out.writeLong(header.startedAt)
            out.writeUTF(header.sessionId)
            out.writeUTF(header.segmentId)
            if (header.codec == "opus") { out.writeBoolean(header.vad); out.writeInt(header.bitrate) }
        }
        fun append(pcm: ByteArray, count: Int) {
            require(count in 2..BYTES_PER_SECOND && count % 2 == 0)
            require(header.codec == "pcm")
            appendPayload(pcm.copyOf(count), pcmBytes + count)
        }
        fun appendPayload(payload: ByteArray, cumulativePcmBytes: Long) {
            require(payload.size in 1..65_536)
            require(cumulativePcmBytes >= pcmBytes && cumulativePcmBytes % 2 == 0L)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(header, index, cumulativePcmBytes))
            val encrypted = cipher.doFinal(payload)
            check(cipher.iv.size == 12)
            out.writeInt(encrypted.size)
            if (header.codec == "opus") out.writeLong(cumulativePcmBytes)
            out.write(cipher.iv)
            out.write(encrypted)
            out.flush()
            pcmBytes = cumulativePcmBytes
            index++
            if (index % 5 == 0) raw.fd.sync()
        }
        override fun close() {
            try { out.flush(); raw.fd.sync() } finally { out.close() }
        }
    }

    private fun aad(h: Header, index: Int, bytes: Long) =
        (if (h.codec == "opus") "DTA2|${h.startedAt}|${h.sessionId}|${h.segmentId}|${h.vad}|${h.bitrate}|$index|$bytes"
        else "DTA1|${h.startedAt}|${h.sessionId}|${h.segmentId}|$index").toByteArray(Charsets.UTF_8)

    /** Only a physically incomplete trailing block is recoverable. Authentication failures are fatal. */
    fun scan(file: File, key: SecretKey? = null, pcm: OutputStream? = null, allowPartial: Boolean = false,
        opusComments: Map<String, String>? = null): Info {
        RandomAccessFile(file, "r").use { input ->
            val magic = input.readInt()
            require(magic == MAGIC || magic == OPUS_MAGIC) { "录音格式不正确" }
            val basic = Header(input.readLong(), input.readUTF(), input.readUTF())
            val header = if (magic == OPUS_MAGIC) basic.copy(codec = "opus", vad = input.readBoolean(), bitrate = input.readInt()) else basic
            var validLength = input.filePointer
            var bytes = 0L
            var index = 0
            while (input.filePointer < input.length()) {
                val remaining = input.length() - input.filePointer
                val recordHeader = if (magic == OPUS_MAGIC) 12 else 4
                if (remaining < recordHeader && allowPartial) break
                val length = input.readInt()
                val cumulative = if (magic == OPUS_MAGIC) input.readLong() else bytes + length - 16
                require(length in 17..65_552 && cumulative >= bytes && cumulative % 2 == 0L) { "录音块损坏" }
                if (input.length() - input.filePointer < 12 + length) {
                    if (allowPartial) break else throw EOFException("录音块未完整保存")
                }
                if (key != null) {
                    val iv = ByteArray(12).also { input.readFully(it) }
                    val encrypted = ByteArray(length).also { input.readFully(it) }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    cipher.updateAAD(aad(header, index, cumulative))
                    val plain = cipher.doFinal(encrypted)
                    // A recovered stream may end on a normal page; mark its last complete audio page EOS.
                    if (header.codec == "opus" && input.filePointer == input.length() && cumulative > 0) OggOpus.markEndOfStream(plain)
                    if (header.codec == "opus" && index == 1 && opusComments != null)
                        pcm?.write(OggOpus.withComments(plain, opusComments))
                    else pcm?.write(plain)
                } else input.seek(input.filePointer + 12 + length)
                bytes = cumulative
                validLength = input.filePointer
                index++
            }
            return Info(header, bytes, validLength)
        }
    }

    fun exportWav(file: File, key: SecretKey, output: OutputStream) {
        val info = scan(file)
        require(info.header.codec == "pcm") { "请将压缩录音导出为 Opus" }
        output.write(wavHeader(info.pcmBytes))
        scan(file, key, output)
    }

    fun export(file: File, key: SecretKey, output: OutputStream) {
        val info = scan(file)
        if (info.header.codec == "opus") scan(file, key, output, opusComments = Timeline.opusComments(info))
        else exportWav(file, key, output)
    }

    fun wavHeader(pcmBytes: Long): ByteArray {
        require(pcmBytes in 0..(0xffffffffL - 36))
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((pcmBytes + 36).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(BYTES_PER_SECOND)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmBytes.toInt())
        }.array()
    }
}
