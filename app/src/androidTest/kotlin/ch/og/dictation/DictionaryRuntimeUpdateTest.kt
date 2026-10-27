package ch.og.dictation

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
        assertEquals("run this check first in a fresh instrumentation process", 0, Engine.dictionaryModelLoads)
        val pointer = File(context.filesDir, "dictionary/current.json")
        val originalPointer = if (pointer.exists()) pointer.readBytes() else null
        val store = Engine.dictionaryStore(context)
        val original = store.read()
        try {
            val saved = Engine.saveDictionary(context, original, original.entries + "Quarzblütenpost").get(10, TimeUnit.SECONDS)
            assertEquals(saved, store.read())
            assertEquals("editing alone must not initialise ASR", 0, Engine.dictionaryModelLoads)
        } finally {
            restorePointer(pointer, originalPointer)
        }
    }

    @Test fun savedRevisionRefreshesOnceAndAnExistingTakeKeepsItsRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pointer = File(context.filesDir, "dictionary/current.json")
        val originalPointer = if (pointer.exists()) pointer.readBytes() else null
        val store = Engine.dictionaryStore(context)
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
        val old = Engine.runtime(context)
        Engine.warmUp(context).get(2, TimeUnit.MINUTES)
        val take = Engine.openTake(context) { runtime, _ -> runtime.open(capture, output, direct).also { it.start() } }
        try {
            val initialLoads = Engine.dictionaryModelLoads
            val saved = Engine.saveDictionary(context, original, original.entries + "Quarzblütenexpress").get(2, TimeUnit.MINUTES)
            assertEquals("first edit must reuse the loaded model", initialLoads, Engine.dictionaryModelLoads)
            assertTrue(take.active)
            assertEquals(saved, Engine.dictionaryStore(context).read())
            val updated = Engine.runtime(context)
            assertNotSame(old, updated)
            assertSame(updated, Engine.runtime(context))
            val receipt = requireNotNull(Engine.liveReceipt())
            assertEquals("candidate-a-user-dictionary", receipt.getString("baseline"))
            assertTrue(receipt.getBoolean("verified"))
            assertEquals(saved.sha256, receipt.getString("dictionary_sha256"))
            take.stop(); take.stop()
            assertEquals(TakeOutcome.COMPLETED, ended.get(1, TimeUnit.MINUTES).outcome)
            val loads = Engine.dictionaryModelLoads
            val edited = Engine.saveDictionary(context, saved, original.entries + "Quarzblütenpost").get(2, TimeUnit.MINUTES)
            assertEquals("editing must reuse the loaded model", loads, Engine.dictionaryModelLoads)
            assertNotEquals(saved.sha256, edited.sha256)
            val removed = Engine.saveDictionary(context, edited, original.entries).get(2, TimeUnit.MINUTES)
            assertEquals("removal must reuse the loaded model", loads, Engine.dictionaryModelLoads)
            // Removing the synthetic term must recover the exact retained DE/EN outputs.
            NormalBaselineTest().checkRetainedOutputs("candidate-a-user-dictionary", "edited-dictionary-parity-result.json")
            val empty = Engine.saveDictionary(context, removed, emptyList()).get(2, TimeUnit.MINUTES)
            assertTrue(empty.entries.isEmpty())
            assertEquals("empty must keep the same loaded model", loads, Engine.dictionaryModelLoads)
            assertEquals("candidate-a-user-dictionary", requireNotNull(Engine.liveReceipt()).getString("baseline"))
            assertSame(Engine.runtime(context), Engine.runtime(context))
            val restored = Engine.saveDictionary(context, empty, original.entries).get(2, TimeUnit.MINUTES)
            assertEquals(original.entries, restored.entries)
        } finally {
            if (take.active) take.cancel()
            // Native restoration first; finally restore exact persistence identity, including absent pointer.
            val current = store.read()
            if (current.entries != original.entries) Engine.saveDictionary(context, current, original.entries).get(2, TimeUnit.MINUTES)
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
