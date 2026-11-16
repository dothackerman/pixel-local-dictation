package ch.og.dictation

import ch.og.dictation.audio.Recorder
import ch.og.dictation.recognition.Engine
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import java.io.File

/**
 * Wiring: what this process's parts are built from, one immutable value per process, held by
 * [DictationApplication] and reached through [Context.wiring]. Read it at each use; a take
 * captures it once, so its History root cannot change mid-take. Tests replace it only through
 * [WiringOverride], which derives from the current value and always restores it.
 */
class Wiring private constructor(
    /** A fresh platform recorder per take. */
    val recorderFactory: () -> Recorder,
    /** Where takes are kept; the one definition of the owner's History root is [forApp]. */
    val historyRoot: File,
    private val engineSource: Lazy<Engine>,
    /** Asks Android to show the input method's own panel (leaves Wiring with the take controller). */
    val requestPanel: (InputMethodService) -> Unit,
    /** Delivers an unexpected capture end to the main thread (leaves Wiring with the take controller). */
    val postCaptureEnd: (Handler, Runnable) -> Unit,
) {
    /** Created once, on first use: editing Settings or the Dictionary never loads speech recognition. */
    val engine: Engine get() = engineSource.value

    /** A derived value; unchanged parts, the engine included, are shared. */
    fun with(
        recorderFactory: () -> Recorder = this.recorderFactory,
        historyRoot: File = this.historyRoot,
        engine: Engine? = null,
        requestPanel: (InputMethodService) -> Unit = this.requestPanel,
        postCaptureEnd: (Handler, Runnable) -> Unit = this.postCaptureEnd,
    ) = Wiring(recorderFactory, historyRoot, engine?.let { lazyOf(it) } ?: engineSource, requestPanel, postCaptureEnd)

    companion object {
        fun forApp(context: Context): Wiring {
            val app = context.applicationContext
            return Wiring(::Recorder, File(app.noBackupFilesDir, "history"), lazy { Engine.forApp(app) },
                { it.requestShowSelf(0) }, { handler, callback -> handler.post(callback) })
        }
    }
}

/** This process's [Wiring]. */
val Context.wiring: Wiring
    get() = (applicationContext as? DictationApplication ?: error("not the dictation application")).wiring

/** Test-only scoped replacement of this process's [Wiring]; [close] restores the value it started from. */
class WiringOverride internal constructor(private val app: DictationApplication) : AutoCloseable {
    private val original = app.wiring

    /** Replaces the current value by one derived from it. */
    fun set(change: (Wiring) -> Wiring) { app.replaceWiring(change(app.wiring)) }

    override fun close() { app.replaceWiring(original) }
}

/** Starts a scoped replacement derived from the current [Wiring]. */
fun Context.overrideWiring(change: (Wiring) -> Wiring = { it }): WiringOverride {
    val app = applicationContext as? DictationApplication ?: error("not the dictation application")
    return WiringOverride(app).also { it.set(change) }
}
