package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Covers the GitHub #56 conflicts-grouping feature: conflicted changes are pulled out from under
 * the normal directory/repository grouping into a single [JujutsuConflictsNode] pinned to the top
 * of the tree, gated behind [JujutsuChangesTree]'s `groupConflicts` opt-in.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuChangesTreeConflictsTest {
    private val project = projectFixture()

    @Test
    fun `conflicted changes are grouped under a conflicts node, others are not`() {
        val conflicted = change("src/Parser.kt", FileStatus.MERGED_WITH_CONFLICTS)
        val clean = change("src/Utils.kt", FileStatus.MODIFIED)
        val tree = JujutsuChangesTree(project.get(), groupConflicts = true)

        tree.setChangesToDisplay(listOf(conflicted, clean))
        waitForRefresh(tree)

        val conflictsNode = tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>().single()
        conflictsNode.traverseObjectsUnder().filterIsInstance<Change>().toList() shouldBe listOf(conflicted)
        tree.root.traverseObjectsUnder().filterIsInstance<Change>().toList().let { all ->
            (all - conflicted) shouldBe listOf(clean)
        }
    }

    @Test
    fun `no conflicts means no conflicts node`() {
        val tree = JujutsuChangesTree(project.get(), groupConflicts = true)

        tree.setChangesToDisplay(listOf(change("src/Utils.kt", FileStatus.MODIFIED)))
        waitForRefresh(tree)

        tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>() shouldBe emptyList()
    }

    @Test
    fun `conflicts node sorts before other top-level nodes`() {
        val conflicted = change("zzz/Parser.kt", FileStatus.MERGED_WITH_CONFLICTS)
        val clean = change("aaa/Utils.kt", FileStatus.MODIFIED)
        val tree = JujutsuChangesTree(project.get(), groupConflicts = true)

        tree.setChangesToDisplay(listOf(conflicted, clean))
        waitForRefresh(tree)

        tree.root.childNodes().first() shouldBe tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>().single()
    }

    @Test
    fun `groupConflicts defaults to false and produces no conflicts node`() {
        val conflicted = change("src/Parser.kt", FileStatus.MERGED_WITH_CONFLICTS)
        val tree = JujutsuChangesTree(project.get())

        tree.setChangesToDisplay(listOf(conflicted))
        waitForRefresh(tree)

        tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>() shouldBe emptyList()
        tree.root.traverseObjectsUnder().filterIsInstance<Change>().toList() shouldBe listOf(conflicted)
    }

    @Test
    fun `conflicts node text presentation is count-free`() {
        val one = change("src/Parser.kt", FileStatus.MERGED_WITH_CONFLICTS)
        val many = (1..50).map { change("src/File$it.kt", FileStatus.MERGED_WITH_CONFLICTS) }
        val tree = JujutsuChangesTree(project.get(), groupConflicts = true)

        tree.setChangesToDisplay(listOf(one))
        waitForRefresh(tree)
        val presentationForOne =
            tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>().single().textPresentation

        tree.setChangesToDisplay(many)
        waitForRefresh(tree)
        val presentationForMany =
            tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>().single().textPresentation

        presentationForOne shouldBe presentationForMany
    }

    @Test
    fun `scale - conflicts and non-conflicts partition linearly over 5000 changes`() {
        val changes = (1..5000).map {
            change("src/File$it.kt", if (it % 10 == 0) FileStatus.MERGED_WITH_CONFLICTS else FileStatus.MODIFIED)
        }
        val expectedConflicted = changes.count { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
        val tree = JujutsuChangesTree(project.get(), groupConflicts = true)

        tree.setChangesToDisplay(changes)
        // 5000 items genuinely takes longer to build off-EDT than the other cases here, and CI
        // legs (especially older-platform ones under retry load) can be markedly slower than a
        // local run - the default 5s deadline flaked on 2025.2 CI (GitHub Actions) with a plain
        // timeout, not a real assertion failure. This test only checks final partition counts,
        // not wall-clock behaviour, so a generous deadline costs nothing when the build is fast.
        waitForRefresh(tree, timeoutMillis = 20_000)

        val conflictsNode = tree.root.childNodes().filterIsInstance<JujutsuConflictsNode>().single()
        conflictsNode.traverseObjectsUnder().filterIsInstance<Change>().count() shouldBe expectedConflicted
        tree.root.traverseObjectsUnder().filterIsInstance<Change>().count() shouldBe changes.size
    }

    /** [DefaultMutableTreeNode.children] returns a raw [java.util.Enumeration]; expose it typed. */
    private fun ChangesBrowserNode<*>.childNodes(): List<ChangesBrowserNode<*>> =
        (0 until childCount).map { getChildAt(it) as ChangesBrowserNode<*> }

    private fun waitForRefresh(tree: JujutsuChangesTree, timeoutMillis: Long = 5_000) {
        var refreshed = false
        tree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }

    private fun change(path: String, status: FileStatus): Change {
        val filePath = LocalFilePath(path, false)
        return Change(SimpleContentRevision("", filePath, "0"), SimpleContentRevision("", filePath, "1"), status)
    }
}
