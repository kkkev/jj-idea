package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Same minimal, non-mockk stand-in as [GraphEdgeIndexScaleTest]'s `TODO_REPO` - see its doc. */
private val REPO2: JujutsuRepository = object : JujutsuRepository {
    override val displayName get() = throw UnsupportedOperationException()
    override val project get() = throw UnsupportedOperationException()
    override val directory get() = throw UnsupportedOperationException()
    override val commandExecutor get() = throw UnsupportedOperationException()
    override val logService get() = throw UnsupportedOperationException()
    override val logCache get() = throw UnsupportedOperationException()
    override val isInitialised get() = throw UnsupportedOperationException()
    override val gitRemotes get() = throw UnsupportedOperationException()
    override val cachedGitRemotes get() = throw UnsupportedOperationException()
    override val workingCopy get() = throw UnsupportedOperationException()
    override fun getLogEntry(revision: `in`.kkkev.jjidea.jj.Revision) = throw UnsupportedOperationException()
    override fun getLogEntry(contentLocator: `in`.kkkev.jjidea.jj.ContentLocator) =
        throw UnsupportedOperationException()
    override fun revisionNumberFor(filePath: com.intellij.openapi.vcs.FilePath) = throw UnsupportedOperationException()
    override fun createContentRevision(
        filePath: com.intellij.openapi.vcs.FilePath,
        contentLocator: `in`.kkkev.jjidea.jj.ContentLocator
    ) = throw UnsupportedOperationException()
    override fun createContentRevision(filePath: com.intellij.openapi.vcs.FilePath, logEntry: LogEntry) =
        throw UnsupportedOperationException()
    override fun createContentRevision(fileAtVersion: `in`.kkkev.jjidea.jj.FileAtVersion) =
        throw UnsupportedOperationException()
    override fun createDiffSideFor(fileAtVersion: `in`.kkkev.jjidea.jj.FileAtVersion?) =
        throw UnsupportedOperationException()
    override fun getVirtualFile(fileAtVersion: `in`.kkkev.jjidea.jj.FileAtVersion) =
        throw UnsupportedOperationException()
    override fun getRelativePath(filePath: com.intellij.openapi.vcs.FilePath) = throw UnsupportedOperationException()
    override fun getRelativePath(file: com.intellij.openapi.vfs.VirtualFile) = throw UnsupportedOperationException()
}

private fun logEntry(id: String, parentId: String?) = LogEntry(
    repo = REPO2,
    id = ChangeId(id, id, null),
    commitId = CommitId(id.padEnd(40, '0')),
    underlyingDescription = "entry $id",
    parentIds = if (parentId != null) listOf(ChangeId(parentId, parentId, null)) else emptyList()
)

/**
 * Regression tests for jj-idea-jnqi's [CommitGraphBuilder.buildGraph]/[CommitGraphBuilder.appendGraph]
 * split, pinning the two bugs found in manual testing after that bead shipped:
 *
 * 1. [CommitGraphBuilder.buildGraph] never reseeded [CommitGraphBuilder.appendGraph]'s
 *    incremental engine, so the first [CommitGraphBuilder.appendGraph] call after any load
 *    treated its delta as the entire log - silently dropping every earlier row's `GraphNode`
 *    ("lose the graph for previous commits"). `LogMergeScaleTest`'s multi-page loop exercised
 *    this exact sequence but only asserted operation counts, never map completeness, so it
 *    passed anyway - these tests assert completeness/equivalence directly instead.
 * 2. A loaded (already-merged) parent must never be classified [ParentState.NOT_LOADED] after
 *    an append - the "several visible commits ... whose parent has not loaded" symptom.
 */
class CommitGraphBuilderAppendTest {
    @Test
    fun `appendGraph after buildGraph keeps every earlier row's GraphNode`() {
        val pageA = listOf(logEntry("a0", "a1"), logEntry("a1", null))
        val pageB = listOf(logEntry("b0", "b1"), logEntry("b1", null))

        val builder = CommitGraphBuilder()
        builder.buildGraph(pageA)
        val nodes = builder.appendGraph(pageB)

        nodes.keys shouldBe (pageA + pageB).mapTo(HashSet()) { it.key }
        nodes shouldContainExactly CommitGraphBuilder().buildGraph(pageA + pageB)
    }

    @Test
    fun `a full recompute after some appends resyncs the incremental engine for the next append`() {
        // buildGraph(pageA) -> appendGraph(pageB) -> buildGraph(pageA+pageB+pageC) (a Refresh
        // that re-walks every page) -> appendGraph(pageD): the transition the original tests
        // never exercised.
        val pageA = listOf(logEntry("a0", "a1"), logEntry("a1", null))
        val pageB = listOf(logEntry("b0", "b1"), logEntry("b1", null))
        val pageC = listOf(logEntry("c0", "c1"), logEntry("c1", null))
        val pageD = listOf(logEntry("d0", "d1"), logEntry("d1", null))

        val builder = CommitGraphBuilder()
        builder.buildGraph(pageA)
        builder.appendGraph(pageB)
        builder.buildGraph(pageA + pageB + pageC)
        val nodes = builder.appendGraph(pageD)

        val all = pageA + pageB + pageC + pageD
        nodes.keys shouldBe all.mapTo(HashSet()) { it.key }
        nodes shouldContainExactly CommitGraphBuilder().buildGraph(all)
    }

    @Test
    fun `a parent resolved by an earlier page is never classified NOT_LOADED after append`() {
        // Row "child" (in pageA) references "parent" (in pageB) as its parent. Once pageB is
        // appended, "parent" is loaded - "child"'s unresolvedParents must be empty, not stuck
        // showing NOT_LOADED from before pageB arrived.
        val pageA = listOf(logEntry("child", "parent"))
        val pageB = listOf(logEntry("parent", null))

        val builder = CommitGraphBuilder()
        builder.buildGraph(pageA)
        val nodes = builder.appendGraph(pageB)

        nodes.getValue(pageA[0].key).unresolvedParents shouldBe emptyMap()
    }
}
