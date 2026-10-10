package ch.og.dictation

import org.json.JSONObject

/**
 * Kotlin twin of tools/variant.py (schema 1): one decode pipeline, fully described. The daily
 * app runs [DEFAULT]; the laboratory test (LabTest) runs whatever variant.json was pushed.
 * Unknown keys are rejected so that laptop and phone can never silently run different pipelines.
 */
data class Variant(
    val name: String = "baseline",
    val description: String = "",
    val family: String = "parakeet",
    val model: String = "parakeet-v3-matmul8",
    val threads: Int = 4,
    val decoding: Decoding = Decoding(),
    val whisper: Whisper = Whisper(),
    val lid: Lid = Lid(),
    val cutting: Cutting = Cutting(),
    val post: Post = Post(),
) {
    data class Decoding(val method: String = "greedy_search", val maxActivePaths: Int = 4, val blankPenalty: Float = 0f)
    data class Whisper(val language: String = "", val tailPaddings: Int = -1)
    data class Lid(val model: String = "", val probeSeconds: Float = 0f, val fallback: String = "en")
    data class Cutting(
        val minChunkS: Float = 20f, val padS: Float = 0.4f, val minSilenceS: Float = 0.8f,
        val minSpeechS: Float = 0.25f, val vadMaxSpeechS: Float = 20f, val maxChunkS: Float = 0f,
    )
    data class Post(val swissSs: Boolean = true, val seamCapitalize: Boolean = false)

    companion object {
        const val SCHEMA = 1
        val DEFAULT = Variant()
        private val KEYS = mapOf(
            "" to setOf("schema", "name", "description", "family", "model", "threads", "decoding", "whisper", "lid", "cutting", "post"),
            "decoding" to setOf("method", "max_active_paths", "blank_penalty"),
            "whisper" to setOf("language", "tail_paddings"),
            "lid" to setOf("model", "probe_seconds", "fallback"),
            "cutting" to setOf("min_chunk_s", "pad_s", "min_silence_s", "min_speech_s", "vad_max_speech_s", "max_chunk_s"),
            "post" to setOf("swiss_ss", "seam_capitalize"),
        )

        fun parse(json: String): Variant = parse(JSONObject(json))

        fun parse(o: JSONObject): Variant {
            checkKeys(o, "")
            for (section in listOf("decoding", "whisper", "lid", "cutting", "post")) {
                o.optJSONObject(section)?.let { checkKeys(it, section) }
            }
            val schema = o.optInt("schema", SCHEMA)
            require(schema == SCHEMA) { "variant schema $schema not supported, this app knows $SCHEMA" }
            val d = o.optJSONObject("decoding") ?: JSONObject()
            val w = o.optJSONObject("whisper") ?: JSONObject()
            val l = o.optJSONObject("lid") ?: JSONObject()
            val c = o.optJSONObject("cutting") ?: JSONObject()
            val p = o.optJSONObject("post") ?: JSONObject()
            val def = DEFAULT
            val v = Variant(
                name = o.optString("name", def.name),
                description = o.optString("description", def.description),
                family = o.optString("family", def.family),
                model = o.optString("model", def.model),
                threads = o.optInt("threads", def.threads),
                decoding = Decoding(d.optString("method", def.decoding.method), d.optInt("max_active_paths", def.decoding.maxActivePaths),
                    d.optDouble("blank_penalty", def.decoding.blankPenalty.toDouble()).toFloat()),
                whisper = Whisper(w.optString("language", def.whisper.language), w.optInt("tail_paddings", def.whisper.tailPaddings)),
                lid = Lid(l.optString("model", def.lid.model), l.optDouble("probe_seconds", def.lid.probeSeconds.toDouble()).toFloat(),
                    l.optString("fallback", def.lid.fallback)),
                cutting = Cutting(
                    c.optDouble("min_chunk_s", def.cutting.minChunkS.toDouble()).toFloat(),
                    c.optDouble("pad_s", def.cutting.padS.toDouble()).toFloat(),
                    c.optDouble("min_silence_s", def.cutting.minSilenceS.toDouble()).toFloat(),
                    c.optDouble("min_speech_s", def.cutting.minSpeechS.toDouble()).toFloat(),
                    c.optDouble("vad_max_speech_s", def.cutting.vadMaxSpeechS.toDouble()).toFloat(),
                    c.optDouble("max_chunk_s", def.cutting.maxChunkS.toDouble()).toFloat()),
                post = Post(p.optBoolean("swiss_ss", def.post.swissSs), p.optBoolean("seam_capitalize", def.post.seamCapitalize)),
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
        require(family in setOf("parakeet", "whisper", "canary")) { "family must be parakeet, whisper or canary" }
        require(threads in 1..8) { "threads must be 1..8" }
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
        if (family == "canary") {
            require(lid.model.isNotEmpty()) { "canary: lid.model is required" }
            require(lid.fallback in setOf("de", "en")) { "lid.fallback must be de or en" }
        } else {
            require(lid.model.isEmpty()) { "lid is only used with canary in schema 1" }
        }
    }
}
