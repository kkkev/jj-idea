package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [sameChangesAndStatuses] is what lets a background refresh skip rebuilding a [JujutsuChangesTree]
 * (and, transitively, keep the diff preview's request cache hitting rather than resetting scroll
 * position — jj-idea-q6vn) when nothing actually changed. Also guards the jj-idea-3cvb regression:
 * plain [Change.equals] ignores [Change.getFileStatus], so a resolved conflict must still be
 * detected as a real change. And the jj-idea-4diu regression: plain [Change.equals] also ignores
 * the before/after [ContentRevision]s, so a selection that moves to a different commit touching
 * the same paths with the same statuses must still be detected as a real change.
 */
class SameChangesAndStatusesTest {
    /** Mirrors the plugin's own [ContentRevision]s: a `data class` keyed on a revision id. */
    private data class TestRevision(private val path: FilePath, private val revisionId: String) : ContentRevision {
        override fun getFile() = path
        override fun getContent(): String? = null
        override fun getRevisionNumber() = throw UnsupportedOperationException()
    }

    private fun change(path: String, status: FileStatus, revisionId: String = "1") =
        Change(null, TestRevision(LocalFilePath(path, false), revisionId), status)

    @Test
    fun `identical change lists are the same`() {
        val a = listOf(change("src/A.kt", FileStatus.MODIFIED), change("src/B.kt", FileStatus.ADDED))
        val b = listOf(change("src/A.kt", FileStatus.MODIFIED), change("src/B.kt", FileStatus.ADDED))

        sameChangesAndStatuses(a, b) shouldBe true
    }

    @Test
    fun `value-equal but distinct revision instances are the same`() {
        // Reconstructing an unchanged selection (e.g. a background refresh) produces new
        // ContentRevision instances that are value-equal, not reference-equal, to the old ones.
        val a = listOf(Change(null, TestRevision(LocalFilePath("src/A.kt", false), "1"), FileStatus.ADDED))
        val b = listOf(Change(null, TestRevision(LocalFilePath("src/A.kt", false), "1"), FileStatus.ADDED))

        sameChangesAndStatuses(a, b) shouldBe true
    }

    @Test
    fun `different sizes are not the same`() {
        val a = listOf(change("src/A.kt", FileStatus.MODIFIED))
        val b = listOf(change("src/A.kt", FileStatus.MODIFIED), change("src/B.kt", FileStatus.ADDED))

        sameChangesAndStatuses(a, b) shouldBe false
    }

    @Test
    fun `same paths but a different file status are not the same`() {
        val a = listOf(change("src/A.kt", FileStatus.MERGED_WITH_CONFLICTS))
        val b = listOf(change("src/A.kt", FileStatus.MODIFIED))

        sameChangesAndStatuses(a, b) shouldBe false
    }

    @Test
    fun `different paths are not the same`() {
        val a = listOf(change("src/A.kt", FileStatus.MODIFIED))
        val b = listOf(change("src/B.kt", FileStatus.MODIFIED))

        sameChangesAndStatuses(a, b) shouldBe false
    }

    @Test
    fun `same paths and statuses but different revisions are not the same (jj-idea-4diu)`() {
        // e.g. selecting change c after change b, where both b and c modify the same file: the
        // Change lists have identical paths and statuses but point at different content.
        val a = listOf(change("src/A.kt", FileStatus.MODIFIED, revisionId = "b"))
        val b = listOf(change("src/A.kt", FileStatus.MODIFIED, revisionId = "c"))

        sameChangesAndStatuses(a, b) shouldBe false
    }

    @Test
    fun `distinct CurrentContentRevision instances over the same path are the same`() {
        val path = LocalFilePath("src/A.kt", false)
        val a = listOf(Change(null, CurrentContentRevision(path), FileStatus.ADDED))
        val b = listOf(Change(null, CurrentContentRevision(path), FileStatus.ADDED))

        sameChangesAndStatuses(a, b) shouldBe true
    }
}
