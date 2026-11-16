package ch.og.dictation.recognition

import ch.og.dictation.R
import ch.og.dictation.dictionary.DictionarySnapshot
import ch.og.dictation.dictionary.DictionaryStore
import ch.og.dictation.take.TakeSession
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "Recognizer"
private const val ASSET_MODEL = "parakeet-v3"

/** Process-wide: the model is loaded once and reused for every take while the process lives. */
object Engine {
    /** Serial: model load first, then segments in order. */
    private val decodeQueue: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "decode") }
    private var runtime: DictationRuntime? = null
    /** Null while the historical INT8 control runs (ADR-003): nothing provisioned and nothing edited. */
    private var baseline: ProvisionalBaseline? = null
    private var receipts = false
    @Volatile var modelReadyNs: Long? = null
        private set

    private val dictionaryQueue = Executors.newSingleThreadExecutor { Thread(it, "dictionary") }
    private val dictionaryModels = DictionaryModelLeases()
    internal val dictionaryModelLoads: Int get() = dictionaryModels.loads

    private fun lab(context: Context) = File(context.filesDir, "lab")
    private fun acceptance(context: Context) =
        org.json.JSONObject(context.resources.openRawResource(R.raw.candidate_a).bufferedReader().use { it.readText() })

    fun dictionaryStore(context: Context) = DictionaryStore(File(context.filesDir, "dictionary"),
        File(lab(context), acceptance(context).getJSONObject("variant").getJSONObject("hotwords").getString("file")))

    @Synchronized fun runtime(context: Context): DictationRuntime {
        runtime?.let { return it }
        val app = context.applicationContext
        receipts = app.resources.getBoolean(R.bool.benchmark_receipts)
        val (created, normal) = build(app, dictionaryStore(app).read())
        baseline = normal
        runtime = created
        return created
    }

    /** Candidate A with per-stream dictionary leases, or the historical control from APK assets. */
    private fun build(app: Context, dictionary: DictionarySnapshot): Pair<DictationRuntime, ProvisionalBaseline?> {
        val normal = ProvisionalBaseline.load(lab(app), acceptance(app), dictionary)
            ?: return ModelFiles.asset(app, ASSET_MODEL).let { files ->
                Pipeline.runtime(Variant.HISTORICAL_INT8, files, null, files, decodeQueue, written = WrittenForm::of) } to null
        return DictationRuntime(normal.variant, decodeQueue, {
            val lease = dictionaryModels.acquire(normal, dictionary, File(app.applicationInfo.sourceDir)) {
                SherpaRecognizer.parakeet(normal.variant, ModelFiles.dir(normal.modelDirectory), perStreamDictionary = true)
            }
            if (normal.variant.post.swissSs) SwissSpelling(lease) else lease
        }, { VadSegmenter(normal.variant, ModelFiles.dir(lab(app))) }, WrittenForm::of, Pipeline::release) to normal
    }

    /** Stage/load/verify, then atomically save and publish. A failed refresh preserves the old runtime. */
    fun saveDictionary(context: Context, expected: DictionarySnapshot, entries: List<String>): java.util.concurrent.CompletableFuture<DictionarySnapshot> {
        val app = context.applicationContext
        return java.util.concurrent.CompletableFuture.supplyAsync({
            val store = dictionaryStore(app)
            val staged = store.prepare(entries)
            if (staged.sha256 == expected.sha256) return@supplyAsync expected
            synchronized(this) {
                // Editing Settings alone must not load ASR. Its normal first-use checks still
                // run when dictation starts; there is no cached runtime to refresh here.
                if (runtime == null) {
                    requireNotNull(ProvisionalBaseline.load(lab(app), acceptance(app), staged)).requireAvailable()
                    store.commit(staged, expected)
                    return@supplyAsync staged
                }
            }
            val (prepared, normal) = build(app, staged)
            try {
                prepared.warmUp().join()
                synchronized(this) {
                    store.commit(staged, expected)
                    val previous = runtime
                    runtime = prepared
                    baseline = normal
                    modelReadyNs = System.nanoTime()
                    previous?.retire()
                }
            } catch (failure: Throwable) {
                prepared.retire()
                throw failure
            }
            staged
        }, dictionaryQueue)
    }

    /** Benchmark builds only: the identity of the running Candidate A; null for the historical control. */
    @Synchronized fun liveReceipt(): org.json.JSONObject? = if (receipts) baseline?.receipt() else null

    /** Opening and pinning identity must be atomic with publication/retirement of a revision. */
    @Synchronized fun openTake(context: Context, open: (DictationRuntime, () -> org.json.JSONObject?) -> TakeSession): TakeSession {
        val current = runtime(context)
        val selected = baseline?.takeIf { receipts }
        return open(current) { selected?.receipt() }
    }

    /** Starts loading the model in the background; safe to call repeatedly. */
    fun warmUp(context: Context): java.util.concurrent.CompletableFuture<Unit> {
        val ready = try { runtime(context).warmUp() }
            catch (failure: Exception) { java.util.concurrent.CompletableFuture.failedFuture<Unit>(failure) }
        return ready.whenComplete { _, failure ->
            if (failure != null) Log.e(TAG, "model load failed", failure)
            else if (modelReadyNs == null) modelReadyNs = System.nanoTime()
        }
    }
}
