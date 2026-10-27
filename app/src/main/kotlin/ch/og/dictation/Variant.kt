package ch.og.dictation

import org.json.JSONObject
import kotlin.math.ceil

/**
 * Kotlin twin of tools/variant.py (schema 4): one decode pipeline, fully described. The daily
 * app selects its packaged provisional baseline; DEFAULT remains the historical parsing control.
 * The laboratory test (LabTest) runs whatever variant.json was pushed.
 * Unknown keys are rejected so that laptop and phone can never silently run different pipelines.
 */
data class Variant(
    val name: String = "baseline",
    val description: String = "",
    val family: String = "parakeet",
    val model: String = "parakeet-v3-matmul8",
    val threads: Int = 4,
    val runtime: Runtime = Runtime(),
    val decoding: Decoding = Decoding(),
    val whisper: Whisper = Whisper(),
    val qwen3: Qwen3 = Qwen3(),
    val cohere: Cohere = Cohere(),
    val lid: Lid = Lid(),
    val hotwords: Hotwords = Hotwords(),
    val cutting: Cutting = Cutting(),
    val post: Post = Post(),
) {
    /**
     * encoderProvider: "webgpu" puts only the Parakeet encoder on WebGPU (patched ORT 1.31 build).
     * placementLog: diagnostic only; session-level VERBOSE ORT logging for the ADR-023 placement
     * listing of that session (pipeline-placement-v1). Refused by timed measurement runs.
     */
    data class Runtime(val modelArena: Boolean = true, val modelPattern: Boolean = true,
        val lidArena: Boolean = true, val lidPattern: Boolean = true, val profile: Boolean = false,
        val encoderProvider: String = "cpu", val placementLog: Boolean = false)
    data class Decoding(val method: String = "greedy_search", val maxActivePaths: Int = 4, val blankPenalty: Float = 0f)
    data class Whisper(val language: String = "", val tailPaddings: Int = -1)
    data class Qwen3(val maxTotalLen: Int = 1024, val maxNewTokens: Int = 256) {
        /** Conservative bound for the pinned byte-BPE export; includes actual dictionary bytes. */
        fun checkBudget(seconds: Float, hotwords: String = "") {
            val required = ceil(seconds.toDouble() * 13) + 64 + hotwords.toByteArray(Charsets.UTF_8).size + maxNewTokens
            require(required <= maxTotalLen) { "qwen3: audio, hotword prompt and generation reserve exceed max_total_len" }
        }
    }
    data class Cohere(val language: String = "", val usePunct: Boolean = true, val useItn: Boolean = true)
    data class Lid(val model: String = "", val probeSeconds: Float = 0f, val fallback: String = "en")
    /** Dictionary (ADR-005): file name under files/lab on the phone, corpus/lab on the laptop. */
    data class Hotwords(val file: String = "", val score: Float = 1.5f)
    data class Cutting(
        val minChunkS: Float = 20f, val padS: Float = 0.4f, val minSilenceS: Float = 0.8f,
        val minSpeechS: Float = 0.25f, val vadMaxSpeechS: Float = 20f, val maxChunkS: Float = 0f,
    )
    data class Post(val swissSs: Boolean = true, val seamCapitalize: Boolean = false, val skipSilentChunks: Boolean = false)

    val isDictionary: Boolean get() = hotwords.file.isNotEmpty()

    companion object {
        const val SCHEMA = 4
        val HISTORICAL_INT8 = Variant()
        // Parsing defaults remain frozen: historical plans must retain their meaning.
        val DEFAULT = HISTORICAL_INT8
        val FAMILIES = setOf("parakeet", "whisper", "canary", "qwen3", "cohere")
        private val KEYS = mapOf(
            "" to setOf("schema", "name", "description", "family", "model", "threads", "runtime", "decoding", "whisper", "qwen3", "cohere", "lid", "hotwords", "cutting", "post"),
            "runtime" to setOf("model_arena", "model_pattern", "lid_arena", "lid_pattern", "profile", "encoder_provider", "placement_log"),
            "decoding" to setOf("method", "max_active_paths", "blank_penalty"),
            "whisper" to setOf("language", "tail_paddings"),
            "qwen3" to setOf("max_total_len", "max_new_tokens"),
            "cohere" to setOf("language", "use_punct", "use_itn"),
            "lid" to setOf("model", "probe_seconds", "fallback"),
            "hotwords" to setOf("file", "score"),
            "cutting" to setOf("min_chunk_s", "pad_s", "min_silence_s", "min_speech_s", "vad_max_speech_s", "max_chunk_s"),
            "post" to setOf("swiss_ss", "seam_capitalize", "skip_silent_chunks"),
        )

        fun parse(json: String): Variant = parse(JSONObject(json))

        fun parse(o: JSONObject): Variant {
            checkKeys(o, "")
            for (section in KEYS.keys.filter { it.isNotEmpty() }) {
                if (o.has(section)) require(o.opt(section) is JSONObject) { "$section must be an object" }
                o.optJSONObject(section)?.let { checkKeys(it, section) }
            }
            val schema = o.optInt("schema", SCHEMA)
            require(schema in 1..SCHEMA) { "variant schema $schema not supported, this app knows $SCHEMA" }
            val rt = o.optJSONObject("runtime") ?: JSONObject()
            fun runtimeBool(key: String): Boolean {
                if (!rt.has(key)) return key != "profile" && key != "placement_log"
                require(rt.get(key) is Boolean) { "runtime.$key must be boolean" }
                return rt.getBoolean(key)
            }
            val d = o.optJSONObject("decoding") ?: JSONObject()
            val w = o.optJSONObject("whisper") ?: JSONObject()
            val q = o.optJSONObject("qwen3") ?: JSONObject()
            val co = o.optJSONObject("cohere") ?: JSONObject()
            val l = o.optJSONObject("lid") ?: JSONObject()
            val h = o.optJSONObject("hotwords") ?: JSONObject()
            val c = o.optJSONObject("cutting") ?: JSONObject()
            val p = o.optJSONObject("post") ?: JSONObject()
            val def = DEFAULT
            fun JSONObject.f(key: String, default: Float) = optDouble(key, default.toDouble()).toFloat()
            fun JSONObject.strictInt(key: String, default: Int): Int {
                if (!has(key)) return default
                require(get(key) is Int) { "qwen3.$key must be an integer" }
                return getInt(key)
            }
            val family = o.optString("family", def.family)
            val maxChunkS = c.f("max_chunk_s", def.cutting.maxChunkS).let {
                if (schema < 3 && family == "qwen3" && it == 0f) 40f else it
            }
            val v = Variant(
                name = o.optString("name", def.name),
                description = o.optString("description", def.description),
                family = family,
                model = o.optString("model", def.model),
                runtime = Runtime(runtimeBool("model_arena"), runtimeBool("model_pattern"),
                    runtimeBool("lid_arena"), runtimeBool("lid_pattern"), runtimeBool("profile"),
                    if (rt.has("encoder_provider")) {
                        require(rt.get("encoder_provider") is String) { "runtime.encoder_provider must be a string" }
                        rt.getString("encoder_provider")
                    } else "cpu", runtimeBool("placement_log")),
                threads = o.optInt("threads", def.threads),
                decoding = Decoding(d.optString("method", def.decoding.method), d.optInt("max_active_paths", def.decoding.maxActivePaths),
                    d.f("blank_penalty", def.decoding.blankPenalty)),
                whisper = Whisper(w.optString("language", def.whisper.language), w.optInt("tail_paddings", def.whisper.tailPaddings)),
                qwen3 = Qwen3(q.strictInt("max_total_len", def.qwen3.maxTotalLen), q.strictInt("max_new_tokens", def.qwen3.maxNewTokens)),
                cohere = Cohere(co.optString("language", def.cohere.language), co.optBoolean("use_punct", def.cohere.usePunct),
                    co.optBoolean("use_itn", def.cohere.useItn)),
                lid = Lid(l.optString("model", def.lid.model), l.f("probe_seconds", def.lid.probeSeconds), l.optString("fallback", def.lid.fallback)),
                hotwords = Hotwords(h.optString("file", def.hotwords.file), h.f("score", def.hotwords.score)),
                cutting = Cutting(
                    c.f("min_chunk_s", def.cutting.minChunkS), c.f("pad_s", def.cutting.padS), c.f("min_silence_s", def.cutting.minSilenceS),
                    c.f("min_speech_s", def.cutting.minSpeechS), c.f("vad_max_speech_s", def.cutting.vadMaxSpeechS), maxChunkS),
                post = Post(p.optBoolean("swiss_ss", def.post.swissSs), p.optBoolean("seam_capitalize", def.post.seamCapitalize),
                    p.optBoolean("skip_silent_chunks", def.post.skipSilentChunks)),
            )
            v.validate()
            return v
        }

        private fun checkKeys(o: JSONObject, section: String) {
            val unknown = o.keys().asSequence().filter { it !in KEYS.getValue(section) }.toList()
            require(unknown.isEmpty()) { "unknown variant keys (the schema is closed): ${unknown.joinToString()}" }
        }
    }

    private fun validate() {
        require(family in FAMILIES) { "family must be one of $FAMILIES" }
        require(threads in 1..8) { "threads must be 1..8" }
        require(runtime.encoderProvider in setOf("cpu", "webgpu")) { "runtime.encoder_provider must be cpu or webgpu" }
        require(runtime.encoderProvider == "cpu" || family == "parakeet") { "runtime.encoder_provider webgpu is parakeet only" }
        require(!runtime.placementLog || runtime.encoderProvider == "webgpu") { "runtime.placement_log needs runtime.encoder_provider webgpu" }
        require(decoding.method in setOf("greedy_search", "modified_beam_search")) { "decoding.method" }
        require(decoding.maxActivePaths in 1..32) { "decoding.max_active_paths must be 1..32" }
        with(cutting) {
            require(minChunkS >= 0 && padS >= 0 && minSilenceS >= 0 && minSpeechS >= 0 && vadMaxSpeechS >= 0 && maxChunkS >= 0) { "cutting values must not be negative" }
            require(maxChunkS == 0f || maxChunkS >= minChunkS) { "cutting.max_chunk_s must be 0 or at least min_chunk_s" }
        }
        if (family == "whisper") {
            require(cutting.maxChunkS > 0f && cutting.maxChunkS <= 28f) { "whisper: cutting.max_chunk_s must be set and at most 28" }
            require(whisper.language in setOf("", "de", "en")) { "whisper.language must be '', 'de' or 'en'" }
        }
        if (family == "qwen3") {
            require(qwen3.maxTotalLen in 512..8192 && qwen3.maxNewTokens in 256..2048) { "qwen3 budgets out of range" }
            require(cutting.maxChunkS > 0f && cutting.maxChunkS <= 40f) { "qwen3: cutting.max_chunk_s must be set and at most 40" }
            qwen3.checkBudget(cutting.maxChunkS)
        }
        if (family == "canary") {
            require(lid.model.isNotEmpty()) { "canary: lid.model is required" }
            require(lid.fallback in setOf("de", "en")) { "lid.fallback must be de or en" }
        }
        if (family == "cohere") {
            require(cohere.language in setOf("", "de", "en")) { "cohere.language must be '', 'de' or 'en'" }
            require(cohere.language.isNotEmpty() || lid.model.isNotEmpty()) { "cohere needs the language: cohere.language or lid.model" }
            require(lid.fallback in setOf("de", "en")) { "lid.fallback must be de or en" }
        }
        if (family != "canary" && family != "cohere") require(lid.model.isEmpty()) { "lid is only used with canary and cohere" }
        if (isDictionary) {
            require(!hotwords.file.contains('/') && !hotwords.file.contains('\\') && hotwords.file.endsWith(".txt")) { "hotwords.file is a plain .txt file name" }
            require(family == "parakeet" || family == "qwen3") { "hotwords are supported for parakeet and qwen3 only" }
            if (family == "parakeet") require(decoding.method == "modified_beam_search") { "parakeet hotwords need modified_beam_search" }
            require(hotwords.score > 0f && hotwords.score <= 10f) { "hotwords.score must be in (0, 10]" }
        }
    }
}
