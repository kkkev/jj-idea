package `in`.kkkev.jjidea.ui

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for parsing and representing jj log entries
 */
class JujutsuLogEntryTest {

    @Test
    fun `parse log entry with all fields`() {
        val changeId = "qpvuntsm"
        val commitId = "abc123def456"
        val description = "Add new feature"
        val bookmarks = listOf("main", "feature-branch")

        val entry = JujutsuLogEntry(
            changeId = changeId,
            commitId = commitId,
            description = description,
            bookmarks = bookmarks,
            isWorkingCopy = false,
            hasConflict = false,
            isEmpty = false,
            isUndescribed = false,
            shortChangeIdPrefix = "qp"
        )

        entry.changeId shouldBe changeId
        entry.commitId shouldBe commitId
        entry.description shouldBe description
        entry.bookmarks shouldContain "main"
        entry.bookmarks shouldContain "feature-branch"
    }

    @Test
    fun `working copy entry has special marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "Work in progress",
            bookmarks = emptyList(),
            isWorkingCopy = true,
            hasConflict = false,
            isEmpty = false,
            isUndescribed = false,
            shortChangeIdPrefix = "qp"
        )

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `conflict entry has marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "Conflicted change",
            bookmarks = emptyList(),
            isWorkingCopy = false,
            hasConflict = true,
            isEmpty = false,
            isUndescribed = false,
            shortChangeIdPrefix = "qp"
        )

        entry.hasConflict shouldBe true
    }

    @Test
    fun `empty commit has marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "",
            bookmarks = emptyList(),
            isWorkingCopy = false,
            hasConflict = false,
            isEmpty = true,
            isUndescribed = false,
            shortChangeIdPrefix = "qp"
        )

        entry.isEmpty shouldBe true
    }

    @Test
    fun `undescribed commit has marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "",
            bookmarks = emptyList(),
            isWorkingCopy = false,
            hasConflict = false,
            isEmpty = false,
            isUndescribed = true,
            shortChangeIdPrefix = "qp"
        )

        entry.isUndescribed shouldBe true
    }

    @Test
    fun `format change ID for display`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "Test",
            bookmarks = emptyList(),
            isWorkingCopy = false,
            hasConflict = false,
            isEmpty = false,
            isUndescribed = false,
            shortChangeIdPrefix = "qp"
        )

        val formatted = entry.getFormattedChangeId()

        formatted.shortPart shouldBe "qp"
        formatted.restPart shouldBe "vuntsm"
    }

    @Test
    fun `display markers for special states`() {
        val conflictEntry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "Test",
            bookmarks = emptyList(),
            isWorkingCopy = false,
            hasConflict = true,
            isEmpty = false,
            isUndescribed = false,
            shortChangeIdPrefix = "qp"
        )

        val markers = conflictEntry.getMarkers()

        markers shouldContain "conflict"
    }
}
