package com.daytrace.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.KeyGenerator

class EncryptedAudioTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun recording(block: (File, ByteArray) -> Unit) {
        val file = File.createTempFile("daytrace", ".dta")
        try {
            val pcm = ByteArray(32_000) { (it % 127).toByte() }
            EncryptedAudio.Writer(file, key, EncryptedAudio.Header(1234, "session", "segment")).use { writer ->
                writer.append(pcm, pcm.size); writer.append(pcm, 640)
            }
            block(file, pcm)
        } finally { file.delete() }
    }
    @Test fun encryptedRoundTripProducesPlayableWav() = recording { file, pcm ->
        val out = ByteArrayOutputStream()
        EncryptedAudio.exportWav(file, key, out)
        val wav = out.toByteArray()
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(32_640, ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(pcm + pcm.copyOf(640), wav.copyOfRange(44, wav.size))
        assertEquals(1020L, EncryptedAudio.scan(file).durationMs)
        assertFalse(file.readBytes().asList().windowed(64).any { it == pcm.take(64) })
    }
    @Test fun abruptDeathRecoversOnlyAuthenticatedCompleteBlocks() = recording { file, _ ->
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }
        val recovered = EncryptedAudio.scan(file, key, allowPartial = true)
        assertEquals(32_000L, recovered.pcmBytes)
        RandomAccessFile(file, "rw").use { it.setLength(recovered.validLength) }
        assertEquals(1000L, EncryptedAudio.scan(file, key).durationMs)
    }
    @Test fun tamperingIsRejectedEvenDuringRecovery() = recording { file, _ ->
        RandomAccessFile(file, "rw").use { it.seek(100); val b = it.readByte(); it.seek(100); it.writeByte(b.toInt() xor 1) }
        assertThrows(Exception::class.java) { EncryptedAudio.scan(file, key, allowPartial = true) }
    }
    @Test fun wrongKeyCannotDecrypt() = recording { file, _ ->
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { EncryptedAudio.scan(file, other) }
    }
    @Test fun incompleteTailIsRejectedForFinalizedFiles() = recording { file, _ ->
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }
        assertThrows(Exception::class.java) { EncryptedAudio.scan(file, key) }
    }
}
