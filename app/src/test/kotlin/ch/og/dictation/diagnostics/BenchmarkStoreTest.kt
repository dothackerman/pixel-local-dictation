package ch.og.dictation.diagnostics

import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** A saved recording's receipt (#91: built here, no longer in the service): the take's timing, outcome and acknowledgements. */
    @Test fun aRecordingsReceiptNamesTheTakesTimingOutcomeAndAcknowledgements() {
        val result = TakeResult(TakeOutcome.COMPLETED, "hello", linkedMapOf("captureStart" to 1_000_000_000L,
            "stopRequested" to 3_500_000_000L, "finalReady" to 3_740_000_000L), commitAccepted = true)
        val receipt = org.json.JSONObject(BenchmarkStore.receipt("0.2.5", ByteArray(64_000), result))
        assertEquals(setOf("app", "recognizer", "text", "audioSeconds", "recordingMs", "stopToFinalMs", "timingProtocol",
            "eventsNs", "outcome", "commitAccepted", "returnAccepted"), receipt.keys().asSequence().toSet())
        assertEquals("0.2.5", receipt.getString("app"))
        assertEquals("parakeet-v3-matmul8 sherpa-onnx-1.13.8", receipt.getString("recognizer"))
        assertEquals("hello", receipt.getString("text"))
        assertEquals(2.0, receipt.getDouble("audioSeconds"), 0.0)
        assertEquals(2500L, receipt.getLong("recordingMs"))
        assertEquals(240L, receipt.getLong("stopToFinalMs"))
        assertEquals("dictation-session-v1", receipt.getString("timingProtocol"))
        assertEquals(3_740_000_000L, receipt.getJSONObject("eventsNs").getLong("finalReady"))
        assertEquals("COMPLETED", receipt.getString("outcome"))
        assertTrue(receipt.getBoolean("commitAccepted"))
        assertTrue(receipt.isNull("returnAccepted"))
        val missing = org.json.JSONObject(BenchmarkStore.receipt("0.2.5", ByteArray(0), result.copy(text = null, eventsNs = emptyMap())))
        assertTrue(missing.isNull("text"))
        assertEquals("a missing event gives 0 ms", 0L, missing.getLong("recordingMs"))
        assertEquals(0L, missing.getLong("stopToFinalMs"))
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

    @Test fun regressionInterruptedResultsNeverCreateBenchmarkArtifacts() {
        val dir = File(Files.createTempDirectory("bench-interrupted").toFile(), "benchmark")
        val store = BenchmarkStore(dir)
        val completed = TakeResult(TakeOutcome.COMPLETED, "ordinary result", emptyMap(), retentionEligible = true)

        for (outcome in listOf(TakeOutcome.COMPLETED, TakeOutcome.FAILED)) {
            val recovered = TakeResult(outcome, "accepted prefix", emptyMap(),
                retentionEligible = true, interrupted = true, historySaved = true)
            for (enabled in listOf(false, true)) {
                assertNull(store.saveIfEligible(enabled, recovered, ByteArray(4), 16_000, "recovery text"))
                assertFalse(dir.exists())
            }
        }
        assertNotNull(store.saveIfEligible(true, completed, ByteArray(4), 16_000, "ordinary text"))
        assertEquals(setOf("json", "wav"), dir.listFiles()!!.map { it.extension }.toSet())
        assertFalse(BenchmarkStore.recordingEnabled(true, liveReceipt = true))
        dir.parentFile?.deleteRecursively()
    }
}
