package ch.og.dictation.storage

import android.content.ClipboardManager
import android.content.Context

/** What Copy last wrote in this build, for the shared host tests: release writes the system clipboard. */
fun lastCopy(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
