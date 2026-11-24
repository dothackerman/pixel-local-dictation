package ch.og.dictation.storage

import android.content.Context
import java.io.File

/** The app-private folders Android hands out; a temporary base stands in for them in host tests. */
internal class AppFolders(val noBackup: File)

/**
 * Where one build type keeps each kind of data (ADR-028). Exactly one implementation is compiled
 * into a build, as `buildLayout`: the owner layout in release, the test layout in the lab build.
 */
internal interface StorageLayout {
    /** The layout word of the build marker: `owner` or `test`. */
    val name: String
    fun historyRoot(folders: AppFolders): File
    /** Throws unless this layout allows [root] as a History root. */
    fun requireHistoryRoot(folders: AppFolders, root: File)
}

/**
 * Storage: the only code that resolves app-private files and folders. One accessor per data kind;
 * which folders they name is fixed by the build's layout, never chosen at run time.
 */
class Storage internal constructor(internal val folders: AppFolders, history: File) {
    init { buildLayout.requireHistoryRoot(folders, history) }

    /** `owner` or `test`, as in the build marker. */
    val layout: String get() = buildLayout.name

    /** Where takes are kept. */
    val historyRoot: File = history

    /** The same storage with History kept at [root]; the layout refuses a root outside its folders. */
    internal fun withHistoryRoot(root: File) = Storage(folders, root)

    companion object {
        fun forApp(context: Context) = of(AppFolders(context.applicationContext.noBackupFilesDir))

        /** The build's layout below [base] instead of the app's data folder: host tests. */
        fun under(base: File) = of(AppFolders(File(base, "no_backup")))

        private fun of(folders: AppFolders) = Storage(folders, buildLayout.historyRoot(folders))
    }
}
