package ch.og.dictation.labrun

import ch.og.dictation.wiring
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Lab run harness (ADR-028). A Lab run measures; it is completed or incomplete, never passed or failed.
 * Every class of this package runs through `phone measure` only, and each `@Test` is
 * `@Test fun name() = LabRun.measure("…") { lab -> … }`.
 *
 * Inputs come only from the lab folder: what `phone push` put there, and under [INPUTS] the copies of
 * owner recordings or receipts that `phone measure … inputs=…` placed for this run and removes after
 * it. Results go to the lab folder. tools/device_classes.py checks all of this on the laptop.
 */
object LabRun {
    /** Inside the lab folder; `LAB_RUN_INPUTS` in tools/phone names the same folder. */
    const val INPUTS = "labrun-inputs"
    /** The lines `phone measure` reads from the instrumentation output, one per Lab run. */
    const val COMPLETED = "LAB RUN completed"
    const val INCOMPLETE = "LAB RUN incomplete"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    val lab: File get() = instrumentation.targetContext.wiring.storage.labFolder

    /**
     * The copies `phone measure` placed for this run: `inputs("benchmark")` holds what
     * `inputs=files/benchmark…` named, `inputs("timing-receipts")` the receipts. Absent when none was named.
     */
    fun inputs(kind: String): File = File(File(lab, INPUTS), kind)

    /**
     * Runs [body] with the lab folder and reports one line: completed, or incomplete with the kind of
     * the reason. Nothing [body] throws fails the instrumentation; an unmet precondition, a broken-off
     * collection and an error all leave the run incomplete.
     */
    fun measure(name: String, body: (lab: File) -> Unit) {
        val line = try {
            body(lab)
            "$COMPLETED: $name"
        } catch (reason: Throwable) {
            Log.w("LabRun", "$name incomplete", reason)
            "$INCOMPLETE: $name: ${reason.javaClass.simpleName}: ${reason.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(200)}"
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
    }
}
