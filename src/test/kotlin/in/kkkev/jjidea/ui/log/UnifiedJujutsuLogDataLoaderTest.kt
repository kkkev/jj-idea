package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
            parentIds = emptyList(),
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

    @Nested
    inner class `fetchSearchResults` {
        private val defaultRevset = Expression("description(substring-i:\"x\")")

        private fun repoWithService(
            result: Result<List<LogEntry>>,
            revset: Expression = defaultRevset,
            limit: Int = 100
        ): Pair<JujutsuRepository, LogService> {
            val repo = mockk<JujutsuRepository>()
            val logService = mockk<LogService>()
            every {
                logService.getLog(revset = revset, filePaths = emptyList(), limit = limit, quiet = true)
            } returns result
            every { repo.logService } returns logService
            return repo to logService
        }

        private fun entryFor(repo: JujutsuRepository) = LogEntry(
            repo = repo,
            id = ChangeId("abc", "abc", null),
            commitId = CommitId("0000000000000000000000000000000000000000"),
            underlyingDescription = "test"
        )

        @Test
        fun `issues exactly one getLog call per repo, passing the revset, limit, and quiet=true`() {
            val revset = Expression("description(substring-i:\"test\")")
            val (repo, logService) = repoWithService(
                Result.success(listOf(entryFor(mockk()))),
                revset = revset,
                limit = 250
            )

            fetchSearchResults(listOf(repo), revset) { 250 }

            verify(exactly = 1) {
                logService.getLog(revset = revset, filePaths = emptyList(), limit = 250, quiet = true)
            }
        }

        @Test
        fun `drops repos whose fetch fails without affecting other repos`() {
            val (failing, _) = repoWithService(Result.failure(RuntimeException("boom")))
            val (ok, _) = repoWithService(Result.success(listOf(entryFor(mockk()))))

            val result = fetchSearchResults(listOf(failing, ok), defaultRevset) { 100 }

            result.keys shouldBe setOf(ok)
        }

        @Test
        fun `omits repos with empty results`() {
            val (repo, _) = repoWithService(Result.success(emptyList()))

            val result = fetchSearchResults(listOf(repo), defaultRevset) { 100 }

            result.shouldContainExactly(emptyMap())
        }

        @Test
        fun `uses the per-repo limit provided by limitFor`() {
            val (repo, logService) = repoWithService(Result.success(listOf(entryFor(mockk()))), limit = 42)

            fetchSearchResults(listOf(repo), defaultRevset) { 42 }

            verify(exactly = 1) {
                logService.getLog(revset = defaultRevset, filePaths = emptyList(), limit = 42, quiet = true)
            }
        }
    }
}
