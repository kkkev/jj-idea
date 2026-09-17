package `in`.kkkev.jjidea.ui.common

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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private const val PREVIEW_PROPERTY = "jjidea.preview.dragAndDrop"

/**
 * Coverage for [JujutsuChangesTree.installFilesDragSource] (jj-idea-yvry, -b2oi): the pure
 * [filesDragPayload] hit-test, and the [PreviewFeature.DRAG_AND_DROP]-gated install itself -
 * mirroring [in.kkkev.jjidea.ui.log.JujutsuLogTableDnDTest]'s coverage of
 * [in.kkkev.jjidea.ui.log.installDragAndDrop] for the log table's own source.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuChangesTreeDnDTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    @AfterEach
    fun cleanUp() {
        System.clearProperty(PREVIEW_PROPERTY)
    }

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
    )

    private fun change(path: String) = Change(null, SimpleContentRevision("", LocalFilePath(path, false), "1"))

    private fun treeWith(changes: List<Change>): JujutsuChangesTree {
        val tree = JujutsuChangesTree(project.get())
        tree.setChangesToDisplay(changes)
        var refreshed = false
        tree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) UIUtil.dispatchAllInvocationEvents()
        tree.selectionRows = IntArray(tree.rowCount) { it }
        return tree
    }

    // region filesDragPayload

    @Test
    fun `an empty selection produces no payload`() {
        val tree = JujutsuChangesTree(project.get())

        tree.filesDragPayload { entry("aaaaaaaa") }.shouldBeNull()
    }

    @Test
    fun `ownerFor returning null - e g a multi-commit selection with no single owner - produces no payload`() {
        val tree = treeWith(listOf(change("/a")))

        tree.filesDragPayload { null }.shouldBeNull()
    }

    @Test
    fun `a non-empty selection with a resolvable owner produces a Files payload carrying both`() {
        val owner = entry("aaaaaaaa")
        val theChange = change("/a")
        val tree = treeWith(listOf(theChange))

        val payload = tree.filesDragPayload { owner }

        payload.shouldNotBeNull()
        payload.owner shouldBe owner
        payload.changes shouldBe listOf(theChange)
    }

    // endregion

    // region gating

    @Test
    fun `installFilesDragSource does not throw with the preview feature off`() {
        val tree = treeWith(listOf(change("/a")))

        tree.installFilesDragSource(project.get()) { entry("aaaaaaaa") }
    }

    @Test
    fun `installFilesDragSource does not throw with the preview feature on`() {
        System.setProperty(PREVIEW_PROPERTY, "true")
        val tree = treeWith(listOf(change("/a")))

        // DnDManager is a no-op in tests (HeadlessDnDManager, same as JujutsuLogTableDnDTest), so
        // there's no client-property-style signal that install() actually ran (unlike
        // installDragAndDrop's SmoothAutoScroller side effect) - this only asserts the gated call
        // itself is safe; filesDragPayload above covers the actual behaviour the builder wires up.
        tree.installFilesDragSource(project.get()) { entry("aaaaaaaa") }
    }

    // endregion
}
