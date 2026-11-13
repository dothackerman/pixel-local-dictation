package ch.og.dictation

import android.app.Application
import java.io.File

open class DictationApplication : Application() {
    protected open fun historyRoot() = File(noBackupFilesDir, "history")
    protected open fun ownsHistory() = getProcessName() == applicationInfo.processName

    override fun onCreate() {
        super.onCreate()
        if (ownsHistory()) HistoryIo.start(historyRoot())
    }
}
