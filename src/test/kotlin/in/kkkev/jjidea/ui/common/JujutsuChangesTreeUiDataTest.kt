package `in`.kkkev.jjidea.ui.common

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE
import com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE_ARRAY
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Covers [JujutsuChangesTree.showsLocalFiles] gating [VIRTUAL_FILE]/[VIRTUAL_FILE_ARRAY] in
 * [JujutsuChangesTree.uiDataSnapshot], mirroring the platform's own
 * [com.intellij.openapi.vcs.changes.ui.ChangesListView] (used by IntelliJ's built-in Git/Commit
 * changes view) so that globally-bound file actions such as Reformat Code and Optimize Imports
 * can act on the Working Copy tool window's selection - but not on a historical selection
 * (commit details pane on a non-`@` commit, compare-changes panel, file-selection dialog), where
 * a change's local file isn't the revision on screen.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuChangesTreeUiDataTest {
    private val project = projectFixture()

    @Test
    fun `showsLocalFiles true exposes the selection as virtual files, skipping unresolvable changes`() {
        val existingPath = Files.createTempFile("jj-idea-test", ".kt")
        val fallbackDisposable = Disposer.newDisposable()

        try {
            // HeadlessDataManager (used under @TestApplication) otherwise ignores the passed
            // component and only serves a manually-registered test provider; this restores real,
            // component-hierarchy-based DataContext resolution so uiDataSnapshot is actually invoked.
            HeadlessDataManager.fallbackToProductionDataManager(fallbackDisposable)

            val existingVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(existingPath)!!
            val existingChange = Change(null, contentRevision(existingPath, "1"))
            val deletedChange = Change(contentRevision("/does/not/exist", "0"), null)

            val tree = JujutsuChangesTree(project.get()).apply { showsLocalFiles = { true } }
            tree.setChangesToDisplay(listOf(existingChange, deletedChange))
            waitForRefresh(tree)
            tree.selectionRows = IntArray(tree.rowCount) { it }

            val dataContext = DataManager.getInstance().getDataContext(tree)

            VIRTUAL_FILE_ARRAY.getData(dataContext)?.toList() shouldBe listOf(existingVirtualFile)
            VIRTUAL_FILE.getData(dataContext) shouldBe existingVirtualFile
        } finally {
            Disposer.dispose(fallbackDisposable)
            Files.deleteIfExists(existingPath)
        }
    }

    @Test
    fun `showsLocalFiles false (the default) never exposes virtual files, even with a resolvable selection`() {
        val existingPath = Files.createTempFile("jj-idea-test", ".kt")
        val fallbackDisposable = Disposer.newDisposable()

        try {
            HeadlessDataManager.fallbackToProductionDataManager(fallbackDisposable)

            val existingChange = Change(null, contentRevision(existingPath, "1"))

            val tree = JujutsuChangesTree(project.get()) // showsLocalFiles defaults to false
            tree.setChangesToDisplay(listOf(existingChange))
            waitForRefresh(tree)
            tree.selectionRows = IntArray(tree.rowCount) { it }

            val dataContext = DataManager.getInstance().getDataContext(tree)

            VIRTUAL_FILE_ARRAY.getData(dataContext) shouldBe null
            VIRTUAL_FILE.getData(dataContext) shouldBe null
        } finally {
            Disposer.dispose(fallbackDisposable)
            Files.deleteIfExists(existingPath)
        }
    }

    private fun waitForRefresh(tree: JujutsuChangesTree, timeoutMillis: Long = 5_000) {
        var refreshed = false
        tree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }
}

private fun contentRevision(existingPath: Path, revision: String): ContentRevision =
    SimpleContentRevision("", LocalFilePath(existingPath, false), revision)

private fun contentRevision(existingPath: String, revision: String): ContentRevision =
    SimpleContentRevision("", LocalFilePath(existingPath, false), revision)
