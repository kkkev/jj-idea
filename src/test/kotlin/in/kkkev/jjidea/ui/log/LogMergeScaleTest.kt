package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/** Same minimal, non-mockk stand-in as [GraphEdgeIndexScaleTest]'s `TODO_REPO` - see its doc. */
private val REPO: JujutsuRepository = object : JujutsuRepository {
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

private fun entry(id: String, parentId: String?, timestampSeconds: Long) = LogEntry(
    repo = REPO,
    id = ChangeId(id, id, null),
    commitId = CommitId(id.padEnd(40, '0')),
    underlyingDescription = "entry $id",
    parentIds = if (parentId != null) listOf(ChangeId(parentId, parentId, null)) else emptyList(),
    authorTimestamp = Instant.fromEpochSeconds(timestampSeconds)
)

private fun snapshotOf(entries: List<LogEntry>) =
    MergedSnapshot(entries, entries.mapTo(HashSet()) { it.key }, entries.minOf { it.sortTimestamp() }, emptyMap())

/**
 * Scale/correctness test for jj-idea-jnqi's [appendGuardHolds] + [CommitGraphBuilder.appendGraph]
 * wiring - the loader-level half of the fix (the graph-layout algorithm itself is covered by
 * `graph.IncrementalLayoutScaleTest`). Directly asserts the bead's own ask: "a scale test
 * asserting buildGraph/topologicalSort's total operationCount across N sequential loadMore()
 * calls stays O(total rows), not O(total rows * N)" - simulated here at the same granularity
 * `UnifiedJujutsuLogDataLoader.mergeAndNotify` uses (guard check, then
 * [CommitGraphBuilder.appendGraph] or a from-scratch [CommitGraphBuilder.buildGraph]).
 */
class LogMergeScaleTest {
    @Test
    fun `N sequential page appends keep the guard passing and total layout work stays O(total rows)`() {
        val pageSize = 500
        val pageCount = 60
        val totalRows = pageSize * pageCount

        // e0 (newest) -> e1 -> ... -> e(totalRows-1) (oldest): a linear chain paged back through
        // history exactly as loadMore() does - each page strictly older than the last.
        val all = (0 until totalRows).map { i ->
            entry("e$i", if (i + 1 < totalRows) "e${i + 1}" else null, timestampSeconds = totalRows - i.toLong())
        }
        val pages = all.chunked(pageSize)

        val graphBuilder = CommitGraphBuilder()
        var snapshot: MergedSnapshot? = null
        var totalOperations = 0L
        var pagesSoFar = emptyList<LogEntry>()

        for ((pageIndex, page) in pages.withIndex()) {
            val current = snapshot
            val graphNodes = if (current != null &&
                appendGuardHolds(page, current, emptyMap(), hasExpansionOrSearch = false)
            ) {
                val nodes = graphBuilder.appendGraph(page)
                totalOperations += graphBuilder.incrementalOperationCount
                snapshot = MergedSnapshot(
                    current.entries + page,
                    current.keys + page.mapTo(HashSet()) { it.key },
                    minOf(current.minTimestamp, page.minOf { it.sortTimestamp() }),
                    emptyMap()
                )
                nodes
            } else {
                // Only the very first page should ever take this branch for this monotone shape.
                val nodes = graphBuilder.buildGraph(page)
                snapshot = snapshotOf(page)
                nodes
            }
            pagesSoFar = pagesSoFar + page

            // jj-idea-jnqi bugfix regression check: every row loaded so far must still have a
            // GraphNode after each append - this is exactly the assertion missing before, which
            // let the "incrementalEngine never reseeded" bug through (appendGraph()'s map only
            // ever contained the latest page). Set equality (not kotest's order-insensitive
            // collection matcher, which is pairwise and O(n*m) - far too slow at this scale:
            // summed across 60 growing pages it turned a sub-second test into ~5 minutes).
            graphNodes.keys shouldBe pagesSoFar.mapTo(HashSet()) { it.key }

            // Full value equivalence against a fresh from-scratch build, at a few checkpoints
            // (not every page - O(total rows) each, so only spot-checked to keep the test fast).
            if (pageIndex == 0 || pageIndex == pages.size / 2 || pageIndex == pages.size - 1) {
                graphNodes shouldContainExactly CommitGraphBuilder().buildGraph(pagesSoFar)
            }
        }

        // A from-scratch-per-page implementation's total work across N pages is
        // ~pageSize * pageCount * (pageCount+1) / 2 (≈9.2M work-units here) - two orders of
        // magnitude above any plausible linear bound. Linear total work is a small constant per row.
        totalOperations shouldBeLessThan (5L * totalRows)
    }

    @Test
    fun `guard passes for a page strictly older than the current snapshot`() {
        val current = snapshotOf(listOf(entry("e0", "e1", 100), entry("e1", null, 90)))
        val delta = listOf(entry("e2", "e3", 80), entry("e3", null, 70))
        appendGuardHolds(delta, current, emptyMap(), hasExpansionOrSearch = false) shouldBe true
    }

    @Test
    fun `guard fails when the delta references an already-merged entry as a parent`() {
        // e2's parent is e0, which is already in the snapshot - e2 would be a CHILD of an
        // existing row, not a pure append.
        val current = snapshotOf(listOf(entry("e0", null, 100)))
        val delta = listOf(entry("e2", "e0", 80))
        appendGuardHolds(delta, current, emptyMap(), hasExpansionOrSearch = false) shouldBe false
    }

    @Test
    fun `guard fails when a delta entry is newer than the snapshot's oldest entry`() {
        val current = snapshotOf(listOf(entry("e0", null, 100)))
        val delta = listOf(entry("e1", null, 150)) // newer than e0
        appendGuardHolds(delta, current, emptyMap(), hasExpansionOrSearch = false) shouldBe false
    }

    @Test
    fun `guard fails when correctionsByRepo changed since the snapshot`() {
        val current = MergedSnapshot(
            listOf(entry("e0", null, 100)),
            setOf(entry("e0", null, 100).key),
            Instant.fromEpochSeconds(100),
            mapOf(REPO to BookmarkCorrections(setOf("old-deleted"), emptyMap()))
        )
        val delta = listOf(entry("e1", null, 90))
        val correctionsByRepo = mapOf(REPO to BookmarkCorrections(setOf("new-deleted"), emptyMap()))
        appendGuardHolds(delta, current, correctionsByRepo, hasExpansionOrSearch = false) shouldBe false
    }

    @Test
    fun `guard fails when an expansion or search bucket is in play`() {
        val current = snapshotOf(listOf(entry("e0", null, 100)))
        val delta = listOf(entry("e1", null, 90))
        appendGuardHolds(delta, current, emptyMap(), hasExpansionOrSearch = true) shouldBe false
    }
}
