package ch.og.dictation

import ch.og.dictation.history.startHistory
import ch.og.dictation.history.HistoryIo
import ch.og.dictation.recognition.Chunk
import ch.og.dictation.recognition.DictationRuntime
import ch.og.dictation.recognition.Engine
import ch.og.dictation.recognition.Recognizer
import ch.og.dictation.recognition.Segmenter
import ch.og.dictation.storage.Storage
import ch.og.dictation.recognition.Variant
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WiringTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun runtime() = DictationRuntime(Variant.HISTORICAL_INT8, Executor { it.run() }, { Recognizer { "" } }, {
        object : Segmenter {
            override fun accept(samples: FloatArray) = emptyList<Chunk>()
            override fun flush() = emptyList<Chunk>()
            override fun reset() {}
        }
    })

    @Test fun oneValuePerProcessWithOneStorage() {
        assertSame(app.wiring, app.wiring)
        assertSame(app.wiring.storage, app.wiring.storage)
        assertSame("the engine is created once", app.wiring.engine, app.wiring.engine)
    }

    @Test fun derivedValuesShareTheEngineUnlessReplaced() {
        val base = app.wiring
        val derived = base.with(storage = Storage.under(File(app.cacheDir, "elsewhere")))
        assertSame(base.engine, derived.engine)
        val prepared = Engine.prepared(runtime())
        assertSame(prepared, base.with(engine = prepared).engine)
        assertNotSame(prepared, base.engine)
    }

    @Test fun scopedReplacementAlwaysRestoresTheValueItStartedFrom() {
        val original = app.wiring
        val isolated = Storage.under(File(app.cacheDir, "history-wiring-test"))
        val scope = app.overrideWiring { it.with(storage = isolated) }
        assertEquals(isolated.historyRoot, app.wiring.storage.historyRoot)
        scope.set { it.with(recorderFactory = { error("no microphone in this test") }) }
        assertSame("later changes derive from the current value", isolated, app.wiring.storage)
        scope.close()
        assertSame(original, app.wiring)
    }

    @Test fun preparedEngineOpensTakesWithoutAContext() {
        val prepared = runtime()
        val engine = Engine.prepared(prepared)
        assertSame(prepared, engine.runtime())
        assertEquals(null, engine.liveReceipt())
        engine.warmUp().get(2, TimeUnit.SECONDS)
        assertTrue(engine.modelReadyNs != null)
        prepared.close().join()
    }

    @Test fun historyStartsOnlyInTheOwningProcess() {
        val root = File(app.noBackupFilesDir, "history-startup-function").apply { deleteRecursively(); mkdirs() }
        val abandoned = File(root, "abandoned.tmp").apply { writeText("synthetic") }
        startHistory(owns = false, root = root)
        HistoryIo.executor.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue("a non-owning process never touches History", abandoned.exists())
        startHistory(owns = true, root = root)
        HistoryIo.executor.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(abandoned.exists())
        root.deleteRecursively()
    }
}
