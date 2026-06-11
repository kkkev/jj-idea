package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [FileChange.allPaths] — ensures every path a change touches is returned, which is
 * important for restore operations that must address both sides of a rename or the source of a delete.
 */
class FileChangeAllPathsTest {
    private val rev = ChangeIdRevisionNumber(ChangeId("abc123abc123", "abc1"))

    private fun revision(path: String): ContentRevision = mockk {
        every { file } returns LocalFilePath("/project/$path", false)
        every { revisionNumber } returns rev
        every { content } returns ""
    }

    @Test
    fun `Modified change yields single path`() {
        val change = Change(revision("Foo.kt"), revision("Foo.kt"))
        val paths = FileChange.from(change).allPaths
        paths shouldHaveSize 1
        paths.map { it.path } shouldContainExactlyInAnyOrder listOf("/project/Foo.kt")
    }

    @Test
    fun `Added change yields after path only`() {
        val change = Change(null, revision("New.kt"))
        val paths = FileChange.from(change).allPaths
        paths shouldHaveSize 1
        paths.map { it.path } shouldContainExactlyInAnyOrder listOf("/project/New.kt")
    }

    @Test
    fun `Deleted change yields before path (not after)`() {
        val change = Change(revision("Gone.kt"), null)
        val paths = FileChange.from(change).allPaths
        paths shouldHaveSize 1
        paths.map { it.path } shouldContainExactlyInAnyOrder listOf("/project/Gone.kt")
    }

    @Test
    fun `Renamed change yields both source and target paths`() {
        val change = Change(revision("old.kt"), revision("new.kt"))
        val paths = FileChange.from(change).allPaths
        paths shouldHaveSize 2
        paths.map { it.path } shouldContainExactlyInAnyOrder listOf("/project/old.kt", "/project/new.kt")
    }
}
