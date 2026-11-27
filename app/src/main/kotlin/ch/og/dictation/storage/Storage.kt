package ch.og.dictation.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Properties

/** One small named store of flags and name sets (shared preferences on the phone). */
interface Preferences {
    fun flag(key: String): Boolean
    fun setFlag(key: String, on: Boolean)
    fun names(key: String): Set<String>
    fun setNames(key: String, names: Set<String>)
}

/** Where Copy puts a take's text. */
fun interface Clipboard {
    fun copy(text: String)
}

/**
 * Storage: where the app keeps each kind of data, one accessor per kind. Both builds use the same
 * paths; the lab build is its own app (#98), so Android keeps its data apart from release's. What
 * differs by build type is in `BuildType.kt` of `src/release` and `src/benchmark`.
 */
class Storage private constructor(
    private val files: File,
    noBackup: File,
    private val preferences: (String) -> Preferences,
    system: Clipboard,
) {
    /** Where takes are kept. */
    val historyRoot: File = File(noBackup, "history")

    /** The Dictionary's saved revisions and the pointer to the current one. */
    val dictionaryRoot: File get() = File(files, "dictionary")

    /** The pending History warnings: kinds only. */
    val historyNotices: Preferences get() = preferences("history_notices")

    /** The switches of the Settings page. */
    val settings: Preferences get() = preferences("settings")

    /** Copy's target: the system clipboard in release; in the lab build a text kept in the app. */
    val clipboard: Clipboard = copyTarget(system)

    /**
     * Benchmark recordings. Release only counts and deletes what earlier builds left there; the
     * lab build also saves into it.
     */
    val recordingsRoot: File get() = File(files, "benchmark")

    /** False in release: no Benchmark switch, no recording saved, whatever the settings hold. */
    val savesRecordings: Boolean get() = SAVES_RECORDINGS

    /** The lab folder: the speech model and the lab build's test output. */
    val labFolder: File get() = File(files, "lab")

    companion object {
        fun forApp(context: Context) = context.applicationContext.let { app ->
            Storage(app.filesDir, app.noBackupFilesDir,
                { name -> AndroidPreferences(app.getSharedPreferences(name, Context.MODE_PRIVATE)) },
                // Plain text without the sensitive-content flag, by owner decision.
                { text -> (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Transcript", text)) })
        }

        /**
         * The same paths below [base] instead of the app's data folder: host tests. Preferences are
         * files in `shared_prefs`; the file `clipboard` stands in for the system clipboard.
         */
        fun under(base: File) = Storage(File(base, "files"), File(base, "no_backup"),
            { name -> FilePreferences(File(base, "shared_prefs/$name.xml")) },
            { text -> runCatching { base.mkdirs(); File(base, "clipboard").writeText(text) } })
    }
}

/** Flags are written behind the caller, name sets before it returns: as the stores always did. */
private class AndroidPreferences(private val prefs: SharedPreferences) : Preferences {
    override fun flag(key: String) = prefs.getBoolean(key, false)
    override fun setFlag(key: String, on: Boolean) = prefs.edit().putBoolean(key, on).apply()
    override fun names(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    override fun setNames(key: String, names: Set<String>) { prefs.edit().putStringSet(key, names).commit() }
}

/** A failed write is dropped without an exception, as shared preferences drop it. */
private class FilePreferences(private val file: File) : Preferences {
    private fun read() = Properties().apply { if (file.isFile) file.inputStream().use(::loadFromXML) }
    private fun write(key: String, value: String) = synchronized(lock) {
        runCatching {
            val all = read().apply { setProperty(key, value) }
            file.parentFile?.mkdirs()
            file.outputStream().use { all.storeToXML(it, null) }
        }
        Unit
    }
    override fun flag(key: String) = read().getProperty(key) == "true"
    override fun setFlag(key: String, on: Boolean) = write(key, on.toString())
    override fun names(key: String) = read().getProperty(key).orEmpty().split('\n').filterTo(linkedSetOf()) { it.isNotEmpty() }
    override fun setNames(key: String, names: Set<String>) = write(key, names.joinToString("\n"))
    private companion object { val lock = Any() }
}
