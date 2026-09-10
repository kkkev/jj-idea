package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.jupiter.api.Test

/**
 * A minimal, non-mockk [JujutsuRepository] stand-in for scale tests. [ChangeKey] hashes/compares
 * its repo on every map operation, and at this test's n=20,000 scale that happens tens of
 * thousands of times - a mockk proxy's per-call recording overhead (fine at ordinary test sizes)
 * compounds badly under that many invocations, turning an O(n) test into a multi-minute, heap-
 * churning one with no algorithmic regression at all. Identity-based `equals`/`hashCode`
 * (inherited from `Any`, never overridden here) is all [GraphEdgeIndex] needs; every other member
 * is unreachable from this test and left unimplemented.
 */
private val TODO_REPO: JujutsuRepository = object : JujutsuRepository {
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

/**
 * Operation-count scale test for [GraphEdgeIndex.build] (jj-idea-sc8m), per contributing.md's
 * "PR requirement - scale analysis as a deliverable": this index replaces two duplicated
 * O(rows)-per-call passes ([JujutsuGraphAndDescriptionRenderer]'s old `getRowPassthroughs` and
 * `JujutsuLogTableRenderers.graphTextStartX`'s own scan) with one shared pass, built once per
 * graph update - this asserts that pass itself stays linear in row count, never quadratic, using
 * synthetic in-memory graphs (no platform), mirroring
 * [in.kkkev.jjidea.ui.log.graph.GraphLayoutScaleTest]'s pattern. See [TODO_REPO]'s doc for why the
 * repo here isn't a mockk mock.
 */
class GraphEdgeIndexScaleTest {
    private val repo = TODO_REPO

    private fun entry(id: Int, parentIds: List<Int> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId("e$id", "e$id", null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $id",
        parentIds = parentIds.map { ChangeId("e$it", "e$it", null) }
    )

    @Test
    fun `linear chain stays linear, not quadratic`() {
        val n = 20_000
        val entries = (0 until n).map { i -> entry(i, if (i + 1 < n) listOf(i + 1) else emptyList()) }
        val nodes = CommitGraphBuilder().buildGraph(entries)

        val index = GraphEdgeIndex.build(entries, nodes)

        // A quadratic regression (re-marking every prior row on each new edge) would be ~n²/2
        // (≈2*10^8 for n=20k) - far above any plausible linear bound.
        index.operationCount shouldBeLessThan (5L * n)
    }

    @Test
    fun `wide DAG with bounded passthrough width stays O(n times width), not quadratic`() {
        val n = 20_000
        val width = 8
        // Each entry's single parent is `width` rows below - opens a passthrough that spans
        // `width` rows. At most `width` passthroughs are open at any time, so total marking work
        // should scale with n * width, not n^2.
        val entries = (0 until n).map { i -> entry(i, if (i + width < n) listOf(i + width) else emptyList()) }
        val nodes = CommitGraphBuilder().buildGraph(entries)

        val index = GraphEdgeIndex.build(entries, nodes)

        index.operationCount shouldBeLessThan (20L * n * width)
    }
}
