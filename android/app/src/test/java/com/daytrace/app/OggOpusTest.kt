package com.daytrace.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.KeyGenerator

class OggOpusTest {
    private class Encoder : PacketEncoder {
        override val lookahead = 104
        override fun encode(pcm: ByteArray) = ByteArray(40) { 7 }
        override fun close() {}
    }
    private fun granule(page: ByteArray) = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getLong(6)
    @Test fun headersPreskipAndFinalGranuleAccountForDelay() {
        val pages = mutableListOf<ByteArray>(); val sizes = mutableListOf<Long>()
        OggOpus(Encoder(), 123) { page, bytes -> pages += page; sizes += bytes }.use { ogg ->
            repeat(50) { ogg.append(ByteArray(640)) }
        }
        assertEquals(4, pages.size)
        assertEquals(2, pages[0][5].toInt())
        assertEquals("OpusHead", String(pages[0], 28, 8))
        assertEquals(312, ByteBuffer.wrap(pages[0]).order(ByteOrder.LITTLE_ENDIAN).getShort(38).toInt())
        assertEquals(48000L, granule(pages[2]))
        assertEquals(48312L, granule(pages[3]))
        assertEquals(4, pages.last()[5].toInt())
        assertEquals(32000L, sizes.last())
        pages.forEach { page ->
            val stored = page.copyOfRange(22, 26)
            OggOpus.updateCrc(page)
            assertArrayEquals(stored, page.copyOfRange(22, 26))
        }
    }
    @Test fun encryptedOpusRoundTripAndTruncatedTailRecovery() {
        val file = File.createTempFile("opus-test", ".part")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            EncryptedAudio.Writer(file, key, EncryptedAudio.Header(123, "s", "id", "opus", true, 16000)).use { writer ->
                OggOpus(Encoder(), 456) { page, bytes -> writer.appendPayload(page, bytes) }.use { ogg ->
                    repeat(60) { ogg.append(ByteArray(640)) }
                }
            }
            assertEquals(1200L, EncryptedAudio.scan(file, key).durationMs)
            val out = ByteArrayOutputStream(); EncryptedAudio.export(file, key, out)
            assertEquals("OggS", String(out.toByteArray(), 0, 4))
            RandomAccessFile(file, "rw").use { it.setLength(it.length() - 10) }
            val recovered = EncryptedAudio.scan(file, key, allowPartial = true)
            assertEquals(993L, recovered.durationMs)
            RandomAccessFile(file, "rw").use { it.setLength(recovered.validLength) }
            val recoveredOut = ByteArrayOutputStream(); EncryptedAudio.export(file, key, recoveredOut)
            val data = recoveredOut.toByteArray()
            var offset = 0; var lastFlags = 0
            while (offset < data.size) {
                lastFlags = data[offset + 5].toInt()
                val count = data[offset + 26].toInt() and 255
                val size = (0 until count).sumOf { data[offset + 27 + it].toInt() and 255 }
                offset += 27 + count + size
            }
            assertEquals(4, lastFlags)
        } finally { file.delete() }
    }
}
