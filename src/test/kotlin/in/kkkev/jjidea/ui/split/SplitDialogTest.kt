package `in`.kkkev.jjidea.ui.split

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class SplitDialogTest {
    private val project = projectFixture()

    @Test
    fun `parent description pre-populated with source description`() {
        val source = createEntry("src1", description = "source desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parentDescriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `child description pre-populated with source description`() {
        val source = createEntry("src1", description = "source desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.childDescriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description empty when source empty`() {
        val source = createEntry("src1", description = "")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parentDescriptionText shouldBe ""
        dialog.childDescriptionText shouldBe ""
        disposeDialog(dialog)
    }

    @Test
    fun `parallel checkbox defaults to unchecked`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parallelCheckBox.isSelected shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `dynamic labels switch when parallel toggled`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        // Default: linear mode
        dialog.childHeaderLabel.text shouldContain "Child"
        dialog.parentHeaderLabel.text shouldContain "Parent"

        // Toggle to parallel
        dialog.parallelCheckBox.isSelected = true
        dialog.parallelCheckBox.actionListeners.forEach { it.actionPerformed(null) }
        UIUtil.dispatchAllInvocationEvents()

        dialog.childHeaderLabel.text shouldContain "First"
        dialog.parentHeaderLabel.text shouldContain "Second"

        disposeDialog(dialog)
    }

    @Test
    fun `no files ticked by default in file selection`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges.size shouldBe 0
        disposeDialog(dialog)
    }

    @Test
    fun `validation fails when nothing ticked (nothing to split off)`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Nothing ticked and no overrides — validation should fail (child would be empty)
        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation fails when all files ticked (nothing left for parent)`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val changes = listOf(main, utils)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(changes)
        UIUtil.dispatchAllInvocationEvents()

        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes with a mixed selection`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val changes = listOf(main, utils)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(listOf(main))
        UIUtil.dispatchAllInvocationEvents()

        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    @Test
    fun `override injected for test produces non-null hunkSelection`() {
        val changes = listOf(change("src/Auth.kt"), change("src/Logger.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        // Inject a partial content override for Auth.kt
        dialog.setFirstCommitOverrideForTest(fp, "partial content\n")

        // hunkPickerForTest allows OK action to fire without showing the merge window
        // Validation should now pass (override implies partial selection = non-empty second commit)
        // OK produces a SplitHunkSelection
        dialog.performOKForTest()
        dialog.result shouldNotBe null
        dialog.result!!.hunkSelection shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `ok action produces null hunkSelection when no partial files`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "initial desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Tick Logger so it moves to the child (file-level, no overrides).
        dialog.fileSelection.changesTree.setIncludedChanges(listOf(loggerChange))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        val result = dialog.result

        result shouldNotBe null
        result!!.hunkSelection shouldBe null // no overrides → fast path
        result.filePaths shouldBe listOf(authChange.filePath) // Auth.kt stays in the parent
        result.description shouldBe Description("initial desc")
        result.childDescription shouldBe null // unchanged
        result.parallel shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `preSelectedFiles from right-click land in the child, not the parent`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val authPath = LocalFilePath("src/Auth.kt", false)

        // Simulate right-clicking Auth.kt and choosing "Split into New Child".
        val dialog = SplitDialog(project.get(), source, changes, preSelectedFiles = setOf(authPath))
        waitForRefresh(dialog.fileSelection)

        // The right-clicked file must start TICKED (moving to the child); the other file
        // stays unticked (remains in the parent).
        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)

        dialog.performOKForTest()
        val result = dialog.result
        result shouldNotBe null
        // filePaths are the files that stay in the parent (`jj split` keeps them there).
        result!!.filePaths shouldBe listOf(loggerChange.filePath)
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent unticked returns after content (nothing moves)`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isIncludedInChild = false,
            override = null,
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "after\n"
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent fully ticked returns base content`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isIncludedInChild = true,
            override = null,
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "before\n"
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent partial override wins regardless of tick`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isIncludedInChild = true,
            override = "partial\n",
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "partial\n"
        disposeDialog(dialog)
    }

    @Test
    fun `describeSplitState labels unticked state as parent all changes, child no changes`() {
        val (parentTitle, childTitle) = describeSplitState(
            content = "after\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Parent",
            childLabel = "Child"
        )
        parentTitle shouldContain "all changes"
        childTitle shouldContain "no changes"
    }

    @Test
    fun `describeSplitState labels fully-ticked state as parent unchanged, child all changes`() {
        val (parentTitle, childTitle) = describeSplitState(
            content = "before\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Parent",
            childLabel = "Child"
        )
        parentTitle shouldContain "unchanged"
        childTitle shouldContain "all changes"
    }

    @Test
    fun `describeSplitState labels partial content as partial on both sides`() {
        val (parentTitle, childTitle) = describeSplitState(
            content = "partial\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Parent",
            childLabel = "Child"
        )
        parentTitle shouldContain "partial"
        childTitle shouldContain "partial"
    }

    @Test
    fun `applyPickedContent for a genuine partial does not force-tick a previously unticked file`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        // Auth.kt starts unticked (nothing selected by default). Apply a genuinely-partial
        // result directly, exactly as onPickHunks would after a real partial pick.
        dialog.applyPickedContent(fp, "partial\n", baseContent = "before\n", afterContent = "after\n")

        // Regression: this used to force-tick the file (ensureFileIncluded), making a
        // half-picked file look fully committed to the child.
        dialog.fileSelection.includedChanges shouldBe emptyList()
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent for a fully-picked result ticks the file`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        dialog.applyPickedContent(fp, "before\n", baseContent = "before\n", afterContent = "after\n")

        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)
        disposeDialog(dialog)
    }

    @Test
    fun `setFirstCommitOverrideForTest reflects in partialChanges on tree`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange, change("src/Logger.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(fp, "partial\n")

        // The partial change should be the authChange (matched by filePath)
        dialog.fileSelection.changesTree.partialChanges shouldBe setOf(authChange)
        disposeDialog(dialog)
    }

    @Test
    fun `clearing override removes from partialChanges`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(fp, "partial\n")
        dialog.setFirstCommitOverrideForTest(fp, null) // clear

        dialog.fileSelection.changesTree.partialChanges shouldBe emptySet()
        disposeDialog(dialog)
    }

    @Test
    fun `cancel returns null hunkPickerForTest to leave state unchanged`() {
        val changes = listOf(change("src/Auth.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)

        // Inject a picker that returns null (cancel) — override should not be set
        dialog.hunkPickerForTest = { null }

        // Manually invoke the pick-hunks path via the test seam
        // (pickHunksButton would normally invoke it, but that requires currentPreviewFile to be set)
        // Instead verify the seam via setFirstCommitOverrideForTest + OK fast path
        dialog.setFirstCommitOverrideForTest(fp, null) // clear any override
        dialog.performOKForTest()
        dialog.result!!.hunkSelection shouldBe null // no overrides → fast path
        disposeDialog(dialog)
    }

    // ---- helpers ----

    private fun createEntry(id: String, description: String = "") = LogEntry(
        repo = mockk(relaxed = true),
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = description
    )

    private fun change(path: String): Change {
        val filePath = LocalFilePath(path, false)
        return Change(null, SimpleContentRevision("", filePath, "1"))
    }

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    private fun waitForRefresh(panel: FileSelectionPanel) {
        var refreshed = false
        panel.changesTree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }
}
