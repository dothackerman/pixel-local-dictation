package ch.og.dictation.recognition

import ch.og.dictation.R
import ch.og.dictation.dictionary.DictionarySnapshot
import ch.og.dictation.dictionary.DictionaryStore
import ch.og.dictation.storage.Storage
import ch.og.dictation.take.TakeSession
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "Recognizer"
private const val ASSET_MODEL = "parakeet-v3"

/**
 * One per process, created lazily by Wiring: the model is loaded once and reused for every take
 * while the process lives. Opening a take and the live receipt need no Context.
 */
class Engine(
    private val dictionaryStoreOf: () -> DictionaryStore,
    private val receiptsEnabled: () -> Boolean,
    private val runtimes: Runtimes,
) {
    /** How a runtime is made for one dictionary revision. */
    interface Runtimes {
        /** Candidate A with per-stream dictionary leases, or the historical control. */
        fun build(dictionary: DictionarySnapshot): Pair<DictationRuntime, ProvisionalBaseline?>
        /** Editing without a running model: the staged revision's provisioning is complete (no model load). */
        fun requireAvailable(staged: DictionarySnapshot)
        /** Model loads behind dictionary leases so far. */
        val modelLoads: Int
    }

    private var runtime: DictationRuntime? = null
    /** Null while the historical INT8 control runs (ADR-003): nothing provisioned and nothing edited. */
    private var baseline: ProvisionalBaseline? = null
    private var receipts = false
    @Volatile var modelReadyNs: Long? = null
        private set

    private val dictionaryQueue: ExecutorService by lazy { Executors.newSingleThreadExecutor { Thread(it, "dictionary") } }
    internal val dictionaryModelLoads: Int get() = runtimes.modelLoads

    fun dictionaryStore(): DictionaryStore = dictionaryStoreOf()

    @Synchronized fun runtime(): DictationRuntime {
        runtime?.let { return it }
        receipts = receiptsEnabled()
        val (created, normal) = runtimes.build(dictionaryStore().read())
        baseline = normal
        runtime = created
        return created
    }

    /** Stage/load/verify, then atomically save and publish. A failed refresh preserves the old runtime. */
    fun saveDictionary(expected: DictionarySnapshot, entries: List<String>): CompletableFuture<DictionarySnapshot> =
        CompletableFuture.supplyAsync({
            val store = dictionaryStore()
            val staged = store.prepare(entries)
            if (staged.sha256 == expected.sha256) return@supplyAsync expected
            synchronized(this) {
                // Editing Settings alone must not load ASR. Its normal first-use checks still
                // run when dictation starts; there is no cached runtime to refresh here.
                if (runtime == null) {
                    runtimes.requireAvailable(staged)
                    store.commit(staged, expected)
                    return@supplyAsync staged
                }
            }
            val (prepared, normal) = runtimes.build(staged)
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

    /** Benchmark builds only: the identity of the running Candidate A; null for the historical control. */
    @Synchronized fun liveReceipt(): org.json.JSONObject? = if (receipts) baseline?.receipt() else null

    /** Opening and pinning identity must be atomic with publication/retirement of a revision. */
    @Synchronized fun openTake(open: (DictationRuntime, () -> org.json.JSONObject?) -> TakeSession): TakeSession {
        val current = runtime()
        val selected = baseline?.takeIf { receipts }
        return open(current) { selected?.receipt() }
    }

    /** Starts loading the model in the background; safe to call repeatedly. */
    fun warmUp(): CompletableFuture<Unit> {
        val ready = try { runtime().warmUp() }
            catch (failure: Exception) { CompletableFuture.failedFuture<Unit>(failure) }
        return ready.whenComplete { _, failure ->
            if (failure != null) Log.e(TAG, "model load failed", failure)
            else if (modelReadyNs == null) modelReadyNs = System.nanoTime()
        }
    }

    companion object {
        /**
         * The app's engine: Candidate A from `files/lab`, or the INT8 control from the APK's assets.
         * The Dictionary is the one [storage] names, for as long as this engine lives.
         */
        fun forApp(context: Context, storage: Storage): Engine {
            val app = context.applicationContext
            val runtimes = AppRuntimes(app)
            return Engine({ DictionaryStore(storage.dictionaryRoot, runtimes.provisionedDictionary()) }, { app.resources.getBoolean(R.bool.benchmark_receipts) }, runtimes)
        }

        /** Host tests: an engine around a prepared runtime; no dictionary, no receipts. */
        fun prepared(runtime: DictationRuntime): Engine = Engine({ error("prepared engine has no dictionary") }, { false },
            object : Runtimes {
                override fun build(dictionary: DictionarySnapshot) = runtime to null
                override fun requireAvailable(staged: DictionarySnapshot) = error("prepared engine has no dictionary")
                override val modelLoads = 0
            }).also { it.runtime = runtime }
    }
}

/** The app's runtimes: decode queue, dictionary leases and provisioning under `files/lab`. */
private class AppRuntimes(private val app: Context) : Engine.Runtimes {
    /** Serial: model load first, then segments in order. */
    private val decodeQueue: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "decode") }
    private val dictionaryModels = DictionaryModelLeases()
    override val modelLoads: Int get() = dictionaryModels.loads

    private fun lab() = File(app.filesDir, "lab")
    private fun acceptance() =
        org.json.JSONObject(app.resources.openRawResource(R.raw.candidate_a).bufferedReader().use { it.readText() })

    /** The frozen Candidate A list: what the Dictionary holds until a revision is saved. */
    fun provisionedDictionary() =
        File(lab(), acceptance().getJSONObject("variant").getJSONObject("hotwords").getString("file"))

    override fun requireAvailable(staged: DictionarySnapshot) =
        requireNotNull(ProvisionalBaseline.load(lab(), acceptance(), staged)).requireAvailable()

    override fun build(dictionary: DictionarySnapshot): Pair<DictationRuntime, ProvisionalBaseline?> {
        val normal = ProvisionalBaseline.load(lab(), acceptance(), dictionary)
            ?: return ModelFiles.asset(app, ASSET_MODEL).let { files ->
                Pipeline.runtime(Variant.HISTORICAL_INT8, files, null, files, decodeQueue, written = WrittenForm::of) } to null
        return DictationRuntime(normal.variant, decodeQueue, {
            val lease = dictionaryModels.acquire(normal, dictionary, File(app.applicationInfo.sourceDir)) {
                SherpaRecognizer.parakeet(normal.variant, ModelFiles.dir(normal.modelDirectory), perStreamDictionary = true)
            }
            if (normal.variant.post.swissSs) SwissSpelling(lease) else lease
        }, { VadSegmenter(normal.variant, ModelFiles.dir(lab())) }, WrittenForm::of, Pipeline::release) to normal
    }
}
