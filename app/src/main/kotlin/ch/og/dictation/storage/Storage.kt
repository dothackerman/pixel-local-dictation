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
 * What Android hands the app: its private folders, its named preferences and the system
 * clipboard. A temporary base stands in for all three in host tests.
 */
internal class AppData(val files: File, val noBackup: File, val preferences: (String) -> Preferences, val systemClipboard: Clipboard)

/**
 * Where one build type keeps each kind of data (ADR-028). Exactly one implementation is compiled
 * into a build, as `buildLayout`: the owner layout in release, the test layout in the lab build.
 */
internal interface StorageLayout {
    /** The layout word of the build marker: `owner` or `test`. */
    val name: String
    fun historyRoot(data: AppData): File
    /** Throws unless this layout allows [root] as a History root. */
    fun requireHistoryRoot(data: AppData, root: File)
    fun dictionaryRoot(data: AppData): File
    /** The preferences name of the History notices. */
    val historyNotices: String
    /** The preferences name of the settings. */
    val settings: String
    /** The clipboard Copy writes; [system] is the phone's. */
    fun clipboard(system: Clipboard): Clipboard
}

/**
 * Storage: the only code that resolves app-private files, folders and preferences and the only
 * code that writes the clipboard. One accessor per data kind; what they name is fixed by the
 * build's layout, never chosen at run time.
 */
class Storage private constructor(
    internal val data: AppData,
    history: File,
    /** Copy's target: the system clipboard in release; in the lab build a text kept in the app. */
    val clipboard: Clipboard,
) {
    init { buildLayout.requireHistoryRoot(data, history) }

    /** `owner` or `test`, as in the build marker. */
    val layout: String get() = buildLayout.name

    /** Where takes are kept. */
    val historyRoot: File = history

    /** The Dictionary's saved revisions and the pointer to the current one. */
    val dictionaryRoot: File get() = buildLayout.dictionaryRoot(data)

    /** The pending History warnings: kinds only. */
    val historyNotices: Preferences get() = data.preferences(buildLayout.historyNotices)

    /** The switches of the Settings page. */
    val settings: Preferences get() = data.preferences(buildLayout.settings)

    /** The same storage with History kept at [root]; the layout refuses a root outside its folders. */
    internal fun withHistoryRoot(root: File) = Storage(data, root, clipboard)

    companion object {
        fun forApp(context: Context) = context.applicationContext.let { app ->
            of(AppData(app.filesDir, app.noBackupFilesDir,
                { name -> AndroidPreferences(app.getSharedPreferences(name, Context.MODE_PRIVATE)) },
                // Plain text without the sensitive-content flag, by owner decision.
                { text -> (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Transcript", text)) }))
        }

        /**
         * The build's layout below [base] instead of the app's data folder: host tests. Preferences
         * are files in `shared_prefs`; the file `clipboard` stands in for the system clipboard.
         */
        fun under(base: File) = of(AppData(File(base, "files"), File(base, "no_backup"),
            { name -> FilePreferences(File(base, "shared_prefs/$name.xml")) },
            { text -> runCatching { base.mkdirs(); File(base, "clipboard").writeText(text) } }))

        private fun of(data: AppData) = Storage(data, buildLayout.historyRoot(data), buildLayout.clipboard(data.systemClipboard))
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
