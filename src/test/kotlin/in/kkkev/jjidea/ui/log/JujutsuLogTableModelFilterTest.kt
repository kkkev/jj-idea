package `in`.kkkev.jjidea.ui.log

import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuLogTableModel filtering functionality.
 *
 * The table model supports multiple filter types:
 * - Text filter (description, change ID, author name/email)
 * - Author filter (by email)
 * - Bookmark filter (by change ID)
 * - Date filter (commits after a cutoff)
 */
class JujutsuLogTableModelFilterTest {
    private lateinit var model: JujutsuLogTableModel
    private val alice = VcsUserImpl("Alice", "alice@example.com")
    private val bob = VcsUserImpl("Bob", "bob@example.com")
    private val charlie = VcsUserImpl("Charlie", "charlie@example.com")

    @BeforeEach
    fun setup() {
        model = JujutsuLogTableModel()
    }

    private fun createEntry(
        changeId: String,
        description: String = "Test commit",
        author: VcsUser? = alice,
        timestamp: Instant? = Instant.fromEpochMilliseconds(1000000000L),
        bookmarks: List<Bookmark> = emptyList()
    ) = LogEntry(
        repo = mockk<JujutsuRepository>(),
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = description,
        bookmarks = bookmarks,
        parentIdentifiers = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = false,
        authorTimestamp = timestamp,
        committerTimestamp = null,
        author = author,
        committer = null
    )

