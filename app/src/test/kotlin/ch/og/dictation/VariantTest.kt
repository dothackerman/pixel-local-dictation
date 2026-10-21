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
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"dictionary": ["x"]}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"hotwords": ["x"]}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"cutting": {"overlap_s": 1}}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"schema": 5}""") }
    }

    @Test fun familyRulesMatchThePythonSchema() {
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "whisper", "model": "x"}""") }  // needs max_chunk_s <= 28
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "whisper", "model": "x", "cutting": {"max_chunk_s": 30}}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "canary", "model": "x"}""") }   // needs lid.model
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"lid": {"model": "tiny"}}""") }           // lid only with canary
        Variant.parse("""{"family": "canary", "model": "x", "lid": {"model": "tiny"}}""")
    }

    @Test fun dictionaryRules() {
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"hotwords": {"file": "dictionary.txt"}}""") }  // greedy
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"hotwords": {"file": "../x.txt"}, "decoding": {"method": "modified_beam_search"}}""") }
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("""{"family": "whisper", "model": "x", "cutting": {"max_chunk_s": 26}, "hotwords": {"file": "d.txt"}}""") }
        val v = Variant.parse("""{"hotwords": {"file": "dictionary.txt", "score": 2.0}, "decoding": {"method": "modified_beam_search"}, "post": {"skip_silent_chunks": true}}""")
        assertEquals(true, v.isDictionary)
        assertEquals(true, v.post.skipSilentChunks)
        assertEquals(Variant.DEFAULT, Variant.parse("""{"schema": 1}"""))
    }

    @Test fun oldQwenVariantsGetBoundedAudioAndGenerationHeadroom() {
        for (schema in listOf(1, 2)) {
            val v = Variant.parse("""{"schema": $schema, "family": "qwen3"}""")
            assertEquals(1024, v.qwen3.maxTotalLen)
            assertEquals(256, v.qwen3.maxNewTokens)
            assertEquals(40f, v.cutting.maxChunkS)
        }
    }

    @Test fun qwenRejectsUnboundedOrUnderbudgetedAudio() {
        for (json in listOf(
            """{"family":"qwen3"}""",
            """{"family":"qwen3","cutting":{"max_chunk_s":41}}""",
            """{"family":"qwen3","cutting":{"max_chunk_s":40},"qwen3":{"max_total_len":512}}""",
            """{"family":"qwen3","cutting":{"max_chunk_s":40},"qwen3":{"max_new_tokens":128}}""",
            """{"family":"qwen3","cutting":{"max_chunk_s":40},"qwen3":{"max_total_len":"1024"}}""",
        )) assertThrows(IllegalArgumentException::class.java) { Variant.parse(json) }
    }

    @Test fun explicitQwenBudgetsAndOtherFamilyDefaults() {
        val v = Variant.parse("""{"schema":3,"family":"qwen3","cutting":{"max_chunk_s":25},
            "qwen3":{"max_total_len":2048,"max_new_tokens":512}}""")
        assertEquals(2048, v.qwen3.maxTotalLen)
        assertEquals(512, v.qwen3.maxNewTokens)
        for (schema in listOf(1, 2, 3)) assertEquals(Variant.DEFAULT, Variant.parse("""{"schema":$schema}"""))
    }
    @org.junit.Test fun allocationSettingsAreExplicitAndStrict() {
        val v = Variant.parse("""{"schema":4,"runtime":{"model_arena":false,"lid_pattern":false}}""")
        assertEquals(false, v.runtime.modelArena)
        assertEquals(true, v.runtime.modelPattern)
        assertEquals(false, v.runtime.lidPattern)
        assertThrows(IllegalArgumentException::class.java) {
            Variant.parse("""{"runtime":{"model_arena":"false"}}""")
        }
    }
    @org.junit.Test fun encoderProviderIsCpuByDefaultAndWebgpuParakeetOnly() {
        assertEquals("cpu", Variant.DEFAULT.runtime.encoderProvider)
        assertEquals("webgpu", Variant.parse("""{"runtime":{"encoder_provider":"webgpu"}}""").runtime.encoderProvider)
        for (bad in listOf("""{"runtime":{"encoder_provider":"gpu"}}""", """{"runtime":{"encoder_provider":true}}""",
                """{"family":"whisper","cutting":{"max_chunk_s":28},"runtime":{"encoder_provider":"webgpu"}}""")) {
            assertThrows(IllegalArgumentException::class.java) { Variant.parse(bad) }
        }
    }
}
