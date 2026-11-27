package ch.og.dictation

import ch.og.dictation.dictionary.DictionaryStore
import ch.og.dictation.history.HistoryCompletion
import ch.og.dictation.history.HistoryDelivery
import ch.og.dictation.history.HistoryEntry
import ch.og.dictation.history.HistoryMarker
import ch.og.dictation.history.HistoryNotice
import ch.og.dictation.history.HistoryPageCase
import ch.og.dictation.history.HistoryProcess
import ch.og.dictation.history.HistoryRows
import ch.og.dictation.history.historyNotices
import ch.og.dictation.panel.PanelImages
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings and History as gallery images (#79; they were captures of the phone suite of this name):
 * `app/build/panel-gallery/after/screens/<look>/<screen>.png`, in portrait in the one light scheme
 * at font 1× and 2×. Settings with the switch off and on; the History list, its overflow menu, the
 * warning row, the marks sheet (plain and opened at a mark), a detail, one detail per mark, the
 * Clear confirmation, a deleted row with Undo, and the empty state. Synthetic text at fixed times in
 * one zone and language, so the same checkout gives the same bytes; Today and Yesterday headers
 * depend on the day of the run and are not shown. tools/visible_change.py compares the images.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHistoryScreenshotTest : HistoryPageCase() {
    class Settings : SettingsActivity() {
        override fun onCreate(savedInstanceState: Bundle?) { setTheme(R.style.Theme_Dictation); super.onCreate(savedInstanceState) }
    }

    private val looks = PanelImages.LOOKS.filter { it.name in setOf("portrait-light", "portrait-font2") }
    private val screens = listOf("settings-switch-off", "settings-switch-on", "history-list", "history-menu", "history-warning",
        "history-marks", "history-detail-marked", "history-marks-at-mark", "history-detail", "history-clear-dialog",
        "history-deleted-undo", "history-detail-recording", "history-detail-preparing", "history-detail-cut-short",
        "history-detail-not-inserted", "history-detail-uncertain", "history-empty")

    /** 14 March 2020, 09:26:40 UTC; every year shows, so no image changes with the day of the run. */
    private val day = 1_584_178_000_000L
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private val entries = listOf(
        HistoryEntry(id(1), day, "Pick up bread and milk on the way home.", HistoryDelivery.ACCEPTED),
        HistoryEntry(id(2), day - 3_600_000, "Grüße an alle, wir kommen etwa um halb acht und bringen Brot mit.", HistoryDelivery.UNKNOWN),
        HistoryEntry(id(3), day - 86_400_000, "Meeting notes: review the project plan tomorrow, then send the summary to everyone who joined the call.", HistoryDelivery.REJECTED),
        HistoryEntry(id(4), day - 172_800_000, "Remind me to call the dentist about the appointment next week.", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED, 1),
    )
    private val marked = linkedMapOf(
        HistoryMarker.RECORDING to HistoryEntry(id(11), day - 30_000, "Still talking about the garden plan and the new", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.RECORDING),
        HistoryMarker.PREPARING to HistoryEntry(id(12), day - 90_000, "Book the train to Bern for Friday morning.", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.FINISHING),
        HistoryMarker.CUT_SHORT to HistoryEntry(id(13), day - 600_000, "Notes for the call: budget, timeline and the", HistoryDelivery.NOT_ATTEMPTED, HistoryCompletion.INTERRUPTED),
        HistoryMarker.NOT_INSERTED to HistoryEntry(id(14), day - 1_200_000, "Bitte bring morgen das Ladekabel mit.", HistoryDelivery.REJECTED),
        HistoryMarker.UNCERTAIN to HistoryEntry(id(15), day - 1_800_000, "See you at the station at seven.", HistoryDelivery.UNKNOWN),
    )

    @Test fun settingsAndHistory() {
        val folder = File(PanelImages.GALLERY, "after/screens")
        folder.deleteRecursively()
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        Locale.setDefault(Locale.UK)
        try {
            for (look in looks) {
                RuntimeEnvironment.setQualifiers(look.qualifiers)
                RuntimeEnvironment.setFontScale(look.fontScale)
                val into = File(folder, look.name).apply { mkdirs() }
                capture { name, view, underlay -> File(into, "$name.png").writeBytes(png(view, underlay)) }
            }
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
        for (look in looks) {
            val images = File(folder, look.name).listFiles()!!
            assertEquals(look.name, screens.toSet(), images.map { it.nameWithoutExtension }.toSet())
            assertTrue(images.all { it.length() > 1000 })
            // Each screen is its own picture.
            assertEquals(look.name, images.size, images.map { it.readBytes().contentHashCode() }.toSet().size)
        }
    }

    /** Walks Settings and History once in the current look and hands each screen to [shot]. */
    private fun capture(shot: (name: String, view: View, underlay: View?) -> Unit) {
        root.deleteRecursively()
        save(*entries.toTypedArray())
        DictionaryStore(app.wiring.storage.dictionaryRoot, File(app.wiring.storage.dictionaryRoot, "none")).let {
            if (!it.read().edited) it.commit(it.prepare(listOf("Bärlauch", "GrapheneOS", "Matterhorn", "SBB Mobile", "Zürich HB")), it.read())
        }

        val settings = Robolectric.buildActivity(Settings::class.java).create().start().resume().visible()
        try {
            val page = settings.get()
            await("the History and Dictionary counts") {
                page.findViewById<View>(R.id.settings_history_count).isShown && page.findViewById<View>(R.id.settings_dictionary_count).isShown
            }
            assertEquals("4 saved", page.findViewById<TextView>(R.id.settings_history_count).text.toString())
            // Both switch states, drawn without touching the stored benchmark setting.
            val switch = page.findViewById<MaterialSwitch>(R.id.benchmark_switch)
            switch.setOnCheckedChangeListener(null)
            for (on in listOf(false, true)) {
                switch.isChecked = on
                switch.jumpDrawablesToCurrentState()
                idle()
                shot("settings-switch-${if (on) "on" else "off"}", page.window.decorView, null)
            }
        } finally { settings.pause().stop().destroy() }

        val list = open()
        var page = list.get()
        awaitText(page, entries.first().text)
        awaitText(page, entries.last().text)
        shot("history-list", page.window.decorView, null)

        // The overflow menu: Marks explained, then Clear History… in the error colour.
        assertTrue(toolbar(page).showOverflowMenu())
        idle()
        val menu = WindowInspector.getGlobalWindowViews().last()
        assertNotSame("overflow menu did not open", page.window.decorView, menu.rootView)
        shot("history-menu", menu, page.window.decorView)
        toolbar(page).hideOverflowMenu()
        idle()

        // Warning row on the History page; dismissed through the row's own button.
        historyNotices(app).record(HistoryNotice.SAVE)
        list.recreate()
        page = list.get()
        awaitText(page, app.getString(R.string.history_save_error))
        awaitText(page, entries.first().text)
        shot("history-warning", page.window.decorView, null)
        page.findViewById<View>(R.id.history_status_action).performClick()
        idle()
        assertEquals(View.GONE, page.findViewById<View>(R.id.history_status).visibility)

        // Marks explained, from the overflow menu.
        fun sheet() = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(R.id.history_marks_rows) != null }
        toolbar(page).menu.performIdentifierAction(R.id.history_marks_item, 0)
        idle()
        shot("history-marks", sheet(), page.window.decorView)
        sheet().findViewById<View>(R.id.history_marks_close).performClick()
        idle()
        // A detail with a mark: its line opens the sheet at that mark.
        click(awaitRow(page, entries[2].text))
        awaitText(page, app.getString(R.string.history_copy_transcript))
        shot("history-detail-marked", page.window.decorView, null)
        page.findViewById<View>(R.id.history_detail_marker).performClick()
        idle()
        shot("history-marks-at-mark", sheet(), page.window.decorView)
        sheet().findViewById<View>(R.id.history_marks_close).performClick()
        idle()
        page.onBackPressedDispatcher.onBackPressed()
        click(awaitRow(page, entries.first().text))
        awaitText(page, app.getString(R.string.history_copy_transcript))
        shot("history-detail", page.window.decorView, null)
        page.onBackPressedDispatcher.onBackPressed()
        awaitText(page, entries.first().text)

        assertTrue(toolbar(page).menu.findItem(R.id.history_clear_item).isEnabled)
        toolbar(page).menu.performIdentifierAction(R.id.history_clear_item, 0)
        idle()
        val dialog = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(android.R.id.message) != null }
        shot("history-clear-dialog", dialog, page.window.decorView)
        dialog.findViewById<View>(android.R.id.button2).performClick()
        idle()
        assertEquals(entries.size, root.listFiles().orEmpty().count { it.name.endsWith(".history") })

        // Delete from a row: the row goes, the Snackbar offers Undo; Undo brings it back.
        undoWindow = 60_000
        find(page.window.decorView) {
            it.isShown && it.contentDescription == app.getString(R.string.history_delete_row, HistoryRows.date(entries.first().timestamp),
                HistoryRows.previewWords(entries.first().text))
        }!!.performClick()
        idle()
        assertFalse(shows(page, entries.first().text))
        shot("history-deleted-undo", page.window.decorView, null)
        awaitText(page, app.getString(R.string.dictionary_undo), exact = true).performClick()
        awaitText(page, entries.first().text)
        list.pause().stop().destroy()

        // One detail per mark: the mark's sentence under the meta line. In-progress marks need a live take.
        root.deleteRecursively()
        save(*marked.values.toTypedArray())
        HistoryProcess.owner.register(marked.getValue(HistoryMarker.RECORDING).id)
        marked.getValue(HistoryMarker.PREPARING).id.let { HistoryProcess.owner.register(it); HistoryProcess.owner.finishing(it) }
        val marks = open()
        for ((mark, entry) in marked) {
            click(awaitRow(marks.get(), entry.text))
            awaitText(marks.get(), app.getString(mark.line))
            shot("history-detail-${mark.name.lowercase().replace('_', '-')}", marks.get().window.decorView, null)
            marks.get().onBackPressedDispatcher.onBackPressed()
            idle()
        }
        marks.pause().stop().destroy()
        marked.values.forEach { HistoryProcess.owner.remove(it.id) }

        root.deleteRecursively()
        val blank = open()
        awaitText(blank.get(), app.getString(R.string.history_empty))
        shot("history-empty", blank.get().window.decorView, null)
        blank.pause().stop().destroy()
    }

    /** [view] as PNG; a menu, sheet or dialog is drawn where it sits over [underlay], its page, dimmed as the window asks. */
    private fun png(view: View, underlay: View?): ByteArray {
        check(view.width > 0 && view.height > 0)
        val page = underlay ?: view
        val image = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            underlay?.let {
                it.draw(canvas)
                val params = view.layoutParams as? WindowManager.LayoutParams
                val amount = if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) params.dimAmount else 0f
                canvas.drawColor(Color.argb((255 * amount).toInt(), 0, 0, 0))
                val at = IntArray(2).also(view::getLocationOnScreen)
                val pageAt = IntArray(2).also(it::getLocationOnScreen)
                canvas.translate((at[0] - pageAt[0]).toFloat(), (at[1] - pageAt[1]).toFloat())
            }
            view.draw(canvas)
            return java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { image.recycle() }
    }
}
