package ch.og.dictation.probe

import ch.og.dictation.wiring
import android.content.Context
import java.io.File

/** Benchmark History and Settings screens refuse to start on the owner's History root (#65). */
fun Context.refuseOwnersHistory(root: File) =
    check(root.absoluteFile != wiring.historyRoot.absoluteFile) { "test screen refused: it would open the owner's History" }
