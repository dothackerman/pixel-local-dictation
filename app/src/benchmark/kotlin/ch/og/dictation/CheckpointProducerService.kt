package ch.og.dictation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File
import java.util.concurrent.Executor

/** Synthetic producer in its own process; never binds to or stops the IME. */
class CheckpointProducerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread {
            val root = File(noBackupFilesDir, "checkpoint-process-test")
            val direct = Executor { it.run() }
            var feed: ((FloatArray) -> Unit)? = null
            val capture = object : TakeCapture {
                override fun start(accept: (FloatArray) -> Unit) { feed = accept }
                override fun stop(done: (Result<ByteArray>) -> Unit) = done(Result.success(byteArrayOf()))
                override fun cancel(done: () -> Unit) = done()
            }
            val output = object : TakeOutput {
                override fun preview(text: String) {}
                override fun commit(text: String) = error("Unexpected insertion")
                override fun returnToKeyboard() = false
                override fun finished(result: TakeResult, pcm: ByteArray) {}
            }
            val runtime = DictationRuntime(Variant.HISTORICAL_INT8, direct, { Recognizer { "synthetic checkpoint" } },
                { object : Segmenter {
                    override fun accept(samples: FloatArray) = listOf(Chunk(samples, true))
                    override fun flush() = emptyList<Chunk>()
                    override fun reset() {}
                } })
            val take = runtime.open(capture, HistoryTakeOutput(output, { HistoryStore(root) }, direct, { error("save failed") }), direct)
            take.start()
            feed!!(floatArrayOf(1f))
            val ready = File(root, "producer-ready.tmp")
            ready.writeText(Process.myPid().toString())
            check(ready.renameTo(File(root, "producer-ready")))
            Process.killProcess(Process.myPid())
        }.start()
        return START_NOT_STICKY
    }
}
