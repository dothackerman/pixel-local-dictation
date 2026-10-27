package ch.og.dictation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

/** Survives rotation; saving finishes even if the editor closes. No entry text reaches diagnostics. */
class DictionaryModel(application: Application) : AndroidViewModel(application) {
    data class State(val snapshot: DictionarySnapshot? = null, val busy: Boolean = true,
        val error: Boolean = false, val event: Int = 0, val action: String = "", val problem: Problem? = null)
    enum class Problem { CONFLICT, MODEL_UNAVAILABLE }
    val state = MutableLiveData(State())
    private val reader = Executors.newSingleThreadExecutor()
    private var handledEvent = 0
    var undoEntries: List<String>? = null
        private set

    init { reload() }

    /** Completion and acknowledgement share the ViewModel lifetime, including rotation. */
    fun consumeCompletion(): State? {
        val current = state.value ?: return null
        if (current.busy || current.event <= handledEvent) return null
        handledEvent = current.event
        return current
    }

    fun reload() {
        val event = state.value?.event ?: 0
        state.value = State(event = event)
        reader.execute {
            val result = runCatching { Engine.dictionaryStore(getApplication()).read() }
            getApplication<Application>().mainExecutor.execute {
                state.value = result.fold({ State(it, false, event = event) }, { State(busy = false, error = true, event = event) })
            }
        }
    }

    fun save(entries: List<String>, action: String) {
        val before = state.value ?: return
        val snapshot = before.snapshot ?: return
        if (before.busy) return
        undoEntries = null
        state.value = before.copy(busy = true, error = false, action = action, problem = null)
        Engine.saveDictionary(getApplication(), snapshot, entries).whenComplete { saved, failure ->
            val cause = generateSequence(failure) { it.cause }.lastOrNull()
            if (cause is DictionaryRevisionConflict) {
                // Never retry an old whole-list mutation with a new revision token.
                // Refresh first; the Activity retains its draft and rebuilds the next mutation.
                reader.execute {
                    val fresh = runCatching { Engine.dictionaryStore(getApplication()).read() }
                    getApplication<Application>().mainExecutor.execute {
                        state.value = State(fresh.getOrNull(), false, true, before.event + 1, action, Problem.CONFLICT)
                    }
                }
                return@whenComplete
            }
            getApplication<Application>().mainExecutor.execute {
                if (failure == null && action == "delete") undoEntries = snapshot.entries
                state.value = State(saved ?: snapshot, false, failure != null, before.event + 1, action,
                    if (cause is DictionaryModelUnavailable) Problem.MODEL_UNAVAILABLE else null)
            }
        }
    }

    fun undo() { undoEntries?.let { save(it, "undo") } }
    override fun onCleared() { reader.shutdown() }
}
