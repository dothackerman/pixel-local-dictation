package ch.og.dictation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

/** Survives rotation; saving finishes even if the editor closes. No entry text reaches diagnostics. */
class DictionaryModel(application: Application) : AndroidViewModel(application) {
    data class State(val snapshot: DictionarySnapshot? = null, val busy: Boolean = true,
        val error: Boolean = false, val event: Int = 0, val action: String = "")
    val state = MutableLiveData(State())
    private val reader = Executors.newSingleThreadExecutor()
    var undoEntries: List<String>? = null
        private set

    init { reload() }

    fun reload() {
        state.value = State()
        reader.execute {
            val result = runCatching { Engine.dictionaryStore(getApplication()).read() }
            getApplication<Application>().mainExecutor.execute {
                state.value = result.fold({ State(it, false) }, { State(busy = false, error = true) })
            }
        }
    }

    fun save(entries: List<String>, action: String) {
        val before = state.value ?: return
        val snapshot = before.snapshot ?: return
        if (before.busy) return
        undoEntries = null
        state.value = before.copy(busy = true, error = false, action = action)
        Engine.saveDictionary(getApplication(), snapshot, entries).whenComplete { saved, failure ->
            getApplication<Application>().mainExecutor.execute {
                if (failure == null && action == "delete") undoEntries = snapshot.entries
                state.value = State(saved ?: snapshot, false, failure != null, before.event + 1, action)
            }
        }
    }

    fun undo() { undoEntries?.let { save(it, "undo") } }
    override fun onCleared() { reader.shutdown() }
}
