package ch.og.dictation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Puts a take's text on the clipboard as plain text (History's copy and the panel's Copy).
 * No sensitive-content flag, by owner decision. minSdk is 36: the system shows its own copy
 * confirmation, so no Toast or Snackbar of ours.
 */
fun copyTranscript(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Transcript", text))
}
