package ch.og.dictation

import ch.og.dictation.history.startHistory
import android.app.Application

open class DictationApplication : Application() {
    @Volatile private var current: Wiring? = null

    /** This process's [Wiring], created on first use. */
    val wiring: Wiring
        get() = current ?: synchronized(this) { current ?: Wiring.forApp(this).also { current = it } }

    internal fun replaceWiring(value: Wiring) { synchronized(this) { current = value } }

    override fun onCreate() {
        super.onCreate()
        startHistory(owns = getProcessName() == applicationInfo.processName, root = wiring.historyRoot)
    }
}
