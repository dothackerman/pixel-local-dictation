package ch.og.dictation

import ch.og.dictation.wiring
import ch.og.dictation.recognition.Engine
import ch.og.dictation.take.TakeCapture
import ch.og.dictation.take.TakeOutcome
import ch.og.dictation.take.TakeOutput
import ch.og.dictation.take.TakeResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic edit; restore the exact original pointer in finally. No speech or terms in output. */
@RunWith(AndroidJUnit4::class)
class DictionaryRuntimeUpdateTest {
    @Test fun coldSettingsChangesDoNotInitialiseTheModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("run this check first in a fresh instrumentation process", 0, context.wiring.engine.dictionaryModelLoads)
        val pointer = File(context.filesDir, "dictionary/current.json")
        val originalPointer = if (pointer.exists()) pointer.readBytes() else null
        val store = context.wiring.engine.dictionaryStore()
        val original = store.read()
        try {
            val saved = context.wiring.engine.saveDictionary(original, original.entries + "Quarzblütenpost").get(10, TimeUnit.SECONDS)
            assertEquals(saved, store.read())
            assertEquals("editing alone must not initialise ASR", 0, context.wiring.engine.dictionaryModelLoads)
        } finally {
            restorePointer(pointer, originalPointer)
        }
    }

    @Test fun savedRevisionRefreshesOnceAndAnExistingTakeKeepsItsRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pointer = File(context.filesDir, "dictionary/current.json")
        val originalPointer = if (pointer.exists()) pointer.readBytes() else null
        val store = context.wiring.engine.dictionaryStore()
        val original = store.read()
        val direct = Executor { it.run() }
        val ended = CompletableFuture<TakeResult>()
        val capture = object : TakeCapture {
            override fun start(accept: (FloatArray) -> Unit) {}
            override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
            override fun cancel(done: () -> Unit) = done()
        }
        val output = object : TakeOutput {
            override fun preview(text: String) {}
            override fun commit(text: String) = true
            override fun returnToKeyboard() = true
            override fun finished(result: TakeResult, pcm: ByteArray) { ended.complete(result) }
        }
        val old = context.wiring.engine.runtime()
        context.wiring.engine.warmUp().get(2, TimeUnit.MINUTES)
        val take = context.wiring.engine.openTake { runtime, _ -> runtime.open(capture, output, direct).also { it.start() } }
        try {
            val initialLoads = context.wiring.engine.dictionaryModelLoads
            val saved = context.wiring.engine.saveDictionary(original, original.entries + "Quarzblütenexpress").get(2, TimeUnit.MINUTES)
            assertEquals("first edit must reuse the loaded model", initialLoads, context.wiring.engine.dictionaryModelLoads)
            assertTrue(take.active)
            assertEquals(saved, context.wiring.engine.dictionaryStore().read())
            val updated = context.wiring.engine.runtime()
            assertNotSame(old, updated)
            assertSame(updated, context.wiring.engine.runtime())
            val receipt = requireNotNull(context.wiring.engine.liveReceipt())
            assertEquals("candidate-a-user-dictionary", receipt.getString("baseline"))
            assertTrue(receipt.getBoolean("verified"))
            assertEquals(saved.sha256, receipt.getString("dictionary_sha256"))
            take.stop(); take.stop()
            assertEquals(TakeOutcome.COMPLETED, ended.get(1, TimeUnit.MINUTES).outcome)
            val loads = context.wiring.engine.dictionaryModelLoads
            val edited = context.wiring.engine.saveDictionary(saved, original.entries + "Quarzblütenpost").get(2, TimeUnit.MINUTES)
            assertEquals("editing must reuse the loaded model", loads, context.wiring.engine.dictionaryModelLoads)
            assertNotEquals(saved.sha256, edited.sha256)
            val removed = context.wiring.engine.saveDictionary(edited, original.entries).get(2, TimeUnit.MINUTES)
            assertEquals("removal must reuse the loaded model", loads, context.wiring.engine.dictionaryModelLoads)
            // Removing the synthetic term must recover the exact retained DE/EN outputs.
            NormalBaselineTest().checkRetainedOutputs("candidate-a-user-dictionary", "edited-dictionary-parity-result.json")
            val empty = context.wiring.engine.saveDictionary(removed, emptyList()).get(2, TimeUnit.MINUTES)
            assertTrue(empty.entries.isEmpty())
            assertEquals("empty must keep the same loaded model", loads, context.wiring.engine.dictionaryModelLoads)
            assertEquals("candidate-a-user-dictionary", requireNotNull(context.wiring.engine.liveReceipt()).getString("baseline"))
            assertSame(context.wiring.engine.runtime(), context.wiring.engine.runtime())
            val restored = context.wiring.engine.saveDictionary(empty, original.entries).get(2, TimeUnit.MINUTES)
            assertEquals(original.entries, restored.entries)
        } finally {
            if (take.active) take.cancel()
            // Native restoration first; finally restore exact persistence identity, including absent pointer.
            val current = store.read()
            if (current.entries != original.entries) context.wiring.engine.saveDictionary(current, original.entries).get(2, TimeUnit.MINUTES)
            restorePointer(pointer, originalPointer)
        }
    }
    private fun restorePointer(pointer: File, bytes: ByteArray?) {
        if (bytes == null) check(!pointer.exists() || pointer.delete()) else {
            val temp = File(pointer.parentFile, "restore.tmp")
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

}
