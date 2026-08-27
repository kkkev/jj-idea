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
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class SquashIntoDialogPickSourcesModeTest {
    private val project = projectFixture()

    // All entries share the same repo so repoEntries filtering works correctly.
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    @Test
    fun `working copy is pre-selected and seeds description`() {
        val dest = createEntry("dest1", description = "destination")
        val wc = createEntry("wc1", description = "working copy", isWorkingCopy = true)
        val other = createEntry("other1", description = "other change")
        val dialog = dialog(dest, listOf(wc, other))

        dialog.descriptionText shouldBe "destination\n\nworking copy"
        disposeDialog(dialog)
    }

    @Test
    fun `description empty when no source selected and no working copy`() {
        val dest = createEntry("dest1", description = "destination")
        val src = createEntry("src1", description = "source desc")
        val dialog = dialog(dest, listOf(src))

        // No working copy — nothing is pre-selected, so description stays empty
        dialog.descriptionText shouldBe ""
        disposeDialog(dialog)
    }

    @Test
    fun `description updated when source is manually selected`() {
        val dest = createEntry("dest1", description = "dest desc")
        val src = createEntry("src1", description = "source desc")
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        dialog.descriptionText shouldBe "dest desc\n\nsource desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description uses only dest when source description is empty`() {
        val dest = createEntry("dest1", description = "dest desc")
        val src = createEntry("src1", description = "")
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        dialog.descriptionText shouldBe "dest desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description uses only source when destination description is empty`() {
        val dest = createEntry("dest1", description = "")
        val src = createEntry("src1", description = "source desc")
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        dialog.descriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description empty when both descriptions empty`() {
        val dest = createEntry("dest1", description = "")
        val src = createEntry("src1", description = "")
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        dialog.descriptionText shouldBe ""
        disposeDialog(dialog)
    }

    @Test
    fun `changing the source selection resets the preview to the placeholder`() {
        val dest = createEntry("dest1", description = "dest desc")
        val src1 = createEntry("src1", description = "src1 desc")
        val src2 = createEntry("src2", description = "src2 desc")
        val dialog = dialog(dest, listOf(src1, src2))

        dialog.selectPickerRowsForTest(0)
        val changes = listOf(change("src/Main.kt"))
        dialog.fileSelection.setChanges(changes)
        waitForRefresh(dialog.fileSelection)
        dialog.fileSelection.changesTree.selectFile(changes[0].filePath)
        val deadline1 = System.currentTimeMillis() + 5_000
        while (!dialog.preview.hasRequestForTest() && System.currentTimeMillis() < deadline1) {
            UIUtil.dispatchAllInvocationEvents()
        }
        dialog.preview.hasRequestForTest() shouldBe true

        // Changing the picker selection replaces the file tree wholesale (reloadChangesForSelection) —
        // the preview must not keep showing a diff for a file that may no longer be in the tree.
        dialog.selectPickerRowsForTest(1)

        dialog.preview.hasRequestForTest() shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `delete empty and move defaults to unchecked per settings`() {
        val dest = createEntry("dest1")
        val dialog = dialog(dest, emptyList())

        dialog.deleteEmptyAndMoveIsSelected shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `delete empty and move initializes from persisted settings`() {
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = true
        try {
            val dest = createEntry("dest1")
            val dialog = dialog(dest, emptyList())

            dialog.deleteEmptyAndMoveIsSelected shouldBe true
            disposeDialog(dialog)
        } finally {
            JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        }
    }

    @Test
    fun `doOKAction builds spec with selected sources and fixed destination`() {
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        val dest = createEntry("dest1", description = "dest desc")
        val src = createEntry("src1", description = "src desc")
        val changes = listOf(change("src/Main.kt"))
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        // Manually populate file selection (bypasses background change loading) so doValidate passes
        dialog.fileSelection.setChanges(changes)
        waitForRefresh(dialog.fileSelection)

        dialog.performOKForTest()

        val spec = dialog.result
        spec?.sources?.map { it.toString() } shouldBe listOf("src1")
        spec?.destination?.toString() shouldBe "dest1"
        disposeDialog(dialog)
    }

    @Test
    fun `doOKAction persists checkbox state`() {
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        val dest = createEntry("dest1")
        val src = createEntry("src1")
        val changes = listOf(change("src/Main.kt"))
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        dialog.fileSelection.setChanges(changes)
        waitForRefresh(dialog.fileSelection)
        dialog.deleteEmptyAndMoveIsSelected = true
        dialog.performOKForTest()

        dialog.result?.deleteEmptyAndMoveWorkingCopy shouldBe true
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove shouldBe true
        JujutsuSettings.getInstance(project.get()).state.squashDeleteEmptyAndMove = false
        disposeDialog(dialog)
    }

    @Test
    fun `file selection shows all changes after manual population`() {
        val dest = createEntry("dest1")
        val src = createEntry("src1")
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val dialog = dialog(dest, listOf(src))

        dialog.selectPickerRowsForTest(0)
        dialog.fileSelection.setChanges(changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges shouldHaveSize 2
        dialog.fileSelection.allIncluded shouldBe true
        disposeDialog(dialog)
    }

    private fun dialog(
        destination: LogEntry,
        candidates: List<LogEntry>
    ) = SquashIntoDialog(
        project.get(),
        destination.repo,
        SquashMode.PickSources(destination, candidates),
        emptyList()
    )

    private fun createEntry(
        id: String,
        description: String = "desc",
        isWorkingCopy: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = description,
        isWorkingCopy = isWorkingCopy
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

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
