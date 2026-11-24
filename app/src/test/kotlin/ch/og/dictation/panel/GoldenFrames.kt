package ch.og.dictation.panel

import java.io.File
import org.robolectric.RuntimeEnvironment

/**
 * Golden panel-frame sequences (#77): one file per scripted take scenario in
 * `app/src/test/golden/panel-frames`. A sequence that differs fails its test and puts every frame
 * of the golden and of the new sequence into the gallery as BEFORE/AFTER images.
 * `-PupdateGolden` on the Gradle command line rewrites the files instead; only after the owner decided.
 */
object GoldenFrames {
    const val RULE = "A differing frame or image stops the work: the owner decides from the BEFORE/AFTER pairs; " +
        "text alone is not accepted."
    private val folder = File("src/test/golden/panel-frames")
    private fun images(side: String, scenario: String) = File(PanelImages.GALLERY, "$side/sequences/$scenario")

    fun check(scenario: String, lines: List<String>) {
        val golden = File(folder, "$scenario.txt")
        if (System.getProperty("golden.update") == "true") {
            folder.mkdirs()
            golden.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        val expected = if (golden.isFile) golden.readLines() else emptyList()
        if (expected == lines) {
            images("before", scenario).deleteRecursively()
            images("after", scenario).deleteRecursively()
            return
        }
        val rendered = runCatching {
            PanelImages.write(images("before", scenario), numbered(expected))
            PanelImages.write(images("after", scenario), numbered(lines))
        }.exceptionOrNull()
        throw AssertionError(buildString {
            appendLine("panel-frame sequence of scenario $scenario differs from ${golden.path}")
            appendLine("--- golden")
            expected.forEach { appendLine(it) }
            appendLine("+++ this run")
            lines.forEach { appendLine(it) }
            if (rendered == null) appendLine("frames as images: ${images("before", scenario).absolutePath} and ${images("after", scenario).absolutePath}; " +
                "pairs and list: python3 tools/visible_change.py compare")
            else appendLine("frames could not be rendered: $rendered")
            append(RULE)
        })
    }

    /** The frames of a sequence, named by their place in it. */
    private fun numbered(lines: List<String>): List<Pair<String, PanelFrame>> {
        val resources = RuntimeEnvironment.getApplication().resources
        return lines.filter(FrameText::isFrame).mapIndexed { index, line -> "%02d".format(index + 1) to FrameText.frame(line, resources) }
    }
}
