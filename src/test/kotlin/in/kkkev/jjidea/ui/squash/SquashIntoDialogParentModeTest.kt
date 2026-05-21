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
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
        listOf(source),
        changes,
        candidateDestinations = candidates
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

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
