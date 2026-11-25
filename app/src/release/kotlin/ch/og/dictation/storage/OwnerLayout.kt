package ch.og.dictation.storage

import java.io.File

/** Release: owner data, where the owner's daily keyboard has always kept it. */
internal object OwnerLayout : StorageLayout {
    /** Reserved for timing receipts (#63), relative to the app's data folder; nothing writes it yet. */
    const val TIMING_RECEIPTS = "files/timing-receipts"

    override val name = "owner"
    override fun historyRoot(data: AppData) = File(data.noBackup, "history")
    override fun requireHistoryRoot(data: AppData, root: File) =
        require(root == historyRoot(data)) { "the owner layout has one History root" }
    override fun dictionaryRoot(data: AppData) = File(data.files, "dictionary")
    override val historyNotices = "history_notices"
    override val settings = "settings"
    override fun clipboard(system: Clipboard) = system
    /** What earlier builds saved; release keeps only the control that deletes it (ADR-010 amendment). */
    override fun recordingsRoot(data: AppData) = File(data.files, "benchmark")
    override val savesRecordings = false
}

internal val buildLayout: StorageLayout = OwnerLayout
