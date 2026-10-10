package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VariantTest {
    @Test fun emptyObjectIsTheShippedPipeline() {
        assertEquals(Variant.DEFAULT, Variant.parse("{}"))
        assertEquals(Variant.DEFAULT, Variant.parse("""{"schema": 1, "name": "baseline", "family": "parakeet", "threads": 4}"""))
    }

    @Test fun valuesOverrideDefaultsSectionBySection() {
        val v = Variant.parse("""{"name": "w", "family": "whisper", "model": "sherpa-onnx-whisper-turbo",
            "cutting": {"min_chunk_s": 10, "vad_max_speech_s": 15, "max_chunk_s": 26}, "post": {"seam_capitalize": true}}""")
        assertEquals("whisper", v.family)
        assertEquals(10f, v.cutting.minChunkS)
        assertEquals(26f, v.cutting.maxChunkS)
        assertEquals(0.4f, v.cutting.padS)   // untouched default
        assertEquals(true, v.post.seamCapitalize)
    }

    @Test fun unknownKeysAreRejectedBecauseTheSchemaIsClosed() {
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"hotwords": ["Winterthur"]}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"cutting": {"overlap_s": 1}}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"schema": 2}""") }
    }

    @Test fun familyRulesMatchThePythonSchema() {
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "whisper", "model": "x"}""") }  // needs max_chunk_s <= 28
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "whisper", "model": "x", "cutting": {"max_chunk_s": 30}}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "canary", "model": "x"}""") }   // needs lid.model
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"lid": {"model": "tiny"}}""") }           // lid only with canary
        Variant.parse("""{"family": "canary", "model": "x", "lid": {"model": "tiny"}}""")
    }
}
