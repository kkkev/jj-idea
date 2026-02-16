package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for context menu action availability logic.
 *
 * Since [JujutsuLogContextMenuActions.createActionGroup] requires the IntelliJ Application to be
 * initialized (for ActionManager), these tests verify the business logic for determining
 * what data gets passed to the action factories.
 *
 * The logic tested here corresponds to the filtering in createActionGroup:
 * - `entry?.takeIf { !it.isWorkingCopy && !it.immutable }` for edit
 * - `entry?.takeUnless { it.immutable }` for describe
 * - `entry?.takeIf { !it.immutable }` for abandon
 * - `entries.map { it.repo }.toSet().singleOrNull()` for new change
 */
class JujutsuLogContextMenuActionsTest {
    private lateinit var repo1: JujutsuRepository
    private lateinit var repo2: JujutsuRepository

    @BeforeEach
    fun setup() {
        repo1 = mockk<JujutsuRepository> {
            every { commandExecutor } returns mockk()
            every { displayName } returns "repo1"
        }
        repo2 = mockk<JujutsuRepository> {
            every { commandExecutor } returns mockk()
            every { displayName } returns "repo2"
        }
    }

    private fun createEntry(
        changeId: String = "abc123",
        isWorkingCopy: Boolean = false,
        immutable: Boolean = false,
        isEmpty: Boolean = true,
        repo: JujutsuRepository = repo1
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId.take(2), null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = "Test commit",
        bookmarks = emptyList(),
        parentIdentifiers = emptyList(),
        isWorkingCopy = isWorkingCopy,
        hasConflict = false,
        isEmpty = isEmpty,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = immutable
    )

    @Nested
    inner class `Copy ID action availability` {
        @Test
        fun `single entry provides its ID`() {
            val entries = listOf(createEntry("abc123"))

            val entry = entries.singleOrNull()

            entry.shouldNotBeNull()
            entry.id.full shouldBe "abc123"
        }

        @Test
        fun `multiple entries return null for single selection`() {
            val entries = listOf(createEntry("abc123"), createEntry("def456"))

            val entry = entries.singleOrNull()

            entry.shouldBeNull()
        }

        @Test
        fun `empty selection returns null`() {
            val entries = emptyList<LogEntry>()

            val entry = entries.singleOrNull()

            entry.shouldBeNull()
        }
    }

    @Nested
    inner class `Edit action availability` {
        @Test
        fun `working copy entry filtered out`() {
            val entry = createEntry("abc123", isWorkingCopy = true)

            val editTarget = entry.takeIf { !it.isWorkingCopy && !it.immutable }

            editTarget.shouldBeNull()
        }

        @Test
        fun `immutable entry filtered out`() {
            val entry = createEntry("abc123", immutable = true)

            val editTarget = entry.takeIf { !it.isWorkingCopy && !it.immutable }

            editTarget.shouldBeNull()
        }

        @Test
        fun `working copy and immutable entry filtered out`() {
            val entry = createEntry("abc123", isWorkingCopy = true, immutable = true)

            val editTarget = entry.takeIf { !it.isWorkingCopy && !it.immutable }

            editTarget.shouldBeNull()
        }

        @Test
        fun `mutable non-working-copy entry passes through`() {
            val entry = createEntry("abc123", isWorkingCopy = false, immutable = false)

            val editTarget = entry.takeIf { !it.isWorkingCopy && !it.immutable }

            editTarget.shouldNotBeNull()
            editTarget.id.full shouldBe "abc123"
        }
    }

    @Nested
    inner class `Describe action availability` {
        @Test
        fun `immutable entry filtered out`() {
            val entry = createEntry("abc123", immutable = true)

            val describeTarget = entry.takeUnless { it.immutable }

            describeTarget.shouldBeNull()
        }

        @Test
        fun `mutable entry passes through`() {
            val entry = createEntry("abc123", immutable = false)

            val describeTarget = entry.takeUnless { it.immutable }

            describeTarget.shouldNotBeNull()
        }

        @Test
        fun `working copy mutable entry passes through`() {
            // Describe should work on working copy (unlike edit)
            val entry = createEntry("abc123", isWorkingCopy = true, immutable = false)

            val describeTarget = entry.takeUnless { it.immutable }

            describeTarget.shouldNotBeNull()
            describeTarget.isWorkingCopy shouldBe true
        }
    }

    @Nested
    inner class `Abandon action availability` {
        @Test
        fun `immutable entry filtered out`() {
            val entry = createEntry("abc123", immutable = true)

            val abandonTarget = entry.takeIf { !it.immutable }

            abandonTarget.shouldBeNull()
        }

        @Test
        fun `mutable entry passes through`() {
            val entry = createEntry("abc123", immutable = false)

            val abandonTarget = entry.takeIf { !it.immutable }

            abandonTarget.shouldNotBeNull()
        }

        @Test
        fun `working copy mutable entry passes through`() {
            // Abandon should work on working copy
            val entry = createEntry("abc123", isWorkingCopy = true, immutable = false)

            val abandonTarget = entry.takeIf { !it.immutable }

            abandonTarget.shouldNotBeNull()
            abandonTarget.isWorkingCopy shouldBe true
        }
    }

