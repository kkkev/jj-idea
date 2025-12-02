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
            shortChangeIdPrefix = "qp",
            bookmarks = bookmarks
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
            shortChangeIdPrefix = "qp",
            isWorkingCopy = true
        )

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `conflict entry has marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "Conflicted change",
            shortChangeIdPrefix = "qp",
            hasConflict = true
        )

        entry.hasConflict shouldBe true
    }

    @Test
    fun `empty commit has marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "",
            shortChangeIdPrefix = "qp",
            isEmpty = true
        )

        entry.isEmpty shouldBe true
    }

    @Test
    fun `undescribed commit has marker`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "",
            shortChangeIdPrefix = "qp",
            isUndescribed = true
        )

        entry.isUndescribed shouldBe true
    }

    @Test
    fun `format change ID for display`() {
        val entry = JujutsuLogEntry(
            changeId = "qpvuntsm",
            commitId = "abc123",
            description = "Test",
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
            shortChangeIdPrefix = "qp",
            hasConflict = true
        )

        val markers = conflictEntry.getMarkers()

        markers shouldContain "conflict"
    }
}
