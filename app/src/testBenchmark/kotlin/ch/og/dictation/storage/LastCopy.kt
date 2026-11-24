package ch.og.dictation.storage

import ch.og.dictation.wiring
import android.content.Context

/** What Copy last wrote in this build, for the shared host tests: the lab build's in-app clipboard. */
fun lastCopy(context: Context): String? = context.wiring.storage.copiedText
