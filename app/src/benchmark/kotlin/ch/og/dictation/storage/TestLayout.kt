package ch.og.dictation.storage

import java.io.File

/**
 * Lab build: test folders only, no owner path. The folder set is published for the phone wrapper
 * in `app/src/benchmark/test-folders.json` (relative to the app's data folder) and never changes.
 */
internal object TestLayout : StorageLayout {
    /** The speech model and test output; shared with release, which reads only the model. */
    const val LAB_FOLDER = "files/lab"
    /** Dictionary and recordings; History; History notices; settings. */
    val TEST_FOLDERS = listOf("files/test", "no_backup/test",
        "shared_prefs/test_history_notices.xml", "shared_prefs/test_settings.xml")

    override val name = "test"
    private fun historyFolder(folders: AppFolders) = File(folders.noBackup, "test")
    override fun historyRoot(folders: AppFolders) = File(historyFolder(folders), "history")

    override fun requireHistoryRoot(folders: AppFolders, root: File) {
        val folder = historyFolder(folders).canonicalFile.toPath()
        val path = root.canonicalFile.toPath()
        require(path != folder && path.startsWith(folder)) { "outside the test folders: $root" }
    }

    fun testHistoryRoot(folders: AppFolders, name: String) =
        File(historyFolder(folders), name).also { requireHistoryRoot(folders, it) }
}

internal val buildLayout: StorageLayout = TestLayout

/** A further History root inside the test folders, for screens and processes under test. */
fun Storage.testHistoryRoot(name: String): File = TestLayout.testHistoryRoot(folders, name)

/** This storage with History kept at the test root [name]. */
fun Storage.withTestHistory(name: String): Storage = withHistoryRoot(testHistoryRoot(name))