    @Nested
    inner class `Text filter` {
        @Test
        fun `no filter shows all entries`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "First commit"),
                    createEntry("def456", "Second commit"),
                    createEntry("ghi789", "Third commit")
                )
            )

            model.rowCount shouldBe 3
        }

        @Test
        fun `filter by description matches`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Add new feature"),
                    createEntry("def456", "Fix bug"),
                    createEntry("ghi789", "Add another feature")
                )
            )

            model.setFilter("feature")

            model.rowCount shouldBe 2
            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "ghi789"
        }

        @Test
        fun `filter by change id matches`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "First"),
                    createEntry("def456", "Second"),
                    createEntry("ghi789", "Third")
                )
            )

            model.setFilter("def")

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "def456"
        }

        @Test
        fun `filter by author name matches`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", bob),
                    createEntry("ghi789", "Commit 3", charlie)
                )
            )

            model.setFilter("Bob")

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "def456"
        }

        @Test
        fun `filter by author email matches`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", bob),
                    createEntry("ghi789", "Commit 3", charlie)
                )
            )

            model.setFilter("charlie@example.com")

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "ghi789"
        }

        @Test
        fun `filter is case insensitive by default`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Add Feature"),
                    createEntry("def456", "fix bug"),
                    createEntry("ghi789", "FEATURE update")
                )
            )

            model.setFilter("FEATURE")

            model.rowCount shouldBe 2
        }

        @Test
        fun `filter is case sensitive when enabled`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Add Feature"),
                    createEntry("def456", "fix bug"),
                    createEntry("ghi789", "feature update")
                )
            )

            model.setFilter("Feature", regex = false, caseSensitive = true)

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `filter with whole words matches word boundaries`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "fix bug"),
                    createEntry("def456", "bugfix applied"),
                    createEntry("ghi789", "debug mode")
                )
            )

            model.setFilter("bug", regex = false, caseSensitive = false, wholeWords = true)

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `empty filter shows all entries`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "First"),
                    createEntry("def456", "Second")
                )
            )

            model.setFilter("")

            model.rowCount shouldBe 2
        }

        @Test
        fun `whitespace-only filter shows all entries`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "First"),
                    createEntry("def456", "Second")
                )
            )

            model.setFilter("   ")

            model.rowCount shouldBe 2
        }
    }

    @Nested
    inner class `Regex filter` {
        @Test
        fun `regex matches pattern`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "feat(ui): add button"),
                    createEntry("def456", "fix(api): handle error"),
                    createEntry("ghi789", "chore: update deps")
                )
            )

            model.setFilter("feat|fix", regex = true)

            model.rowCount shouldBe 2
        }

        @Test
        fun `regex with groups`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "feat(ui): add button"),
                    createEntry("def456", "fix(api): handle error"),
                    createEntry("ghi789", "chore: update deps")
                )
            )

            model.setFilter("\\(.*\\):", regex = true)

            model.rowCount shouldBe 2
        }

        @Test
        fun `invalid regex falls back to literal search`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "test[bracket"),
                    createEntry("def456", "normal commit")
                )
            )

            // Invalid regex - unclosed bracket
            model.setFilter("[bracket", regex = true)

            // Should fall back to literal match
            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `regex respects case sensitivity`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "FEATURE add"),
                    createEntry("def456", "feature update")
                )
            )

            model.setFilter("FEATURE", regex = true, caseSensitive = true)

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }
    }

    @Nested
    inner class `Author filter` {
        @Test
        fun `filter by single author`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", bob),
                    createEntry("ghi789", "Commit 3", alice)
                )
            )

            model.setAuthorFilter(setOf("alice@example.com"))

            model.rowCount shouldBe 2
            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "ghi789"
        }

        @Test
        fun `filter by multiple authors`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", bob),
                    createEntry("ghi789", "Commit 3", charlie)
                )
            )

            model.setAuthorFilter(setOf("alice@example.com", "bob@example.com"))

            model.rowCount shouldBe 2
        }

        @Test
        fun `empty author filter shows all`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", bob)
                )
            )

            model.setAuthorFilter(emptySet())

            model.rowCount shouldBe 2
        }

        @Test
        fun `author filter excludes entries without author`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", author = null)
                )
            )

            model.setAuthorFilter(setOf("alice@example.com"))

            model.rowCount shouldBe 1
        }
    }

    @Nested
    inner class `Bookmark filter` {
        @Test
        fun `filter by bookmark change id`() {
            val entry1 = createEntry("abc123", "Main commit", bookmarks = listOf(Bookmark("main")))
            val entry2 = createEntry("def456", "Feature commit")
            val entry3 = createEntry("ghi789", "Other commit")

            model.setEntries(listOf(entry1, entry2, entry3))

            // Filter to show only the main bookmark and its ancestors
            model.setBookmarkFilter(setOf(ChangeId("abc123", "ab", null)))

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `filter by multiple bookmark change ids`() {
            val entry1 = createEntry("abc123", "Main commit")
            val entry2 = createEntry("def456", "Feature commit")
            val entry3 = createEntry("ghi789", "Other commit")

            model.setEntries(listOf(entry1, entry2, entry3))

            model.setBookmarkFilter(
                setOf(
                    ChangeId("abc123", "ab", null),
                    ChangeId("def456", "de", null)
                )
            )

            model.rowCount shouldBe 2
        }

        @Test
        fun `empty bookmark filter shows all`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1"),
                    createEntry("def456", "Commit 2")
                )
            )

            model.setBookmarkFilter(emptySet())

            model.rowCount shouldBe 2
        }
    }

    @Nested
    inner class `Date filter` {
        @Test
        fun `filter shows commits after cutoff`() {
            val old = Instant.fromEpochMilliseconds(1000000000L)
            val recent = Instant.fromEpochMilliseconds(2000000000L)
            val cutoff = Instant.fromEpochMilliseconds(1500000000L)

            model.setEntries(
                listOf(
                    createEntry("abc123", "Old commit", timestamp = old),
                    createEntry("def456", "Recent commit", timestamp = recent)
                )
            )

            model.setDateFilter(cutoff)

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "def456"
        }

        @Test
        fun `filter includes commits exactly at cutoff`() {
            val timestamp = Instant.fromEpochMilliseconds(1500000000L)

            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit at cutoff", timestamp = timestamp)
                )
            )

            model.setDateFilter(timestamp)

            model.rowCount shouldBe 1
        }

        @Test
        fun `filter excludes commits without timestamp`() {
            model.setEntries(
                listOf(
                    createEntry(
                        "abc123",
                        "Commit with timestamp",
                        timestamp = Instant.fromEpochMilliseconds(2000000000L)
                    ),
                    createEntry("def456", "Commit without timestamp", timestamp = null)
                )
            )

            model.setDateFilter(Instant.fromEpochMilliseconds(1500000000L))

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `null date filter shows all`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Old commit", timestamp = Instant.fromEpochMilliseconds(1000000000L)),
                    createEntry("def456", "Recent commit", timestamp = Instant.fromEpochMilliseconds(2000000000L))
                )
            )

            model.setDateFilter(null)

            model.rowCount shouldBe 2
        }
    }

    @Nested
    inner class `Combined filters` {
        @Test
        fun `text and author filters combine with AND`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Add feature", alice),
                    createEntry("def456", "Add button", bob),
                    createEntry("ghi789", "Fix bug", alice)
                )
            )

            model.setFilter("Add")
            model.setAuthorFilter(setOf("alice@example.com"))

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `all filters combine with AND`() {
            val recent = Instant.fromEpochMilliseconds(2000000000L)
            val old = Instant.fromEpochMilliseconds(1000000000L)

            model.setEntries(
                listOf(
                    createEntry("abc123", "Add feature", alice, timestamp = recent),
                    createEntry("def456", "Add button", bob, timestamp = recent),
                    createEntry("ghi789", "Add widget", alice, timestamp = old)
                )
            )

            model.setFilter("Add")
            model.setAuthorFilter(setOf("alice@example.com"))
            model.setDateFilter(Instant.fromEpochMilliseconds(1500000000L))

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `resetting one filter keeps others`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Add feature", alice),
                    createEntry("def456", "Add button", bob),
                    createEntry("ghi789", "Fix bug", alice)
                )
            )

            model.setFilter("Add")
            model.setAuthorFilter(setOf("alice@example.com"))

            model.rowCount shouldBe 1

            // Reset text filter
            model.setFilter("")

            // Author filter should still be active
            model.rowCount shouldBe 2
        }
    }

    @Nested
    inner class GetAllAuthors {
        @Test
        fun `returns unique sorted authors`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", charlie),
                    createEntry("def456", "Commit 2", alice),
                    createEntry("ghi789", "Commit 3", bob),
                    createEntry("jkl012", "Commit 4", alice)
                )
            )

            val authors = model.getAllAuthors()

            authors shouldContainExactly
                listOf(
                    "alice@example.com",
                    "bob@example.com",
                    "charlie@example.com"
                )
        }

        @Test
        fun `excludes entries without author`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", alice),
                    createEntry("def456", "Commit 2", author = null)
                )
            )

            val authors = model.getAllAuthors()

            authors shouldContainExactly listOf("alice@example.com")
        }
    }

    @Nested
    inner class GetAllBookmarks {
        @Test
        fun `returns unique sorted bookmarks`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1", bookmarks = listOf(Bookmark("main"), Bookmark("develop"))),
                    createEntry("def456", "Commit 2", bookmarks = listOf(Bookmark("feature"))),
                    createEntry("ghi789", "Commit 3", bookmarks = listOf(Bookmark("main")))
                )
            )

            val bookmarks = model.getAllBookmarks()

            bookmarks shouldContainExactly listOf("develop", "feature", "main")
        }

        @Test
        fun `returns empty list when no bookmarks`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Commit 1"),
                    createEntry("def456", "Commit 2")
                )
            )

            val bookmarks = model.getAllBookmarks()

            bookmarks.shouldBeEmpty()
        }
    }

    @Nested
    inner class `Root filter` {
        private lateinit var repo1: JujutsuRepository
        private lateinit var repo2: JujutsuRepository
        private lateinit var repo3: JujutsuRepository

        @BeforeEach
        fun setupRepos() {
            repo1 = mockk<JujutsuRepository> {
                every { displayName } returns "frontend"
            }
            repo2 = mockk<JujutsuRepository> {
                every { displayName } returns "backend"
            }
            repo3 = mockk<JujutsuRepository> {
                every { displayName } returns "shared"
            }
        }

        private fun createEntryInRepo(
            changeId: String,
            repo: JujutsuRepository,
            description: String = "Test commit"
        ) = LogEntry(
            repo = repo,
            id = ChangeId(changeId, changeId, null),
            commitId = CommitId("0000000000000000000000000000000000000000"),
            underlyingDescription = description,
            bookmarks = emptyList(),
            parentIdentifiers = emptyList(),
            isWorkingCopy = false,
            hasConflict = false,
            isEmpty = false,
            authorTimestamp = Instant.fromEpochMilliseconds(1000000000L),
            committerTimestamp = null,
            author = alice,
            committer = null
        )

        @Test
        fun `filter by single root`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo1, "Frontend commit 1"),
                    createEntryInRepo("def456", repo2, "Backend commit"),
                    createEntryInRepo("ghi789", repo1, "Frontend commit 2")
                )
            )

            model.setRootFilter(setOf(repo1))

            model.rowCount shouldBe 2
            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "ghi789"
        }

        @Test
        fun `filter by multiple roots`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo1, "Frontend commit"),
                    createEntryInRepo("def456", repo2, "Backend commit"),
                    createEntryInRepo("ghi789", repo3, "Shared commit")
                )
            )

            model.setRootFilter(setOf(repo1, repo2))

            model.rowCount shouldBe 2
            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "def456"
        }

        @Test
        fun `empty root filter shows all entries`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo1, "Frontend commit"),
                    createEntryInRepo("def456", repo2, "Backend commit"),
                    createEntryInRepo("ghi789", repo3, "Shared commit")
                )
            )

            model.setRootFilter(emptySet())

            model.rowCount shouldBe 3
        }

        @Test
        fun `getAllRoots returns unique roots`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo1, "Frontend commit 1"),
                    createEntryInRepo("def456", repo2, "Backend commit"),
                    createEntryInRepo("ghi789", repo1, "Frontend commit 2"),
                    createEntryInRepo("jkl012", repo3, "Shared commit")
                )
            )

            val roots = model.getAllRoots()

            roots.size shouldBe 3
            roots.toSet() shouldBe setOf(repo1, repo2, repo3)
        }

        @Test
        fun `getAllRoots preserves order of first occurrence`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo2, "Backend commit"),
                    createEntryInRepo("def456", repo1, "Frontend commit"),
                    createEntryInRepo("ghi789", repo2, "Backend commit 2")
                )
            )

            val roots = model.getAllRoots()

            // distinct() preserves order of first occurrence
            roots shouldContainExactly listOf(repo2, repo1)
        }

        @Test
        fun `root filter combines with other filters`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo1, "Add feature"),
                    createEntryInRepo("def456", repo1, "Fix bug"),
                    createEntryInRepo("ghi789", repo2, "Add widget")
                )
            )

            model.setRootFilter(setOf(repo1))
            model.setFilter("Add")

            model.rowCount shouldBe 1
            model.getEntry(0)?.id?.short shouldBe "abc123"
        }

        @Test
        fun `clearing root filter restores filtered entries`() {
            model.setEntries(
                listOf(
                    createEntryInRepo("abc123", repo1, "Frontend commit"),
                    createEntryInRepo("def456", repo2, "Backend commit")
                )
            )

            model.setRootFilter(setOf(repo1))
            model.rowCount shouldBe 1

            model.setRootFilter(emptySet())
            model.rowCount shouldBe 2
        }
    }

    @Nested
    inner class `Sorting` {
        @Test
        fun `entries maintain insertion order when no explicit sort`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "First"),
                    createEntry("def456", "Second"),
                    createEntry("ghi789", "Third")
                )
            )

            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "def456"
            model.getEntry(2)?.id?.short shouldBe "ghi789"
        }

        @Test
        fun `filtered entries maintain relative order`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "Feature one"),
                    createEntry("def456", "Bug fix"),
                    createEntry("ghi789", "Feature two"),
                    createEntry("jkl012", "Another fix")
                )
            )

            model.setFilter("Feature")

            // Filtered entries should maintain their relative order
            model.rowCount shouldBe 2
            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "ghi789"
        }

        @Test
        fun `appendEntries preserves existing order and adds at end`() {
            model.setEntries(
                listOf(
                    createEntry("abc123", "First"),
                    createEntry("def456", "Second")
                )
            )

            model.appendEntries(
                listOf(
                    createEntry("ghi789", "Third"),
                    createEntry("jkl012", "Fourth")
                )
            )

            model.rowCount shouldBe 4
            model.getEntry(0)?.id?.short shouldBe "abc123"
            model.getEntry(1)?.id?.short shouldBe "def456"
            model.getEntry(2)?.id?.short shouldBe "ghi789"
            model.getEntry(3)?.id?.short shouldBe "jkl012"
        }
    }
}
