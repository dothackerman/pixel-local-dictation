package ch.og.dictation

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seconds-long gate that starts every device session (DEVELOPMENT.md, "Phone lab operations"). Reads
 * state only: never unlocks, wakes, selects a keyboard or changes a setting. Prints one line per
 * unmet precondition so the owner knows exactly what to do before the batched run.
 */
@RunWith(AndroidJUnit4::class)
class PhoneReadyTest {
    @Test fun phoneIsReadyForDeviceSuites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val unmet = mutableListOf<String>()
        if (!context.getSystemService(PowerManager::class.java).isInteractive) unmet += "screen off: owner wakes the phone"
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) unmet += "locked: owner unlocks the phone"
        val ime = KeyboardHandoff(instrumentation) {}
        if (!ime.futoSelected()) unmet += "FUTO is not the selected keyboard (selected: ${ime.currentIme()?.substringBefore('/') ?: "unknown"})"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            unmet += "microphone permission not granted to the app"
        }
        val lab = File(context.filesDir, "lab")
        for (fixture in listOf("silero_vad.onnx", "models/parakeet-v3-fp32/encoder.onnx",
            "issue36-inputs/recovery_continuous_60s.wav", "issue36-inputs/recovery_paused_60s.wav")) {
            if (!File(lab, fixture).isFile) unmet += "fixture missing: files/lab/$fixture (phone push)"
        }
        val report = if (unmet.isEmpty()) "READY" else unmet.joinToString("\n") { "NOT READY: $it" }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", report + "\n") })
        assertTrue(report, unmet.isEmpty())
    }
}
