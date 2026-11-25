package ch.og.dictation.history

import ch.og.dictation.R
import ch.og.dictation.wiring
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.menu.ActionMenuItemView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The History page on the laptop, for the suites that ran on the phone (#79): the real activity
 * and layouts on the build's History root (the test layout's, in the lab build), an Undo window
 * each case sets, and the main looper driven by hand.
 */
abstract class HistoryPageCase {
    class Page : HistoryActivity() {
        override val undoWindowMs: Long get() = undoWindow
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Dictation)
            super.onCreate(savedInstanceState)
        }
    }

    companion object { @Volatile var undoWindow = 8000L }

    protected val app: Application get() = RuntimeEnvironment.getApplication()
    protected val root: File get() = app.wiring.storage.historyRoot
    protected val store get() = HistoryStore(root)
    protected val density get() = app.resources.displayMetrics.density
    private val controllers = mutableListOf<ActivityController<Page>>()

    @Before fun emptyHistory() {
        undoWindow = 8000L
        root.deleteRecursively()
        HistoryNotice.entries.forEach(historyNotices(app)::dismiss)
        HistoryProcess.owner.activeIds().forEach(HistoryProcess.owner::remove)
    }

    @After fun closePages() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        HistoryProcess.owner.activeIds().forEach(HistoryProcess.owner::remove)
        onHistoryThread {}
        root.deleteRecursively()
    }

    /** Runs [work] on History's own thread, behind whatever the page queued there. */
    protected fun <T> onHistoryThread(work: () -> T): T = HistoryIo.executor.submit(work).get(10, TimeUnit.SECONDS)

    protected fun save(vararg entries: HistoryEntry) = onHistoryThread { entries.forEach(HistoryIo.store(root)::save) }

    /** A resumed, laid-out page; closed after the case. */
    protected fun open(): ActivityController<Page> =
        Robolectric.buildActivity(Page::class.java).create().start().resume().visible().also { controllers += it }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets [ms] of main-looper time pass: the Undo window, a Snackbar. */
    protected fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs the main looper, and waits for History's thread, until [done]. */
    protected fun await(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        do {
            idle()
            if (done()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("never: $what")
    }

    /** What a TextView says: its text, or for an icon-only action its content description. */
    protected fun label(view: TextView): String =
        if (view is ActionMenuItemView || view.text.isNullOrEmpty()) view.contentDescription?.toString().orEmpty() else view.text.toString()

    protected fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        return null
    }

    /** The first shown TextView saying [value], in [within]. */
    protected fun findText(within: View, value: String, exact: Boolean = false): TextView? =
        find(within) { it is TextView && it.isShown && (if (exact) label(it) == value else label(it).contains(value)) } as TextView?

    protected fun shows(page: Page, value: String, exact: Boolean = false) = findText(page.window.decorView, value, exact) != null

    protected fun awaitText(page: Page, value: String, exact: Boolean = false): TextView {
        await("showing \"$value\"") { shows(page, value, exact) }
        return findText(page.window.decorView, value, exact)!!
    }

    protected fun awaitGone(page: Page, value: String) = await("no longer showing \"$value\"") { !shows(page, value) }

    /** The row or button a tap on [view] reaches. */
    protected fun target(view: View): View = generateSequence(view) { it.parent as? View }.first { it.isClickable }

    protected fun click(view: View) { target(view).performClick(); idle() }

    /** The row whose preview says [preview]: its body, one node for tap and long-press. */
    protected fun awaitRow(page: Page, preview: String): View = target(awaitText(page, preview))

    protected fun rowTrash(row: View): View = (row.parent as View).findViewById(R.id.history_row_delete)

    protected fun toolbar(page: Page): MaterialToolbar = page.findViewById(R.id.history_toolbar)

    protected fun stored(id: String) = File(root, "$id.history").exists()
}
