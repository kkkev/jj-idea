package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [sameChangesAndStatuses] is what lets a background refresh skip rebuilding a [JujutsuChangesTree]
 * (and, transitively, keep the diff preview's request cache hitting rather than resetting scroll
 * position — jj-idea-q6vn) when nothing actually changed. Also guards the jj-idea-3cvb regression:
 * plain [Change.equals] ignores [Change.getFileStatus], so a resolved conflict must still be
 * detected as a real change.
 */
class SameChangesAndStatusesTest {
    private fun change(path: String, status: FileStatus) =
        Change(null, SimpleContentRevision("", LocalFilePath(path, false), "1"), status)

    @Test
    fun `identical change lists are the same`() {
        val a = listOf(change("src/A.kt", FileStatus.MODIFIED), change("src/B.kt", FileStatus.ADDED))
        val b = listOf(change("src/A.kt", FileStatus.MODIFIED), change("src/B.kt", FileStatus.ADDED))

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
}
