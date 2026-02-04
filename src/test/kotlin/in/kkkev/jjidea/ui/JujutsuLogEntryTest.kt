package `in`.kkkev.jjidea.ui

import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Tests for parsing and representing jj log entries
 */
class JujutsuLogEntryTest {
    companion object {
        val CHANGE_ID = ChangeId("qpvuntsm", "qp", 2)
        val COMMIT_ID = CommitId("abc123", 2)

        val JUJUTSU_ROOT = mockk<JujutsuRepository>()

        val ALICE = VcsUserImpl("Alice", "alice@example.com")
        val BOB = VcsUserImpl("Bob", "bob@example.com")
    }

    @Test
    fun `parse log entry with all fields`() {
        val commitId = CommitId("abc123def456")
        val description = "Add new feature"
        val bookmarks = listOf(Bookmark("main"), Bookmark("feature-branch"))

        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = commitId,
            underlyingDescription = description,
            bookmarks = bookmarks
        )

        entry.id shouldBe CHANGE_ID
        entry.commitId shouldBe commitId
        entry.description.actual shouldBe description
        entry.bookmarks shouldContain Bookmark("main")
        entry.bookmarks shouldContain Bookmark("feature-branch")
    }

    @Test
    fun `working copy entry has special marker`() {
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = "Work in progress",
            isWorkingCopy = true
        )

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `conflict entry has marker`() {
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = "Conflicted change",
            hasConflict = true
        )

        entry.hasConflict shouldBe true
    }

    @Test
    fun `empty commit has marker`() {
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = "",
            isEmpty = true
        )

        entry.isEmpty shouldBe true
    }

    @Test
    fun `undescribed commit has marker`() {
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = ""
        )

        entry.description.empty shouldBe true
    }

    @Test
    fun `entry with author and committer information`() {
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = "Test",
            author = ALICE,
            committer = BOB
        )

        entry.author shouldBe ALICE
        entry.committer shouldBe BOB
    }

    @Test
    fun `entry with timestamps`() {
        val authorTime = Instant.fromEpochMilliseconds(1638360000000L)
        val committerTime = Instant.fromEpochMilliseconds(1638361000000L)
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = "Test",
            authorTimestamp = authorTime,
            committerTimestamp = committerTime
        )

        entry.authorTimestamp shouldBe authorTime
        entry.committerTimestamp shouldBe committerTime
    }

    @Test
    fun `multi-line description is preserved`() {
        val multiLineDesc = "First line\n\nSecond paragraph\nThird line"
        val entry = LogEntry(
            repo = JUJUTSU_ROOT,
            id = CHANGE_ID,
            commitId = COMMIT_ID,
            underlyingDescription = multiLineDesc
        )

        entry.description.actual shouldBe multiLineDesc
    }
}
