package ch.og.dictation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android replay adapter. Editor and keyboard receipts are simulated, never live UI evidence. */
fun replaySession(
    runtime: DictationRuntime, audio: FloatArray, paced: Boolean,
    timeline: TimelineObserver? = null, preview: (String) -> Unit = {},
): TakeResult {
    val delivery = Executors.newSingleThreadExecutor()
    lateinit var accept: (FloatArray) -> Unit
    val capture = object : TakeCapture {
        override fun start(callback: (FloatArray) -> Unit) { accept = callback }
        override fun stop(done: (Result<ByteArray>) -> Unit) { done(Result.success(ByteArray(0))) }
        override fun cancel(done: () -> Unit) { done() }
    }
    val finished = CountDownLatch(1)
    var result: TakeResult? = null
    val output = object : TakeOutput {
        override fun preview(text: String) = preview.invoke(text)
        override fun commit(text: String) = true
        override fun returnToKeyboard() = true
        override fun finished(value: TakeResult, pcm: ByteArray) { result = value; finished.countDown() }
    }
    val session = runtime.open(capture, output, delivery, timeline)
    try {
        delivery.submit { session.start() }.get(10, TimeUnit.MINUTES)
        val start = System.nanoTime()
        for (offset in audio.indices step 640) {
            val end = minOf(offset + 640, audio.size)
            val due = start + end * 1_000_000_000L / SAMPLE_RATE
            if (paced) {
                val remaining = due - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
            }
            timeline?.emit(TimelineEvent("feedStart", samples = end - offset, scheduledNs = if (paced) due else null))
            accept(audio.copyOfRange(offset, end))
        }
        delivery.submit { session.stop() }.get(10, TimeUnit.MINUTES)
        check(finished.await(10, TimeUnit.MINUTES)) { "no final session output" }
        delivery.submit {}.get(10, TimeUnit.SECONDS) // Include cleanup after the result callback.
        return requireNotNull(result)
    } finally {
        try { delivery.submit { session.cancel() }.get(10, TimeUnit.SECONDS) }
        finally { delivery.shutdown(); delivery.awaitTermination(10, TimeUnit.SECONDS) }
    }
}
