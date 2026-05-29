package `in`.kkkev.jjidea.ui.statusbar

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.RevisionChoice
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
    fun `actionRevision - change returns commit id`() {
        val entry = makeEntry("aa")
        val item = RevisionChoice.Change(entry)
        JujutsuWorkingCopySwitcher.actionRevision(item) shouldBe entry.commitId
    }

    @Test
    fun `actionRevision - bookmark returns bookmark`() {
        val bookmark = Bookmark("main")
        val item = RevisionChoice.Bookmark(BookmarkItem(bookmark, changeId("bb")))
        JujutsuWorkingCopySwitcher.actionRevision(item) shouldBe bookmark
    }

    @Test
    fun `immutableHint - change id in set returns true`() {
        val entry = makeEntry("cc")
        val item = RevisionChoice.Change(entry)
        JujutsuWorkingCopySwitcher.immutableHint(item, setOf(entry.id.full)) shouldBe true
    }

    @Test
    fun `immutableHint - change id not in set returns false`() {
        val item = RevisionChoice.Change(makeEntry("dd"))
        JujutsuWorkingCopySwitcher.immutableHint(item, emptySet()) shouldBe false
    }

    @Test
    fun `immutableHint - bookmark change id in set returns true`() {
        val id = changeId("ee")
        val item = RevisionChoice.Bookmark(BookmarkItem(Bookmark("feature"), id))
        JujutsuWorkingCopySwitcher.immutableHint(item, setOf(id.full)) shouldBe true
    }

    @Test
    fun `immutableHint - bookmark change id not in set returns false`() {
        val item = RevisionChoice.Bookmark(BookmarkItem(Bookmark("feature"), changeId("ff")))
        JujutsuWorkingCopySwitcher.immutableHint(item, emptySet()) shouldBe false
    }

    @Test
    fun `immutableHint - bookmark with null change id returns false`() {
        val item = RevisionChoice.Bookmark(BookmarkItem(Bookmark("orphan"), null))
        JujutsuWorkingCopySwitcher.immutableHint(item, setOf("anything")) shouldBe false
    }
}
