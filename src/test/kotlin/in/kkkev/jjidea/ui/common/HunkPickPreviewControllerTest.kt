package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Platform tests for the inline-checkbox wiring in [HunkPickPreviewController] (jj-idea-s5yx):
 * polarity of the ticks, state label, in-place refresh, and the scale rule that one toggle costs
 * one line diff and never rebuilds the viewer.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class HunkPickPreviewControllerTest {
    private val project = projectFixture()
    private val disposable: Disposable = Disposer.newDisposable()

    private val before = "a\nb\nc\nd\ne\n"
    private val after = "a\nB\nc\nd\nE\n"
    private val change = Change(
        SimpleContentRevision(before, LocalFilePath("/repo/f.txt", false), "1"),
        SimpleContentRevision(after, LocalFilePath("/repo/f.txt", false), "2")
    )

    private var resolved = before // what resolveContent returns, i.e. the dialog's override/tick state
    private var changedContent: String? = null
    private val diffCalls = AtomicInteger()

    @AfterEach
    fun tearDown() = Disposer.dispose(disposable)

    private fun controller(invert: Boolean) = HunkPickPreviewController(
        project = project.get(),
        disposable = disposable,
        loadContents = { FileContents(before, after, PlainTextFileType.INSTANCE) },
        resolveContent = { _, _, _ -> resolved },
        isIncluded = { false },
        movesToLabel = { "new commit" },
        invertApplied = invert,
        onHunksChanged = { _, content, _ -> changedContent = content.also { resolved = it } },
        differ = LineDiffer { a, b ->
            diffCalls.incrementAndGet()
            platformLineDiffer.diff(a, b)
        }
    ).also {
        it.showFor(change)
        repeat(200) { _ ->
            if (it.sessionForTest() == null) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
        }
    }

    @Test
    fun `squash-style polarity - resolved content is the moved side`() {
        resolved = after // everything applied → both hunks tick
        val c = controller(invert = false)
        c.sessionForTest()!!.checked shouldBe setOf(0, 1)
        c.preview.stateTextForTest() shouldContain "All hunks"
    }

    @Test
    fun `split-style polarity - resolved content is the stays side so nothing applied means all move`() {
        resolved = before
        val c = controller(invert = true)
        c.sessionForTest()!!.checked shouldBe setOf(0, 1)
    }

    @Test
    fun `clicking a box reports content in the resolved polarity and refreshes the label in place`() {
        resolved = before
        val c = controller(invert = false)
        val session = c.sessionForTest()!!
        c.preview.stateTextForTest() shouldContain "No hunks"

        session.toggle(1)

        changedContent shouldBe "a\nb\nc\nd\nE\n"
        c.sessionForTest() shouldBe session // same session: viewer not rebuilt
        c.preview.stateTextForTest() shouldContain "1 of 2"
    }

    @Test
    fun `a non-hunk-subset override shows no checkboxes and the custom label`() {
        resolved = "a\nb\nc\nd\nX\n"
        val c = controller(invert = false)
        // No session is created for custom content; the state label says so.
        repeat(50) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        c.sessionForTest() shouldBe null
        c.preview.stateTextForTest() shouldContain "Custom pick"
    }

    @Test
    fun `a toggle costs no extra line diff (the composed content is remembered)`() {
        resolved = before
        val c = controller(invert = false)
        val baseline = diffCalls.get()

        c.sessionForTest()!!.toggle(0)

        (diffCalls.get() - baseline) shouldBe 0
    }
}
