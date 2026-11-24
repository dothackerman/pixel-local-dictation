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
    private fun historyFolder(data: AppData) = File(data.noBackup, "test")
    override fun historyRoot(data: AppData) = File(historyFolder(data), "history")

    override fun requireHistoryRoot(data: AppData, root: File) {
        val folder = historyFolder(data).canonicalFile.toPath()
        val path = root.canonicalFile.toPath()
        require(path != folder && path.startsWith(folder)) { "outside the test folders: $root" }
    }

    override fun dictionaryRoot(data: AppData) = File(data.files, "test/dictionary")
    override val historyNotices = "test_history_notices"
    override val settings = "test_settings"
    /** In the app: the lab build never writes the system clipboard, which is the owner's. */
    override fun clipboard(system: Clipboard): Clipboard = InAppClipboard()

    fun testHistoryRoot(data: AppData, name: String) =
        File(historyFolder(data), name).also { requireHistoryRoot(data, it) }
}

/** The lab build's Copy target: the last copied text, held in this process only. */
internal class InAppClipboard : Clipboard {
    @Volatile var text: String? = null
        private set
    override fun copy(text: String) { this.text = text }
}

internal val buildLayout: StorageLayout = TestLayout

/** A further History root inside the test folders, for screens and processes under test. */
fun Storage.testHistoryRoot(name: String): File = TestLayout.testHistoryRoot(data, name)

/** This storage with History kept at the test root [name]. */
fun Storage.withTestHistory(name: String): Storage = withHistoryRoot(testHistoryRoot(name))

/** The text Copy put on the lab build's in-app clipboard; null before the first copy. */
val Storage.copiedText: String? get() = (clipboard as InAppClipboard).text
