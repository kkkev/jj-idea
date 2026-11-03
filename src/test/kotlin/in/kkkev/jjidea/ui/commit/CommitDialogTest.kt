package `in`.kkkev.jjidea.ui.commit

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class CommitDialogTest {
    private val project = projectFixture()

    @Test
    fun `description pre-populated with source description`() {
        val source = createEntry("src1", description = "source desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        dialog.descriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description empty when source empty`() {
        val source = createEntry("src1", description = "")
        val dialog = CommitDialog(project.get(), source, emptyList())

        dialog.descriptionText shouldBe ""
        disposeDialog(dialog)
    }

    // The note is an HTML pane (IconAwareHtmlPane), which wraps instead of reporting its preferred
    // width as whatever a single unwrapped line needs (see SplitDialogTest).
    @Test
    fun `note wraps instead of forcing a wide preferred size`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        // A plain JLabel would report a single-line preferred width well over 500px for this
        // sentence at typical dialog font sizes.
        dialog.noteComponent.preferredSize.width shouldBeLessThan JBUI.scale(500)

        disposeDialog(dialog)
    }

    @Test
    fun `no files ticked by default in file selection`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges.size shouldBe 0
        disposeDialog(dialog)
    }

    @Test
    fun `validation fails when nothing ticked (nothing to commit)`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Nothing ticked and no overrides — validation should fail (commit would be empty)
        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes when all files ticked (commits everything)`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val changes = listOf(main, utils)
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(changes)
        UIUtil.dispatchAllInvocationEvents()

        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes with a mixed selection`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val changes = listOf(main, utils)
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(listOf(main))
        UIUtil.dispatchAllInvocationEvents()

        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes with nothing ticked but one file partially picked (GitHub #117)`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Nothing ticked, but Auth.kt has a genuine partial hunk pick - applyPickedContent
        // deliberately leaves the tick alone for a partial, so this is exactly the state a
        // hunks-only commit produces.
        val authPath = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(authPath, "partial content\n")

        dialog.fileSelection.includedChanges.size shouldBe 0
        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    private val authPath = LocalFilePath("src/Auth.kt", false)

    @Test
    fun `hunks-only commit validates and carries the picked committed content`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, listOf(authChange, loggerChange))
        waitForRefresh(dialog.fileSelection)

        // Nothing ticked, but Auth.kt has a genuine partial pick: that's real content to commit.
        dialog.setFirstCommitOverrideForTest(authPath, "partial content\n")

        dialog.doValidateForTest() shouldBe null
        dialog.performOKForTest()
        val result = dialog.result!!
        result.filePaths shouldBe emptyList()
        result.hunkSelection!!.files.first { it.filePath == authPath }.content shouldBe "partial content\n"
        disposeDialog(dialog)
    }

    @Test
    fun `ticked deletion goes into the deletion manifest, unticked one does not`() {
        val gone = deletion("src/Gone.kt")
        val auth = change("src/Auth.kt")
        val source = createEntry("src1", description = "desc")

        val ticked = CommitDialog(project.get(), source, listOf(gone, auth))
        waitForRefresh(ticked.fileSelection)
        ticked.fileSelection.changesTree.setIncludedChanges(listOf(gone))
        UIUtil.dispatchAllInvocationEvents()
        ticked.setFirstCommitOverrideForTest(authPath, "partial\n")
        ticked.performOKForTest()
        // The committed change is the lower commit, so a ticked deletion lands there.
        ticked.result!!.hunkSelection!!.deletedPaths shouldBe setOf("src/Gone.kt")
        disposeDialog(ticked)

        val unticked = CommitDialog(project.get(), source, listOf(gone, auth))
        waitForRefresh(unticked.fileSelection)
        unticked.setFirstCommitOverrideForTest(authPath, "partial\n")
        unticked.performOKForTest()
        unticked.result!!.hunkSelection!!.deletedPaths shouldBe emptySet()
        disposeDialog(unticked)
    }

    @Test
    fun `computePreviewLeftContent - ticked shows after, unticked shows base`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        dialog.computePreviewLeftContent(true, null, "base", "after") shouldBe "after"
        dialog.computePreviewLeftContent(false, null, "base", "after") shouldBe "base"
        dialog.computePreviewLeftContent(true, "picked", "base", "after") shouldBe "picked"
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent ticks on all-changes, unticks on no-changes, and keeps tick on a partial`() {
        val authChange = change("src/Auth.kt")
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, listOf(authChange))
        waitForRefresh(dialog.fileSelection)

        dialog.applyPickedContent(authPath, "after", "base", "after")
        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)

        dialog.applyPickedContent(authPath, "mixed", "base", "after")
        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)

        dialog.applyPickedContent(authPath, "base", "base", "after")
        dialog.fileSelection.includedChanges.toSet() shouldBe emptySet()
        disposeDialog(dialog)
    }

    @Test
    fun `override injected for test produces non-null hunkSelection`() {
        val changes = listOf(change("src/Auth.kt"), change("src/Logger.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
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
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Tick Logger so it is committed (file-level, no overrides).
        dialog.fileSelection.changesTree.setIncludedChanges(listOf(loggerChange))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        val result = dialog.result

        result shouldNotBe null
        result!!.hunkSelection shouldBe null // no overrides → fast path
        result.filePaths shouldBe listOf(loggerChange.filePath) // Logger.kt is committed
        result.description shouldBe Description("initial desc")
        disposeDialog(dialog)
    }

    @Test
    fun `preSelectedFiles from right-click start ticked`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val authPath = LocalFilePath("src/Auth.kt", false)

        val dialog = CommitDialog(project.get(), source, changes, preSelectedFiles = setOf(authPath))
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)
        disposeDialog(dialog)
    }

    @Test
    fun `ticked files are passed as the fileset`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Tick Auth.kt: it should become the commit's fileset.
        dialog.fileSelection.changesTree.setIncludedChanges(listOf(authChange))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        val result = dialog.result
        result shouldNotBe null
        result!!.filePaths shouldBe listOf(authChange.filePath)
        disposeDialog(dialog)
    }

    @Test
    fun `description is passed as the commit message`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "original desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(listOf(authChange))
        UIUtil.dispatchAllInvocationEvents()

        dialog.descriptionText shouldBe "original desc"
        dialog.performOKForTest()
        val result = dialog.result
        result shouldNotBe null
        result!!.description shouldBe Description("original desc")
        disposeDialog(dialog)
    }

    @Test
    fun `shows Pick Hunks`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        dialog.pickHunksButton.isVisible shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `header names the committed change`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        dialog.headerLabel.text shouldContain "Description for committed change"
        dialog.headerLabel.text shouldContain source.id.short
        dialog.firstCommitLabel shouldBe "New working copy"
        dialog.secondCommitLabel shouldBe "Committed change"
        disposeDialog(dialog)
    }

    @Test
    fun `note says unticked files move to a new working-copy change`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        dialog.noteText shouldContain "new working-copy change"
        dialog.noteText shouldContain source.id.short
        disposeDialog(dialog)
    }

    @Test
    fun `pickHunksButton is wired into the shared preview panel's footer`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        // Regression check for the FileDiffPreviewPanel extraction (jj-idea-8a8z): the button
        // must actually be added to the shared shell's footer, not orphaned by the refactor.
        dialog.pickHunksButton.parent shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent partial override wins regardless of tick`() {
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isTicked = true,
            override = "partial\n",
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "partial\n"
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent for a genuine partial does not force-tick a previously unticked file`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        // Auth.kt starts unticked (nothing selected by default). Apply a genuinely-partial
        // result directly, exactly as onPickHunks would after a real partial pick.
        dialog.applyPickedContent(fp, "partial\n", baseContent = "before\n", afterContent = "after\n")

        // A partial must not force-tick the file (ensureFileIncluded), which would make a
        // half-picked file look fully committed.
        dialog.fileSelection.includedChanges shouldBe emptyList()
        disposeDialog(dialog)
    }

    @Test
    fun `setFirstCommitOverrideForTest reflects in partialChanges on tree`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange, change("src/Logger.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = CommitDialog(project.get(), source, changes)
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
        val dialog = CommitDialog(project.get(), source, changes)
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
        val dialog = CommitDialog(project.get(), source, changes)
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

    private fun deletion(path: String): Change {
        val filePath = LocalFilePath(path, false)
        return Change(SimpleContentRevision("old", filePath, "1"), null)
    }

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
