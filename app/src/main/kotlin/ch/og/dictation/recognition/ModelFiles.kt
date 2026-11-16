package ch.og.dictation.recognition

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Where a model's files are: the APK's assets (daily app) or a directory on disk (laboratory,
 * pushed by tools/phone). sherpa-onnx takes a null AssetManager to mean file paths.
 */
class ModelFiles(val assets: AssetManager?, private val dir: String, private val names: List<String>) {
    companion object {
        fun asset(context: Context, dir: String) = ModelFiles(context.assets, dir, context.assets.list(dir).orEmpty().toList())
        fun dir(dir: File) = ModelFiles(null, dir.absolutePath, dir.list().orEmpty().toList())
    }

    /** The single .onnx file whose name contains [stem]; the int8 one when both precisions exist. */
    fun onnx(stem: String): String {
        val hits = names.filter { it.contains(stem) && it.endsWith(".onnx") }
        val pick = hits.singleOrNull() ?: hits.singleOrNull { it.contains(".int8.") }
        return "$dir/" + requireNotNull(pick) { "no unique *$stem*.onnx in $dir: $names" }
    }

    fun tokens(): String = "$dir/" + requireNotNull(names.singleOrNull { it.endsWith("tokens.txt") }) { "no tokens.txt in $dir" }
    fun suffixed(suffix: String): String = "$dir/" + requireNotNull(names.singleOrNull { it.endsWith(suffix) }) { "no unique *$suffix in $dir" }
    fun file(name: String): String = "$dir/$name"
    /** The directory itself (Qwen3's tokenizer files live next to the ONNX files). */
    fun root(): String = dir
}
