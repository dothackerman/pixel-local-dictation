package ch.og.dictation.storage

import java.io.File

/** The lab build keeps the Benchmark switch and saves recordings into its own app's folder. */
internal const val SAVES_RECORDINGS = true

/**
 * Copy stays in the app: the system clipboard is one for all apps, so the lab build never writes the
 * owner's, though its storage is its own (#98).
 */
@Suppress("UNUSED_PARAMETER")
internal fun copyTarget(system: Clipboard): Clipboard = InAppClipboard()

/** The lab build's Copy target: the last copied text, held in this process only. */
internal class InAppClipboard : Clipboard {
    @Volatile var text: String? = null
        private set
    override fun copy(text: String) { this.text = text }
}

/** A further History root for screens and processes under test, apart from the lab app's own History. */
fun Storage.testHistoryRoot(name: String): File = File(historyRoot.parentFile, "test/$name")

/** Short-lived files of device tests. */
val Storage.scratchFolder: File get() = File(labFolder.parentFile, "test/scratch")

/** The text Copy put on the lab build's in-app clipboard; null before the first copy. */
val Storage.copiedText: String? get() = (clipboard as InAppClipboard).text