    @Nested
    inner class `New change action availability` {
        @Test
        fun `single root from multiple entries returns repo`() {
            val entries = listOf(
                createEntry("abc123", repo = repo1),
                createEntry("def456", repo = repo1)
            )

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            uniqueRoot.shouldNotBeNull()
            uniqueRoot shouldBe repo1
        }

        @Test
        fun `multiple roots from entries returns null`() {
            val entries = listOf(
                createEntry("abc123", repo = repo1),
                createEntry("def456", repo = repo2)
            )

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            uniqueRoot.shouldBeNull()
        }

        @Test
        fun `single entry returns its repo`() {
            val entries = listOf(createEntry("abc123", repo = repo1))

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            uniqueRoot.shouldNotBeNull()
            uniqueRoot shouldBe repo1
        }

        @Test
        fun `empty selection returns null`() {
            val entries = emptyList<LogEntry>()

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            uniqueRoot.shouldBeNull()
        }

        @Test
        fun `multiple entries same repo collects all change IDs`() {
            val entries = listOf(
                createEntry("abc123", repo = repo1),
                createEntry("def456", repo = repo1),
                createEntry("ghi789", repo = repo1)
            )

            val changeIds = entries.map { it.id }

            changeIds.size shouldBe 3
            changeIds.map { it.full } shouldBe listOf("abc123", "def456", "ghi789")
        }
    }

    @Nested
    inner class `Rebase action availability` {
        @Test
        fun `immutable entries filtered out`() {
            val entries = listOf(
                createEntry("abc123", immutable = true),
                createEntry("def456", immutable = false)
            )

            val rebaseEntries = entries.filter { !it.immutable }

            rebaseEntries.size shouldBe 1
            rebaseEntries.first().id.full shouldBe "def456"
        }

        @Test
        fun `all immutable entries results in empty list`() {
            val entries = listOf(
                createEntry("abc123", immutable = true),
                createEntry("def456", immutable = true)
            )

            val rebaseEntries = entries.filter { !it.immutable }

            rebaseEntries shouldBe emptyList()
        }

        @Test
        fun `mutable entries pass through`() {
            val entries = listOf(
                createEntry("abc123", immutable = false),
                createEntry("def456", immutable = false)
            )

            val rebaseEntries = entries.filter { !it.immutable }

            rebaseEntries.size shouldBe 2
        }

        @Test
        fun `working copy mutable entry passes through`() {
            val entries = listOf(createEntry("abc123", isWorkingCopy = true, immutable = false))

            val rebaseEntries = entries.filter { !it.immutable }

            rebaseEntries.size shouldBe 1
            rebaseEntries.first().isWorkingCopy shouldBe true
        }

        @Test
        fun `requires single root for repo`() {
            val entries = listOf(
                createEntry("abc123", repo = repo1),
                createEntry("def456", repo = repo2)
            )

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            uniqueRoot.shouldBeNull()
        }

        @Test
        fun `single root with multiple entries returns repo`() {
            val entries = listOf(
                createEntry("abc123", repo = repo1),
                createEntry("def456", repo = repo1)
            )

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            uniqueRoot.shouldNotBeNull()
            uniqueRoot shouldBe repo1
        }

        @Test
        fun `empty selection results in empty rebase entries`() {
            val entries = emptyList<LogEntry>()

            val rebaseEntries = entries.filter { !it.immutable }

            rebaseEntries shouldBe emptyList()
        }

        @Test
        fun `all immutable same root disables rebase`() {
            val entries = listOf(
                createEntry("abc123", immutable = true, repo = repo1),
                createEntry("def456", immutable = true, repo = repo1)
            )

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()
            val mutableEntries = entries.filter { !it.immutable }
            val rebaseRepo = uniqueRoot?.takeIf { mutableEntries.isNotEmpty() }

            uniqueRoot shouldBe repo1
            mutableEntries shouldBe emptyList()
            rebaseRepo.shouldBeNull()
        }

        @Test
        fun `mixed immutable and mutable same root enables rebase`() {
            val entries = listOf(
                createEntry("abc123", immutable = true, repo = repo1),
                createEntry("def456", immutable = false, repo = repo1)
            )

            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()
            val mutableEntries = entries.filter { !it.immutable }
            val rebaseRepo = uniqueRoot?.takeIf { mutableEntries.isNotEmpty() }

            rebaseRepo shouldBe repo1
            mutableEntries.size shouldBe 1
        }
    }

    @Nested
    inner class `Entry property combinations` {
        @Test
        fun `entry with conflict is still mutable`() {
            val entry = LogEntry(
                repo = repo1,
                id = ChangeId("abc123", "ab", null),
                commitId = CommitId("0000000000000000000000000000000000000000"),
                underlyingDescription = "Conflict commit",
                bookmarks = emptyList(),
                parentIdentifiers = emptyList(),
                isWorkingCopy = true,
                hasConflict = true, // has conflict
                isEmpty = false,
                authorTimestamp = null,
                committerTimestamp = null,
                author = null,
                committer = null,
                immutable = false // but still mutable
            )

            // Should still be describable and abandonable
            entry.takeUnless { it.immutable }.shouldNotBeNull()
            entry.takeIf { !it.immutable }.shouldNotBeNull()
        }

        @Test
        fun `empty entry is still mutable`() {
            val entry = createEntry("abc123", isEmpty = true, immutable = false)

            // Empty commits can still be described/abandoned
            entry.takeUnless { it.immutable }.shouldNotBeNull()
            entry.takeIf { !it.immutable }.shouldNotBeNull()
        }
    }
}
