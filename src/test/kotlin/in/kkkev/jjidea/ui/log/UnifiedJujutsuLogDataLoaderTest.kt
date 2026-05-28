package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UnifiedJujutsuLogDataLoaderTest {
    @Nested
    inner class `enrichWithDeletedBookmarks` {
        private val repo = mockk<JujutsuRepository>()

        private fun entry(vararg bookmarks: Bookmark) = LogEntry(
            repo = repo,
            id = ChangeId("abc", "abc", null),
            commitId = CommitId("0000000000000000000000000000000000000000"),
            underlyingDescription = "test",
            bookmarks = bookmarks.toList(),
            parentIdentifiers = emptyList(),
            isWorkingCopy = false,
            hasConflict = false,
            isEmpty = false
        )

        @Test
        fun `no-op when deleted names set is empty`() {
            val bm = Bookmark("foo@origin", tracked = true, aheadCount = 99)
            val e = entry(bm)
            enrichWithDeletedBookmarks(e, emptySet()) shouldBe e
        }

        @Test
        fun `no-op when entry has no matching remote bookmarks`() {
            val bm = Bookmark("bar@origin", tracked = true, aheadCount = 99)
            val e = entry(bm)
            enrichWithDeletedBookmarks(e, setOf("foo")) shouldBe e
        }

        @Test
        fun `injects deleted local and zeros remote counts`() {
            val remote = Bookmark("foo@origin", tracked = true, aheadCount = 42, behindCount = 0)
            val e = entry(remote)
            val result = enrichWithDeletedBookmarks(e, setOf("foo"))
            val bookmarks = result.bookmarks
            bookmarks.shouldContainExactlyInAnyOrder(
                Bookmark("foo", tracked = true, deleted = true),
                Bookmark("foo@origin", tracked = true, aheadCount = 0, behindCount = 0)
            )
        }

        @Test
        fun `preserves unrelated bookmarks`() {
            val remote = Bookmark("foo@origin", tracked = true, aheadCount = 99)
            val unrelated = Bookmark("main@origin", tracked = true, aheadCount = 0)
            val local = Bookmark("main")
            val e = entry(remote, unrelated, local)
            val result = enrichWithDeletedBookmarks(e, setOf("foo"))
            val bookmarks = result.bookmarks
            bookmarks.shouldContainExactlyInAnyOrder(
                Bookmark("foo", tracked = true, deleted = true),
                Bookmark("foo@origin", tracked = true, aheadCount = 0, behindCount = 0),
                unrelated,
                local
            )
        }

        @Test
        fun `handles multiple deleted bookmarks on same entry`() {
            val remote1 = Bookmark("foo@origin", tracked = true, aheadCount = 50)
            val remote2 = Bookmark("bar@origin", tracked = true, aheadCount = 30)
            val e = entry(remote1, remote2)
            val result = enrichWithDeletedBookmarks(e, setOf("foo", "bar"))
            val bookmarks = result.bookmarks
            bookmarks.shouldContainExactlyInAnyOrder(
                Bookmark("foo", tracked = true, deleted = true),
                Bookmark("bar", tracked = true, deleted = true),
                Bookmark("foo@origin", tracked = true, aheadCount = 0, behindCount = 0),
                Bookmark("bar@origin", tracked = true, aheadCount = 0, behindCount = 0)
            )
        }
    }
}
