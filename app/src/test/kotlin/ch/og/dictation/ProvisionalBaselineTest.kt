package ch.og.dictation

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProvisionalBaselineTest {
    private fun manifest() = JSONObject(File("src/main/res/raw/candidate_a.json").readText())

    @Test fun normalSelectionMatchesTheAcceptedReplayAndIgnoresLabLiveSelection() {
        val lab = Files.createTempDirectory("baseline").toFile()
        try {
            File(lab, "issue11-dictionary.txt").writeText("synthetic term")
            File(lab, "live.json").writeText("invalid benchmark selection")
            val selected = ProvisionalBaseline.load(lab, manifest())!!
            assertEquals("candidate-a-score-1.5", selected.variant.name)
            assertEquals("parakeet-v3-fp32", selected.variant.model)
            assertEquals("modified_beam_search", selected.variant.decoding.method)
            assertEquals(8, selected.variant.decoding.maxActivePaths)
            assertEquals(1.5f, selected.variant.hotwords.score)
            assertEquals(4, selected.variant.threads)
            assertTrue(selected.variant.post.skipSilentChunks)
            assertEquals(Variant.HISTORICAL_INT8.cutting, selected.variant.cutting)
            // Nonempty altered dictionaries fail closed, never silently run unmeasured A.
            assertThrows(IllegalArgumentException::class.java) { selected.verify() }
        } finally { lab.deleteRecursively() }
    }
    @Test fun absentEmptyAndWhitespaceDictionaryUseTheUnchangedHistoricalControl() {
        val lab = Files.createTempDirectory("baseline-empty").toFile()
        try {
            assertNull(ProvisionalBaseline.load(lab, manifest()))
            for (content in listOf("", " \n\t")) {
                File(lab, "issue11-dictionary.txt").writeText(content)
                assertNull(ProvisionalBaseline.load(lab, manifest()))
            }
            assertEquals("parakeet-v3-matmul8", Variant.HISTORICAL_INT8.model)
            assertEquals("greedy_search", Variant.HISTORICAL_INT8.decoding.method)
            assertFalse(Variant.HISTORICAL_INT8.isDictionary)
            assertFalse(Variant.HISTORICAL_INT8.post.skipSilentChunks)
            assertEquals(Variant.parse("{}"), Variant.HISTORICAL_INT8)
        } finally { lab.deleteRecursively() }
    }

}
