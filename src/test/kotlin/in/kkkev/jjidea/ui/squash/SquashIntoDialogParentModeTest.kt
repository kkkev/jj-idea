package `in`.kkkev.jjidea.ui.squash

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
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class SquashIntoDialogParentModeTest {
    private val project = projectFixture()

    @Test
    fun `description pre-populated with both descriptions`() {
        val source = createEntry("src1", description = "source desc")
        val parent = createEntry("par1", description = "parent desc")
        val dialog = dialog(source, listOf(parent))

        dialog.descriptionText shouldBe "parent desc\n\nsource desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description pre-populated when source empty`() {
        val source = createEntry("src1", description = "")
        val parent = createEntry("par1", description = "parent desc")
        val dialog = dialog(source, listOf(parent))

        dialog.descriptionText shouldBe "parent desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description pre-populated when parent empty`() {
        val source = createEntry("src1", description = "source desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent))

        dialog.descriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description empty when both empty`() {
        val source = createEntry("src1", description = "")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent))

        dialog.descriptionText shouldBe ""
        disposeDialog(dialog)
    }

    @Test
    fun `description pre-populated with source when no candidates`() {
        val source = createEntry("src1", description = "source desc")
        val dialog = dialog(source, emptyList())

        dialog.descriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `multiple parent candidates pre-select first and seed description`() {
        val source = createEntry("src1", description = "source desc")
        val parent1 = createEntry("par1", description = "parent one")
        val parent2 = createEntry("par2", description = "parent two")
        val dialog = dialog(source, listOf(parent1, parent2))

        dialog.descriptionText shouldBe "parent one\n\nsource desc"
        disposeDialog(dialog)
    }

    @Test
    fun `file selection shows changes and all included by default`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)

        waitForRefresh(dialog.fileSelection)
        dialog.fileSelection.includedChanges shouldHaveSize 2
        dialog.fileSelection.allIncluded shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `unchecking file updates selection`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"), change("README.md"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)

        waitForRefresh(dialog.fileSelection)
        dialog.fileSelection.changesTree.setIncludedChanges(changes.take(1))

        dialog.fileSelection.includedChanges shouldHaveSize 1
        dialog.fileSelection.allIncluded shouldBe false
        disposeDialog(dialog)
    }

    private fun dialog(
        source: LogEntry,
        candidates: List<LogEntry>,
        changes: List<Change> = emptyList()
    ) = SquashIntoDialog(
        project.get(),
        source.repo,
        SquashMode.PickDestination(listOf(source), candidates),
        changes
    )

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

    private fun waitForRefresh(panel: FileSelectionPanel) {
        var refreshed = false
        panel.changesTree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }

    @Test
    fun `delete empty and move checkbox defaults to unchecked (per settings)`() {
        val source = createEntry("src1", description = "source desc")
        val parent = createEntry("par1", description = "parent desc")
        val dialog = dialog(source, listOf(parent))

        dialog.deleteEmptyAndMoveIsSelected shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `delete empty and move checkbox initializes from persisted settings`() {
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = true
        try {
            val source = createEntry("src1", description = "desc")
            val parent = createEntry("par1", description = "")
            val dialog = dialog(source, listOf(parent))

            dialog.deleteEmptyAndMoveIsSelected shouldBe true
            disposeDialog(dialog)
        } finally {
            JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        }
    }

    @Test
    fun `no files means no preview`() {
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), emptyList())

        waitForRefresh(dialog.fileSelection)
        dialog.preview.hasRequestForTest() shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `selecting a file shows its diff, header shows the file name`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)

        waitForRefresh(dialog.fileSelection)
        dialog.fileSelection.changesTree.selectFile(changes[1].filePath)
        awaitPreview(dialog)

        dialog.preview.headerTextForTest() shouldBe "Utils.kt"
        dialog.preview.hasRequestForTest() shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `unticking the selected file keeps the preview showing (now an unchanged diff)`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)

        waitForRefresh(dialog.fileSelection)
        dialog.fileSelection.changesTree.selectFile(changes[0].filePath)
        awaitPreview(dialog)

        dialog.fileSelection.changesTree.setIncludedChanges(changes.drop(1))

        dialog.preview.headerTextForTest() shouldBe "Main.kt"
        dialog.preview.hasRequestForTest() shouldBe true
        disposeDialog(dialog)
    }

    private fun awaitPreview(dialog: SquashIntoDialog) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!dialog.preview.hasRequestForTest() && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    @Test
    fun `doOKAction persists checkbox state and sets spec field`() {
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        val changes = listOf(change("src/Main.kt"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)
        waitForRefresh(dialog.fileSelection)

        dialog.deleteEmptyAndMoveIsSelected = true
        dialog.performOKForTest()

        dialog.result?.deleteEmptyAndMoveWorkingCopy shouldBe true
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove shouldBe true
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        disposeDialog(dialog)
    }

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    // ---- Hunk picking (jj-idea-4q7m) ----

    @Test
    fun `pickHunksButton is wired into the shared preview panel's footer`() {
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent))

        dialog.pickHunksButton.parent shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `pickHunksButton is visible for a single source`() {
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent))

        dialog.pickHunksButton.isVisible shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `pickHunksButton disabled until a changed file is previewed`() {
        val changes = listOf(change("src/Main.kt"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)

        dialog.pickHunksButton.isEnabled shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `override injected for test produces non-null hunkSelection`() {
        val changes = listOf(change("src/Main.kt"))
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)
        waitForRefresh(dialog.fileSelection)

        dialog.setDestinationOverrideForTest(fp, "partial content\n")

        dialog.performOKForTest()
        dialog.result shouldNotBe null
        dialog.result!!.hunkSelection shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `ok action produces null hunkSelection when no partial files`() {
        val changes = listOf(change("src/Main.kt"))
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)
        waitForRefresh(dialog.fileSelection)

        dialog.performOKForTest()
        dialog.result?.hunkSelection shouldBe null
        disposeDialog(dialog)
    }

    @Test
    fun `setDestinationOverrideForTest reflects in partialChanges on tree`() {
        val mainChange = change("src/Main.kt")
        val changes = listOf(mainChange, change("src/Utils.kt"))
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)
        waitForRefresh(dialog.fileSelection)

        dialog.setDestinationOverrideForTest(fp, "partial\n")

        dialog.fileSelection.changesTree.partialChanges shouldBe setOf(mainChange)
        disposeDialog(dialog)
    }

    @Test
    fun `clearing override removes from partialChanges`() {
        val changes = listOf(change("src/Main.kt"))
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)
        waitForRefresh(dialog.fileSelection)

        dialog.setDestinationOverrideForTest(fp, "partial\n")
        dialog.setDestinationOverrideForTest(fp, null)

        dialog.fileSelection.changesTree.partialChanges shouldBe emptySet()
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent for a genuine partial does not force-untick a ticked file`() {
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), listOf(change("src/Main.kt")))
        waitForRefresh(dialog.fileSelection)
        // Main.kt starts ticked by default (all-included). A genuine partial should leave that
        // tick alone - the half-checked render, not the tick, communicates "partial".
        dialog.applyPickedContent(fp, "partial\n", before = "before\n", after = "after\n")

        dialog.fileSelection.includedChanges.map { it.filePath } shouldBe listOf(fp)
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent for a fully-picked (after) result ticks the file`() {
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), listOf(change("src/Main.kt")))
        waitForRefresh(dialog.fileSelection)
        dialog.fileSelection.changesTree.setIncludedChanges(emptyList<Change>())

        dialog.applyPickedContent(fp, "after\n", before = "before\n", after = "after\n")

        dialog.fileSelection.includedChanges.map { it.filePath } shouldBe listOf(fp)
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent for an empty-picked (before) result unticks the file`() {
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), listOf(change("src/Main.kt")))
        waitForRefresh(dialog.fileSelection)

        dialog.applyPickedContent(fp, "before\n", before = "before\n", after = "after\n")

        dialog.fileSelection.includedChanges shouldBe emptyList()
        disposeDialog(dialog)
    }

    @Test
    fun `partial squash disables the delete-empty-and-move checkbox at OK time`() {
        val changes = listOf(change("src/Main.kt"))
        val fp = LocalFilePath("src/Main.kt", false)
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = dialog(source, listOf(parent), changes)
        waitForRefresh(dialog.fileSelection)

        dialog.deleteEmptyAndMoveIsSelected = true // still selected+enabled here (allIncluded)
        dialog.setDestinationOverrideForTest(fp, "partial content\n")

        // isSelected is untouched by the disable, but doOKAction gates on isEnabled too - a
        // partial squash can't empty the source, so the effective result must be false.
        dialog.performOKForTest()
        dialog.result?.deleteEmptyAndMoveWorkingCopy shouldBe false
        disposeDialog(dialog)
    }
}
