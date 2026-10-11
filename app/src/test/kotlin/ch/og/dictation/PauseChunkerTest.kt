package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseChunkerTest {
    private fun samples(n: Int, value: Float) = FloatArray(n) { value }

    @Test fun cutsAtAPauseOnceTheMinimumLengthIsReached() {
        val c = PauseChunker(minSamples = 100, padSamples = 10)
        assertNull(c.append(samples(50, 1f)))
        assertNull("too short at the first pause", c.onSpeechEnd(40))
        assertNull(c.append(samples(80, 2f)))
        val chunk = c.onSpeechEnd(speechEnd = 110)   // cut at 120 = speech end + pad
        assertEquals(120, chunk!!.samples.size)
        assertEquals(true, chunk.speech)
        val rest = c.rest()!!
        assertEquals(10, rest.samples.size)
        assertEquals("no speech end and none in progress", false, rest.speech)
    }

    @Test fun padCannotReachBeyondTheAudioFedSoFar() {
        val c = PauseChunker(minSamples = 100, padSamples = 50)
        c.append(samples(120, 1f))
        assertEquals(120, c.onSpeechEnd(speechEnd = 118)!!.samples.size)
        assertNull(c.rest())
    }

    @Test fun hardMaximumCutsWithoutAPause() {
        val c = PauseChunker(minSamples = 100, padSamples = 10, maxSamples = 150)
        assertNull(c.append(samples(140, 1f)))
        val forced = c.append(samples(30, 2f), speechNow = true)
        assertEquals(150, forced!!.samples.size)
        assertEquals(1f, forced.samples[139]); assertEquals(2f, forced.samples[149])
        assertEquals("speech in progress at the cut", true, forced.speech)
        assertEquals(20, c.rest()!!.samples.size)
    }

    @Test fun joinCapitalisesAfterASentenceEndOnlyWhenAsked() {
        assertEquals("Ein Satz. zweiter satz", joinChunks(listOf("Ein Satz.", " zweiter satz", ""), seamCapitalize = false))
        assertEquals("Ein Satz. Zweiter satz", joinChunks(listOf("Ein Satz.", "zweiter satz"), seamCapitalize = true))
        assertEquals("ohne punkt zweiter", joinChunks(listOf("ohne punkt", "zweiter"), seamCapitalize = true))
    }
}
