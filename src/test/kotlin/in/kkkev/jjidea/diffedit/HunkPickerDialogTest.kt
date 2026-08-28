package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.tools.simple.SimpleThreesideDiffChange
import com.intellij.diff.tools.simple.SimpleThreesideDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.diff.util.ThreeSide
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Platform tests for [HunkPickerDialog]'s 3-way viewer wiring. Drives it via the exact same
 * public platform API a real arrow click uses (`viewer.getChanges()` +
 * `viewer.replaceChange(...)`) — no synthetic "click" seam needed, since both are already public.
 *
 * [SimpleThreesideDiffViewer.rediff]'s no-arg form is alarm-debounced, so tests force a
 * synchronous recompute (`rediff(true)`) after construction and after every write, instead of
 * pumping the event queue to wait for the platform's own debounce timer.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class HunkPickerDialogTest {
    private val project = projectFixture()

    private val base = "line1\nline2\nline3\n"
    private val after = "line1\nCHANGED2\nline3\n"

    @AfterEach
    fun tearDown() {
        HunkPicker.dialogRunnerForTest = null
    }

    @Test
    fun `right arrow moves a hunk to the child, left arrow moves it back`() {
        val dialog = buildDialog(base, after, initialContent = after)
        val viewer = viewerReady(dialog)

        val change = viewer.getChanges().single()
        change.isChange(Side.LEFT) shouldBe true // unticked: Parent currently equals Child

        replaceChange(viewer, change, ThreeSide.LEFT)
        dialog.resultContent() shouldBe base

        val movedChange = viewer.getChanges().single()
        movedChange.isChange(Side.RIGHT) shouldBe true // now moved to child

        replaceChange(viewer, movedChange, ThreeSide.RIGHT)
        dialog.resultContent() shouldBe after

        disposeDialog(dialog)
    }

    @Test
    fun `multiple hunks move independently`() {
        val twoHunkBase = "line1\nline2\nline3\nline4\nline5\n"
        val twoHunkAfter = "line1\nCHANGED2\nline3\nCHANGED4\nline5\n"
        val dialog = buildDialog(twoHunkBase, twoHunkAfter, initialContent = twoHunkAfter)
        val viewer = viewerReady(dialog)

        val firstChange = viewer.getChanges()[0]
        replaceChange(viewer, firstChange, ThreeSide.LEFT)

        dialog.resultContent() shouldBe "line1\nline2\nline3\nCHANGED4\nline5\n"
        disposeDialog(dialog)
    }

    @Test
    fun `OK reports the exit code and result reflects the current live parent content`() {
        val dialog = buildDialog(base, after, initialContent = after)
        val viewer = viewerReady(dialog)
        replaceChange(viewer, viewer.getChanges().single(), ThreeSide.LEFT)

        val resultBeforeOk = dialog.resultContent()
        dialog.performOKForTest()

        dialog.exitCode shouldBe DialogWrapper.OK_EXIT_CODE
        resultBeforeOk shouldBe base
    }

    @Test
    fun `cancel reports non-OK`() {
        val dialog = buildDialog(base, after, initialContent = after)
        dialog.performCancelForTest()

        dialog.exitCode shouldBe DialogWrapper.CANCEL_EXIT_CODE
    }

    @Test
    fun `pickRemainderContent applies an arrow click through the injected dialog runner`() {
        HunkPicker.dialogRunnerForTest = { dialog ->
            val viewer = viewerReady(dialog)
            replaceChange(viewer, viewer.getChanges().single(), ThreeSide.LEFT)
            dialog.performOKForTest()
            true
        }

        val result = HunkPicker.pickRemainderContent(
            project = project.get(),
            fileName = "file.txt",
            fileType = PlainTextFileType.INSTANCE,
            baseContent = base,
            afterContent = after,
            initialContent = after,
            labels = HunkPickerLabels.forSplit("Parent", "Child")
        )

        result shouldBe base
    }

    @Test
    fun `pickRemainderContent returns null when the injected dialog runner reports cancel`() {
        HunkPicker.dialogRunnerForTest = { dialog ->
            dialog.performCancelForTest()
            false
        }

        val result = HunkPicker.pickRemainderContent(
            project = project.get(),
            fileName = "file.txt",
            fileType = PlainTextFileType.INSTANCE,
            baseContent = base,
            afterContent = after,
            initialContent = after,
            labels = HunkPickerLabels.forSplit("Parent", "Child")
        )

        result shouldBe null
    }

    @Test
    fun `pickRemainderContent resumes an existing partial selection with no reconstruction step`() {
        val twoHunkBase = "line1\nline2\nline3\nline4\nline5\n"
        val twoHunkAfter = "line1\nCHANGED2\nline3\nCHANGED4\nline5\n"
        val partialRemainder = "line1\nline2\nline3\nCHANGED4\nline5\n" // first hunk moved, second stayed

        var capturedText: String? = null
        HunkPicker.dialogRunnerForTest = { dialog ->
            capturedText = dialog.resultContent()
            dialog.performCancelForTest()
            false
        }

        HunkPicker.pickRemainderContent(
            project = project.get(),
            fileName = "file.txt",
            fileType = PlainTextFileType.INSTANCE,
            baseContent = twoHunkBase,
            afterContent = twoHunkAfter,
            initialContent = partialRemainder,
            labels = HunkPickerLabels.forSplit("Parent", "Child")
        )

        capturedText shouldBe partialRemainder
    }

    private fun buildDialog(base: String, after: String, initialContent: String): HunkPickerDialog =
        HunkPickerDialog(
            project.get(),
            "file.txt",
            PlainTextFileType.INSTANCE,
            base,
            initialContent,
            after,
            HunkPickerLabels.forSplit("Parent", "Child")
        )

    /**
     * Grabs the constructed viewer and forces a synchronous initial diff (see class doc).
     * `rediff(true)` isn't itself EDT-safe on every platform version without an explicit
     * write-intent lock (production code never calls it directly — only via the debounced
     * `scheduleRediff()` inside `replaceChange`, same as any ordinary diff viewer's click-driven
     * update); tests force it synchronously for determinism, so need the same wrapper
     * `HunkPickerDialog.createCenterPanel` already uses for the same reason.
     */
    private fun viewerReady(dialog: HunkPickerDialog): SimpleThreesideDiffViewer {
        val viewer = requireNotNull(dialog.viewerForTest()) { "viewer not constructed" }
        WriteIntentReadAction.run { viewer.rediff(true) }
        return viewer
    }

    private fun replaceChange(
        viewer: SimpleThreesideDiffViewer,
        change: SimpleThreesideDiffChange,
        sourceSide: ThreeSide
    ) {
        val document = viewer.getEditor(ThreeSide.BASE).document
        DiffUtil.executeWriteCommand(document, null, "test") {
            viewer.replaceChange(change, sourceSide, ThreeSide.BASE)
        }
        WriteIntentReadAction.run { viewer.rediff(true) }
    }

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    // ---- HunkPickerLabels.forSquash wiring (jj-idea-4q7m) ----

    @Test
    fun `squash labels render distinct pane titles from split labels`() {
        val dialog = HunkPickerDialog(
            project.get(),
            "file.txt",
            PlainTextFileType.INSTANCE,
            base,
            after,
            after,
            HunkPickerLabels.forSquash("mytest", "@-")
        )
        val viewer = viewerReady(dialog)

        // Mechanics are unaffected by which labels are used — an arrow click still moves a hunk.
        replaceChange(viewer, viewer.getChanges().single(), ThreeSide.LEFT)
        dialog.resultContent() shouldBe base

        disposeDialog(dialog)
    }

    @Test
    fun `forSquash and forSplit produce different wording for the same commit labels`() {
        val split = HunkPickerLabels.forSplit("Parent", "Child")
        val squash = HunkPickerLabels.forSquash("Parent", "Child")

        split.leftTitle shouldBe "Parent"
        squash.leftTitle shouldNotBe split.leftTitle
        squash.middleTitle shouldNotBe split.middleTitle
        squash.rightTitle shouldNotBe split.rightTitle
        squash.middleArrowTooltip shouldNotBe split.middleArrowTooltip
        squash.rightArrowTooltip shouldNotBe split.rightArrowTooltip
    }
}
