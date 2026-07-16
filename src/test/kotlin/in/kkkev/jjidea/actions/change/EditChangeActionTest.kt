package `in`.kkkev.jjidea.actions.change

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [editableEntry], the pure logic that decides whether the log selection can be
 * "Edit"ed - i.e. moved to and made the new working copy. The working copy is already `@`,
 * and immutable changes can never become the working copy.
 */
class EditChangeActionTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(isWorkingCopy: Boolean = false, immutable: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId("abc123", "ab", null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = "Test commit",
        bookmarks = emptyList(),
        parentIdentifiers = emptyList(),
        isWorkingCopy = isWorkingCopy,
        hasConflict = false,
        isEmpty = true,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = immutable
    )

    @Test
    fun `mutable non-working-copy entry is editable`() {
        val e = entry(isWorkingCopy = false, immutable = false)

        val target = editableEntry(e)

        target.shouldNotBeNull()
        target shouldBe e
    }

    @Test
    fun `working copy entry is not editable`() {
        editableEntry(entry(isWorkingCopy = true)).shouldBeNull()
    }

    @Test
    fun `immutable entry is not editable`() {
        editableEntry(entry(immutable = true)).shouldBeNull()
    }

    @Test
    fun `no selection is not editable`() {
        editableEntry(null).shouldBeNull()
    }
}
