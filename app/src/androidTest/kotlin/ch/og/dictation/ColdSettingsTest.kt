package ch.og.dictation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fresh process only: a synthetic edit of the lab build's test Dictionary, next to the real model, must
 * not load it. The edit is removed afterwards, so the frozen Candidate A list applies again. No speech
 * or terms in output. Host twin: DictionaryRuntimeUpdateTest.
 */
@RunWith(AndroidJUnit4::class)
class ColdSettingsTest {
    @Test fun coldSettingsChangesDoNotInitialiseTheModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("the lab build has no owner Dictionary", "test", context.wiring.storage.layout)
        assertEquals("run this check first in a fresh instrumentation process", 0, context.wiring.engine.dictionaryModelLoads)
        val store = context.wiring.engine.dictionaryStore()
        val original = store.read()
        try {
            val saved = context.wiring.engine.saveDictionary(original, original.entries + "Quarzblütenpost").get(10, TimeUnit.SECONDS)
            assertEquals(saved, store.read())
            assertEquals("editing alone must not initialise ASR", 0, context.wiring.engine.dictionaryModelLoads)
        } finally {
            check(context.wiring.storage.dictionaryRoot.deleteRecursively()) { "the test Dictionary could not be emptied" }
        }
    }
}
