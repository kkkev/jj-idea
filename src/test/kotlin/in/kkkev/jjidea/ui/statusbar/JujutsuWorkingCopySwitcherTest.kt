package `in`.kkkev.jjidea.ui.statusbar

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.Filter
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuWorkingCopySwitcherTest {
    private val repo = mockk<JujutsuRepository>()

    private fun changeId(prefix: String) = ChangeId("${prefix}long", prefix, 0)
    private fun commitId(prefix: String) = CommitId("${prefix}commit", prefix)

    private fun makeEntry(idPrefix: String, immutable: Boolean = false) = LogEntry(
        repo = repo,
        id = changeId(idPrefix),
        commitId = commitId(idPrefix),
        underlyingDescription = "",
        immutable = immutable
    )

    @Test
    fun `defaultFilter excludes remote bookmarks`() {
        JujutsuWorkingCopySwitcher.defaultFilter.includeRemote shouldBe false
    }

    @Test
    fun `defaultFilter includes log entries`() {
        JujutsuWorkingCopySwitcher.defaultFilter.includeLogEntries shouldBe true
    }

    @Test
    fun `Filter matches bookmark by name`() {
        val filter = Filter(includeRemote = false, includeLogEntries = false, query = "main")
        val item = BookmarkItem(Bookmark("main"), changeId("aa"))
        filter.matches(item) shouldBe true
    }

    @Test
    fun `Filter excludes deleted bookmarks`() {
        val filter = Filter(includeRemote = false, includeLogEntries = false)
        val item = BookmarkItem(Bookmark("main", deleted = true), changeId("aa"))
        filter.matches(item) shouldBe false
    }

    @Test
    fun `Filter excludes remote bookmarks when includeRemote is false`() {
        val filter = Filter(includeRemote = false, includeLogEntries = false)
        val item = BookmarkItem(Bookmark("main@origin"), null)
        filter.matches(item) shouldBe false
    }

    @Test
    fun `Filter includes remote bookmarks when includeRemote is true`() {
        val filter = Filter(includeRemote = true, includeLogEntries = false)
        val item = BookmarkItem(Bookmark("main@origin"), null)
        filter.matches(item) shouldBe true
    }

    @Test
    fun `Filter matches log entry by description`() {
        val filter = Filter(includeRemote = false, includeLogEntries = true, query = "hello")
        val entry = LogEntry(
            repo = repo,
            id = changeId("aa"),
            commitId = commitId("aa"),
            underlyingDescription = "hello world"
        )
        filter.matches(entry) shouldBe true
    }

    @Test
    fun `Filter excludes log entries when includeLogEntries is false`() {
        val filter = Filter(includeRemote = false, includeLogEntries = false)
        val entry = makeEntry("bb")
        filter.matches(entry) shouldBe false
    }
}
