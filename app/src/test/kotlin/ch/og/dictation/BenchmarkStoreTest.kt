package ch.og.dictation

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkStoreTest {
    @Test fun wavHeaderDescribes16kMonoPcm() {
        val pcm = ByteArray(32_000) { it.toByte() }
        val wav = BenchmarkStore.wav(pcm, 16_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8)); assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()); assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24)); assertEquals(32_000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt()); assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4)); assertEquals(pcm.size, b.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test fun saveCountDelete() {
        val dir = File(Files.createTempDirectory("bench").toFile(), "benchmark")
        val store = BenchmarkStore(dir)
        assertEquals(0, store.takeCount()); assertTrue(store.deleteAll())
        store.save(ByteArray(4), 16_000, "{}", "a")
        store.save(ByteArray(4), 16_000, "{}", "b")
        assertEquals(2, store.takeCount())
        assertEquals("{}", File(dir, "a.json").readText()); assertEquals(48L, File(dir, "a.wav").length())
        assertTrue(store.deleteAll())
        assertFalse(dir.exists()); assertEquals(0, store.takeCount())
    }
}
