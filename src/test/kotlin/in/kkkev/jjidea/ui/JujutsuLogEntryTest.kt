package `in`.kkkev.jjidea.ui

import `in`.kkkev.jjidea.log.ChangeId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for parsing and representing jj log entries
 */
class JujutsuLogEntryTest {
    companion object {
        val CHANGE_ID = ChangeId("qpvuntsm", 2)
    }

    @Test
    fun `parse log entry with all fields`() {
        val commitId = "abc123def456"
        val description = "Add new feature"
        val bookmarks = listOf("main", "feature-branch")

        val entry = JujutsuLogEntry(
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
        val entry = JujutsuLogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Work in progress",
            isWorkingCopy = true
        )

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `conflict entry has marker`() {
        val entry = JujutsuLogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Conflicted change",
            hasConflict = true
        )

        entry.hasConflict shouldBe true
    }

    @Test
    fun `empty commit has marker`() {
        val entry = JujutsuLogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isEmpty = true
        )

        entry.isEmpty shouldBe true
    }

    @Test
    fun `undescribed commit has marker`() {
        val entry = JujutsuLogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "",
            isUndescribed = true
        )

        entry.isUndescribed shouldBe true
    }

    @Test
    fun `format change ID for display`() {
        val entry = JujutsuLogEntry(
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
        val conflictEntry = JujutsuLogEntry(
            changeId = CHANGE_ID,
            commitId = "abc123",
            description = "Test",
            hasConflict = true
        )

        val markers = conflictEntry.getMarkers()

        markers shouldContain "conflict"
    }
}
