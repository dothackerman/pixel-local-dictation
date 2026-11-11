package ch.og.dictation

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * #36 Crate B2: every text pair the app draws reaches 4.5:1 and every essential indicator
 * (disk, ring, arc, markers, control edges) 3:1, resolved from Theme.Dictation itself, in light
 * and night configuration alike (one fixed scheme).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeContrastTest {
    private fun theme(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Dictation)
    private fun Context.role(attr: Int): Int {
        val value = TypedValue()
        assertTrue("missing role $attr", theme.resolveAttribute(attr, value, true))
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }
    private fun luminance(color: Int): Double {
        fun channel(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(color shr 16 and 0xFF) + 0.7152 * channel(color shr 8 and 0xFF) + 0.0722 * channel(color and 0xFF)
    }
    private fun ratio(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun attr(name: String): Int = RuntimeEnvironment.getApplication().let {
        it.resources.getIdentifier(name, "attr", it.packageName).also { id -> assertNotEquals("no attr $name", 0, id) }
    }

    private fun check(pairs: List<Triple<String, String, Double>>) {
        val context = theme()
        for ((fg, bg, needed) in pairs) {
            val r = ratio(context.role(attr(fg)), context.role(attr(bg)))
            assertTrue("$fg on $bg is %.2f:1, needs $needed:1".format(r), r >= needed)
        }
    }

    @Test fun textPairsReachFourAndAHalf() = check(listOf(
        Triple("colorOnSurface", "colorSurface", 4.5),            // transcripts, rows, titles
        Triple("colorOnSurface", "colorSurfaceContainer", 4.5),   // panel status
        Triple("colorOnSurfaceVariant", "colorSurface", 4.5),     // explanations, times, counts
        Triple("colorOnSurfaceVariant", "colorSurfaceContainer", 4.5), // panel preview, private-field line
        Triple("colorOnPrimary", "colorPrimary", 4.5),            // Undo label, glyphs on the disk
        Triple("colorPrimary", "colorSurfaceContainer", 4.5),     // red-orange on the panel
        Triple("colorPrimaryInverse", "colorSurfaceInverse", 4.5), // Snackbar Undo
        Triple("colorOnSurfaceInverse", "colorSurfaceInverse", 4.5), // Snackbar text
        Triple("colorError", "colorSurface", 4.5),                // Clear History, Delete benchmark data
        Triple("colorOnErrorContainer", "colorErrorContainer", 4.5), // benchmark banner
        Triple("colorOnSecondaryContainer", "colorSecondaryContainer", 4.5), // History warning row
        Triple("colorOnSurface", "colorSecondaryContainer", 4.5), // warning row text
        Triple("colorOnPrimaryContainer", "colorPrimaryContainer", 4.5), // text selection
    ))

    @Test fun essentialIndicatorsReachThree() = check(listOf(
        Triple("colorPrimary", "colorSurfaceContainer", 3.0),     // disk, recording ring, arc, Continue fill
        Triple("colorPrimary", "colorSurface", 3.0),              // recording marker in rows
        Triple("colorOutline", "colorSurfaceContainer", 3.0),     // idle hairline ring, panel rule
        Triple("colorOnSurface", "colorSurface", 3.0),            // markers in rows and the sheet
        Triple("colorError", "colorSurfaceContainer", 3.0),       // Discard glyph and dashed edge
        Triple("colorSecondary", "colorSurface", 3.0),            // switch on track
        Triple("colorOutline", "colorSurface", 3.0),              // switch off edge
        Triple("colorOnSurface", "colorSurfaceContainer", 3.0),   // Stop outline, selected ring, timer rule
    ))

    @Test @Config(qualifiers = "night") fun oneFixedSchemeAlsoInNightMode() {
        val context = theme()
        assertEquals(context.getColor(R.color.b2_surface), context.role(attr("colorSurface")))
        assertEquals(context.getColor(R.color.b2_primary), context.role(attr("colorPrimary")))
    }
}
