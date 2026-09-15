package `in`.kkkev.jjidea.ui.restore

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class RestoreDialogTest {
    private val project = projectFixture()

    @Test
    fun `preselected paths start ticked, everything else unticked`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val dialog = RestoreDialog(project.get(), "@-", listOf(main, utils), setOf(main.filePath))
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges.toList() shouldBe listOf(main)
        disposeDialog(dialog)
    }

    @Test
    fun `no preselection leaves everything unticked`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val dialog = RestoreDialog(project.get(), "@-", changes, emptySet())
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges.size shouldBe 0
        disposeDialog(dialog)
    }

    @Test
    fun `rename preselects when either the source or target path is selected`() {
        val rename = renameChange("src/Old.kt", "src/New.kt")
        val other = change("src/Other.kt")

        val bySource = RestoreDialog(project.get(), "@-", listOf(rename, other), setOf(path("src/Old.kt")))
        waitForRefresh(bySource.fileSelection)
        bySource.fileSelection.includedChanges.toList() shouldBe listOf(rename)
        disposeDialog(bySource)

        val byTarget = RestoreDialog(project.get(), "@-", listOf(rename, other), setOf(path("src/New.kt")))
        waitForRefresh(byTarget.fileSelection)
        byTarget.fileSelection.includedChanges.toList() shouldBe listOf(rename)
        disposeDialog(byTarget)
    }

    @Test
    fun `result reflects ticking and unticking`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val dialog = RestoreDialog(project.get(), "@-", listOf(main, utils), setOf(main.filePath))
        waitForRefresh(dialog.fileSelection)

        // Untick the preselected file, tick the other one instead.
        dialog.fileSelection.changesTree.setIncludedChanges(listOf(utils))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        dialog.result shouldBe listOf(path("src/Utils.kt"))
        disposeDialog(dialog)
    }

    @Test
    fun `result for a rename contains both the old and new path`() {
        val rename = renameChange("src/Old.kt", "src/New.kt")
        val dialog = RestoreDialog(project.get(), "@-", listOf(rename), emptySet())
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(listOf(rename))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        dialog.result!!.shouldContainExactlyInAnyOrder(path("src/Old.kt"), path("src/New.kt"))
        disposeDialog(dialog)
    }

    @Test
    fun `validation fails when nothing is ticked`() {
        val dialog = RestoreDialog(project.get(), "@-", listOf(change("src/Main.kt")), emptySet())
        waitForRefresh(dialog.fileSelection)

        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes once at least one file is ticked`() {
        val main = change("src/Main.kt")
        val dialog = RestoreDialog(project.get(), "@-", listOf(main), setOf(main.filePath))
        waitForRefresh(dialog.fileSelection)

        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    private fun path(relativePath: String): FilePath = LocalFilePath(relativePath, false)

    private fun change(relativePath: String): Change {
        val filePath = path(relativePath)
        return Change(null, SimpleContentRevision("", filePath, "1"))
    }

    private fun renameChange(beforePath: String, afterPath: String) =
        Change(SimpleContentRevision("", path(beforePath), "1"), SimpleContentRevision("", path(afterPath), "2"))

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
