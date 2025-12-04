package `in`.kkkev.jjidea.ui

import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for parsing and representing jj log entries
 */
class JujutsuLogEntryTest {
    companion object {
        val CHANGE_ID = ChangeId("qpvuntsm", 2)
        val ALICE = VcsUserImpl("Alice", "alice@example.com")
        val BOB = VcsUserImpl("Bob", "bob@example.com")
    }

    @Test
    fun `parse log entry with all fields`() {
        val commitId = "abc123def456"
        val description = "Add new feature"
        val bookmarks = listOf("main", "feature-branch")

        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = commitId,
            description = description,
            bookmarks = bookmarks
        )

        entry.changeId shouldBe CHANGE_ID
        entry.commitId shouldBe commitId
        entry.description shouldBe description
        entry.bookmarks shouldContain "main"
        entry.bookmarks shouldContain "feature-branch"
    }

    @Test
    fun `working copy entry has special marker`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Work in progress",
            isWorkingCopy = true
        )

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `conflict entry has marker`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Conflicted change",
            hasConflict = true
        )

        entry.hasConflict shouldBe true
    }

    @Test
    fun `empty commit has marker`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isEmpty = true
        )

        entry.isEmpty shouldBe true
    }

    @Test
    fun `undescribed commit has marker`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isUndescribed = true
        )

        entry.isUndescribed shouldBe true
    }

    @Test
    fun `format change ID for display`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test"
        )

        val formatted = entry.getFormattedChangeId()

        formatted.shortPart shouldBe "qp"
        formatted.restPart shouldBe "vuntsm"
    }

    @Test
    fun `display markers for special states`() {
        val conflictEntry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            hasConflict = true
        )

        val markers = conflictEntry.getMarkers()

        markers shouldContain "conflict"
    }

    @Test
    fun `display markers for empty commit`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isEmpty = true
        )

        val markers = entry.getMarkers()

        markers shouldContain "empty"
    }

    @Test
    fun `display markers for undescribed commit`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isUndescribed = true
        )

        val markers = entry.getMarkers()

        markers shouldContain "(no description)"
    }

    @Test
    fun `display multiple markers at once`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            hasConflict = true,
            isEmpty = true,
            isUndescribed = true
        )

        val markers = entry.getMarkers()

        markers.size shouldBe 3
        markers shouldContain "conflict"
        markers shouldContain "empty"
        markers shouldContain "(no description)"
    }

    @Test
    fun `getDisplayDescription for normal commit`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Add new feature"
        )

        entry.getDisplayDescription() shouldBe "Add new feature"
    }

    @Test
    fun `getDisplayDescription for conflict with empty description`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            hasConflict = true
        )

        entry.getDisplayDescription() shouldBe "(conflict)"
    }

    @Test
    fun `getDisplayDescription for empty commit`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isEmpty = true
        )

        entry.getDisplayDescription() shouldBe "(empty)"
    }

    @Test
    fun `getDisplayDescription for undescribed commit`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isUndescribed = true
        )

        entry.getDisplayDescription() shouldBe "(no description)"
    }

    @Test
    fun `getDisplayDescription for empty description without special flags`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = ""
        )

        entry.getDisplayDescription() shouldBe "(no description)"
    }

    @Test
    fun `getBookmarkDisplay with no bookmarks`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            bookmarks = emptyList()
        )

        entry.getBookmarkDisplay() shouldBe ""
    }

    @Test
    fun `getBookmarkDisplay with single bookmark`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            bookmarks = listOf("main")
        )

        entry.getBookmarkDisplay() shouldBe "main"
    }

    @Test
    fun `getBookmarkDisplay with multiple bookmarks`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            bookmarks = listOf("main", "develop", "feature")
        )

        entry.getBookmarkDisplay() shouldBe "main, develop, feature"
    }

    @Test
    fun `getParentIdsDisplay with no parents`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            parentIds = emptyList()
        )

        entry.getParentIdsDisplay() shouldBe ""
    }

    @Test
    fun `getParentIdsDisplay with single parent`() {
        val parentId = ChangeId("abcdefgh", 2)
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            parentIds = listOf(parentId)
        )

        entry.getParentIdsDisplay() shouldBe "ab:cdefgh"
    }

    @Test
    fun `getParentIdsDisplay with multiple parents (merge)`() {
        val parent1 = ChangeId("abcdefgh", 2)
        val parent2 = ChangeId("xyzwvuts", 2)
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Merge commit",
            parentIds = listOf(parent1, parent2)
        )

        entry.getParentIdsDisplay() shouldBe "ab:cdefgh, xy:zwvuts"
    }

    @Test
    fun `entry with author and committer information`() {
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            author = ALICE,
            committer = BOB
        )

        entry.author shouldBe ALICE
        entry.committer shouldBe BOB
    }

    @Test
    fun `entry with timestamps`() {
        val authorTime = 1638360000000L  // Example timestamp
        val committerTime = 1638361000000L
        val entry = LogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
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
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = multiLineDesc
        )

        entry.description shouldBe multiLineDesc
        entry.getDisplayDescription() shouldBe multiLineDesc
    }
}
