package ch.og.dictation.panel

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel gallery (#73, #77): every panel state, each also in a private field, in portrait,
 * narrow and wide landscape, light, dark and at font 2×, as images in
 * `app/build/panel-gallery/after/states/<look>/<state>.png`. It replaces the captures of the
 * recording panel device suite. tools/visible_change.py stores them as BEFORE and compares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelGalleryTest {
    private val words = PanelPreview.tail("… zwei Hokkaido, eine Zwiebel, ein Stück Ingwer und Kokosmilch. " +
        "And please bring fresh coriander on Saturday. ".repeat(3).trim())
    private val states = (PanelStates.all(words, words) +
        ("recording-banner" to PanelState(PanelPhase.RECORDING, running = true, preview = words, banner = true, ringLive = true)))
        .map { (name, state) -> name to panelFrame(state) }

    @Test fun everyPanelStateInEveryLookIsRenderedToTheGallery() {
        val folder = File(PanelImages.GALLERY, "after/states")
        PanelImages.write(folder, states)
        val images = folder.walkTopDown().filter { it.extension == "png" }.toList()
        assertEquals(states.size * PanelImages.LOOKS.size, images.size)
        assertTrue(images.all { it.length() > 1000 })
        assertEquals(setOf("portrait-light", "portrait-dark", "portrait-font2", "narrow-landscape-light", "narrow-landscape-dark",
            "narrow-landscape-font2", "wide-landscape-light", "wide-landscape-dark", "wide-landscape-font2"),
            folder.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun aFrameGivesTheSameImageOnEveryRenderAndAnotherFrameAnotherImage() {
        val byName = states.toMap()
        for (look in PanelImages.LOOKS) for (name in listOf("recording", "preparing", "held-private", "paused")) {
            assertArrayEquals("$name ${look.name}", PanelImages.png(byName.getValue(name), look), PanelImages.png(byName.getValue(name), look))
        }
        val look = PanelImages.LOOKS.first()
        assertFalse(PanelImages.png(byName.getValue("idle"), look).contentEquals(PanelImages.png(byName.getValue("idle-private"), look)))
        assertFalse(PanelImages.png(byName.getValue("recording"), look).contentEquals(PanelImages.png(byName.getValue("recording-banner"), look)))
        assertFalse(PanelImages.png(byName.getValue("idle"), look).contentEquals(PanelImages.png(byName.getValue("idle"), PanelImages.LOOKS[2])))
    }
}
