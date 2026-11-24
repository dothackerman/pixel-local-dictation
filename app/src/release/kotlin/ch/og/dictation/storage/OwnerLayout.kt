package ch.og.dictation.storage

import java.io.File

/** Release: owner data, where the owner's daily keyboard has always kept it. */
internal object OwnerLayout : StorageLayout {
    /** Reserved for timing receipts (#63), relative to the app's data folder; nothing writes it yet. */
    const val TIMING_RECEIPTS = "files/timing-receipts"

    override val name = "owner"
    override fun historyRoot(folders: AppFolders) = File(folders.noBackup, "history")
    override fun requireHistoryRoot(folders: AppFolders, root: File) =
        require(root == historyRoot(folders)) { "the owner layout has one History root" }
}

internal val buildLayout: StorageLayout = OwnerLayout
