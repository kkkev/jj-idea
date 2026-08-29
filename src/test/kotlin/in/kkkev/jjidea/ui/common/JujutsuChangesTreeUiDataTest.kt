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
 * Covers the Working Copy tree exposing its selection as [VIRTUAL_FILE] and [VIRTUAL_FILE_ARRAY],
 * mirroring the platform's own [com.intellij.openapi.vcs.changes.ui.ChangesListView]
 * (used by IntelliJ's built-in Git/Commit changes view) so globally-bound file actions such as
 * Reformat Code and Optimize Imports can act on the selection.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuChangesTreeUiDataTest {
    private val project = projectFixture()

    @Test
    fun `selection is exposed as virtual files, skipping changes with no resolvable file`() {
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

            val tree = JujutsuChangesTree(project.get())
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
