package `in`.kkkev.jjidea.ui.statusbar

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.statusbar.JujutsuWorkingCopySwitcher.SwitchItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuWorkingCopySwitcherTest {
    private val repo = mockk<JujutsuRepository>()

    private fun changeId(prefix: String) = ChangeId("${prefix}long", prefix, 0)
    private fun commitId(prefix: String) = CommitId("${prefix}commit", prefix)

    private fun makeEntry(
        idPrefix: String,
        description: String = "",
        isWorkingCopy: Boolean = false,
        immutable: Boolean = false,
        bookmarks: List<Bookmark> = emptyList()
    ) = LogEntry(
        repo = repo,
        id = changeId(idPrefix),
        commitId = commitId(idPrefix),
        underlyingDescription = description,
        isWorkingCopy = isWorkingCopy,
        immutable = immutable,
        bookmarks = bookmarks
    )

    private fun buildItemList(
        logEntries: List<LogEntry>,
        bookmarks: List<BookmarkItem> = emptyList()
    ) = JujutsuWorkingCopySwitcher.buildItemList(logEntries, bookmarks)

    @Test
    fun `bookmarks appear before log entries`() {
        val localEntry = makeEntry("aa")
        val workEntry = makeEntry("ww", isWorkingCopy = true)
        val bookmarks = listOf(BookmarkItem(Bookmark("my-feature"), changeId("aa")))

        val items = buildItemList(listOf(workEntry, localEntry), bookmarks)

        items[0].shouldBeInstanceOf<SwitchItem.BookmarkEntry>()
        (items[0] as SwitchItem.BookmarkEntry).displayName shouldBe "my-feature"
        items[1].shouldBeInstanceOf<SwitchItem.ChangeEntry>()
    }

    @Test
    fun `working copy entry is excluded from changes section`() {
        val workEntry = makeEntry("ww", isWorkingCopy = true)
        val otherEntry = makeEntry("aa")

        val items = buildItemList(listOf(workEntry, otherEntry))

        val changeEntries = items.filterIsInstance<SwitchItem.ChangeEntry>()
        changeEntries.none { it.entry.isWorkingCopy } shouldBe true
        changeEntries shouldHaveSize 1
        changeEntries[0].entry.id shouldBe changeId("aa")
    }

    @Test
    fun `remote bookmarks are excluded`() {
        val localEntry = makeEntry("aa")
        val bookmarks = listOf(
            BookmarkItem(Bookmark("main"), changeId("aa")),
            BookmarkItem(Bookmark("main@origin"), changeId("aa"))
        )

        val items = buildItemList(listOf(localEntry), bookmarks)

        val bookmarkEntries = items.filterIsInstance<SwitchItem.BookmarkEntry>()
        bookmarkEntries shouldHaveSize 1
        bookmarkEntries[0].displayName shouldBe "main"
    }

    @Test
    fun `bookmark immutability is cross-referenced from log entries`() {
        val immutableEntry = makeEntry("bb", immutable = true)
        val bookmarks = listOf(BookmarkItem(Bookmark("stable"), changeId("bb")))

        val items = buildItemList(listOf(immutableEntry), bookmarks)

        val bookmark = items.filterIsInstance<SwitchItem.BookmarkEntry>().first()
        bookmark.immutable shouldBe true
    }

    @Test
    fun `mutable bookmark is not marked immutable`() {
        val mutableEntry = makeEntry("cc", immutable = false)
        val bookmarks = listOf(BookmarkItem(Bookmark("feature"), changeId("cc")))

        val items = buildItemList(listOf(mutableEntry), bookmarks)

        val bookmark = items.filterIsInstance<SwitchItem.BookmarkEntry>().first()
        bookmark.immutable shouldBe false
    }

    @Test
    fun `change entry display name uses short id and description`() {
        val entry = makeEntry("dd", description = "Fix the bug\n\nMore detail")

        val items = buildItemList(listOf(entry))

        val change = items.filterIsInstance<SwitchItem.ChangeEntry>().first()
        change.displayName shouldBe "${changeId("dd").short} Fix the bug"
    }

    @Test
    fun `change entry with empty description shows fallback display name`() {
        val entry = makeEntry("ee", description = "")

        val items = buildItemList(listOf(entry))

        val change = items.filterIsInstance<SwitchItem.ChangeEntry>().first()
        change.displayName shouldBe "(${changeId("ee").short})"
    }

    @Test
    fun `recent changes are limited to 10`() {
        val entries = (1..15).map { makeEntry("id$it", description = "Change $it") }

        val items = buildItemList(entries)

        items.filterIsInstance<SwitchItem.ChangeEntry>() shouldHaveSize 10
    }

    @Test
    fun `returns empty list when no entries and no bookmarks`() {
        val items = buildItemList(emptyList())

        items.shouldBeEmpty()
    }

    @Test
    fun `bookmark with unknown change id defaults to not immutable`() {
        // Bookmark references a change not in the log
        val bookmarks = listOf(BookmarkItem(Bookmark("orphan"), changeId("zz")))

        val items = buildItemList(emptyList(), bookmarks)

        val bookmark = items.filterIsInstance<SwitchItem.BookmarkEntry>().first()
        bookmark.immutable shouldBe false
    }
}
