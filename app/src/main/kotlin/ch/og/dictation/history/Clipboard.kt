package ch.og.dictation.history

import ch.og.dictation.wiring
import android.content.Context

/**
 * Puts a take's text on the clipboard as plain text (History's copy and the panel's Copy): the
 * system clipboard in release, the in-app one in the lab build (ADR-028). No sensitive-content
 * flag, by owner decision. minSdk is 36: the system shows its own copy confirmation, so no Toast
 * or Snackbar of ours.
 */
fun copyTranscript(context: Context, text: String) = context.wiring.storage.clipboard.copy(text)
