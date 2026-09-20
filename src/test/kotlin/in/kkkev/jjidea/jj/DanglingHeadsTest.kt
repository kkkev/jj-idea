package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [danglingHeads] — the "which visible heads have no bookmark at all" query behind the
 * bookmarks panel's "Unbookmarked heads" category (jj-idea-lig7, GitHub #107). Each test provides
 * a fake [LogService] rather than mocking [getLogBasic]'s `Revset` parameter directly: mockk's
 * matcher machinery can't handle [Expression] (see [ClosestBookmarksTest]).
 */
class DanglingHeadsTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String, bookmarks: List<Bookmark> = emptyList(), isWorkingCopy: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(4), null),
        commitId = CommitId(id),
        underlyingDescription = "desc",
        bookmarks = bookmarks,
        isWorkingCopy = isWorkingCopy
    )

    /** Answers [getLogBasic] calls in order, regardless of the revset requested. */
    private class FakeLogService(private val calls: List<Result<List<LogEntry>>>) : LogService by mockk(
        relaxed = true
    ) {
        val revsets = mutableListOf<Revset>()
        val limits = mutableListOf<Int?>()

        override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
            val result = calls[revsets.size]
            revsets.add(revset)
            limits.add(limit)
            return result
        }
    }

    @Test
    fun `no dangling heads returns empty list`() {
        val service = FakeLogService(listOf(Result.success(emptyList())))

        service.danglingHeads().shouldBeEmpty()
    }

    @Test
    fun `heads query failure returns empty list, not an error`() {
        val service = FakeLogService(listOf(Result.failure(RuntimeException("boom"))))

        service.danglingHeads().shouldBeEmpty()
    }

    @Test
    fun `the working copy head is excluded even if it has no bookmark`() {
        val service = FakeLogService(
            listOf(Result.success(listOf(entry("h1", isWorkingCopy = true))))
        )

        service.danglingHeads().shouldBeEmpty()
    }

    @Test
    fun `a dangling head with an ancestor bookmark reports its closestBookmarks result`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1"))), // the dangling-heads query itself
                Result.success(listOf(entry("m1", listOf(Bookmark("main"))))), // closestBookmarks' heads query
                Result.success(listOf(entry("d1"))) // closestBookmarks' distance query
            )
        )

        val result = service.danglingHeads()

        result shouldBe listOf(
            DanglingHead(ChangeId("h1", "h1", null), ClosestBookmarks(listOf(BookmarkName("main")), 1, false))
        )
    }

    @Test
    fun `a dangling head with no ancestor bookmark at all keeps a null closest`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1"))), // the dangling-heads query itself
                Result.success(emptyList()) // closestBookmarks' heads query finds nothing
            )
        )

        val result = service.danglingHeads()

        result shouldBe listOf(DanglingHead(ChangeId("h1", "h1", null), null))
    }

    @Test
    fun `limit is passed through to the heads query`() {
        val service = FakeLogService(listOf(Result.success(emptyList())))

        service.danglingHeads(limit = 3)

        service.revsets shouldBe listOf(Expression("heads(all()) ~ (bookmarks() | remote_bookmarks())"))
        service.limits shouldBe listOf(3)
    }
}
