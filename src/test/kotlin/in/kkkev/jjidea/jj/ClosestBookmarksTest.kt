package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [closestBookmarks] — the "which bookmark would `jj bookmark advance` move, and how far
 * behind is it" query behind the bookmark widget's "name +N" indicator and the Advance action
 * (jj-idea-l7wd). Each test provides a fake [LogService] rather than mocking [getLogBasic]'s
 * `Revset` parameter directly: mockk's matcher machinery can't handle [Expression], a sealed
 * interface backed by an inline value class (see [in.kkkev.jjidea.actions.bookmark.MoveBookmarkDirectionTest]
 * for the same workaround).
 */
class ClosestBookmarksTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String, bookmarks: List<Bookmark> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(4), null),
        commitId = CommitId(id),
        underlyingDescription = "desc",
        bookmarks = bookmarks
    )

    /** First [getLogBasic] call answers the "closest bookmark names" query, second the "distance" query. */
    private class FakeLogService(private val calls: List<Result<List<LogEntry>>>) : LogService by mockk(
        relaxed = true
    ) {
        val revsets = mutableListOf<Revset>()

        override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
            val result = calls[revsets.size]
            revsets.add(revset)
            return result
        }
    }

    @Test
    fun `no ancestor bookmark returns null`() {
        val service = FakeLogService(listOf(Result.success(emptyList())))

        service.closestBookmarks(WorkingCopy).shouldBeNull()
    }

    @Test
    fun `single closest bookmark with distance`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1", listOf(Bookmark("main"))))),
                Result.success(listOf(entry("d1"), entry("d2"), entry("d3")))
            )
        )

        val result = service.closestBookmarks(WorkingCopy)

        result shouldBe ClosestBookmarks(listOf(BookmarkName("main")), distance = 3, distanceCapped = false)
    }

    @Test
    fun `bookmark exactly on the target has zero distance`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1", listOf(Bookmark("main"))))),
                Result.success(emptyList())
            )
        )

        service.closestBookmarks(WorkingCopy) shouldBe
            ClosestBookmarks(listOf(BookmarkName("main")), distance = 0, distanceCapped = false)
    }

    @Test
    fun `multiple equidistant bookmarks are collected and deduplicated`() {
        val service = FakeLogService(
            listOf(
                Result.success(
                    listOf(
                        entry("h1", listOf(Bookmark("main"), Bookmark("main"))),
                        entry("h2", listOf(Bookmark("feature")))
                    )
                ),
                Result.success(listOf(entry("d1")))
            )
        )

        val result = service.closestBookmarks(WorkingCopy)

        result?.names shouldBe listOf(BookmarkName("main"), BookmarkName("feature"))
    }

    @Test
    fun `remote bookmarks on the head commit are excluded`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1", listOf(Bookmark("main@origin"))))),
                Result.success(emptyList())
            )
        )

        service.closestBookmarks(WorkingCopy).shouldBeNull()
    }

    @Test
    fun `distance at the cap is reported as capped`() {
        val between = List(1000) { entry("d$it") }
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1", listOf(Bookmark("main"))))),
                Result.success(between)
            )
        )

        val result = service.closestBookmarks(WorkingCopy)

        result shouldBe ClosestBookmarks(listOf(BookmarkName("main")), distance = 1000, distanceCapped = true)
    }

    @Test
    fun `heads query failure returns null`() {
        val service = FakeLogService(listOf(Result.failure(RuntimeException("boom"))))

        service.closestBookmarks(WorkingCopy).shouldBeNull()
    }

    @Test
    fun `distance query failure returns null`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1", listOf(Bookmark("main"))))),
                Result.failure(RuntimeException("boom"))
            )
        )

        service.closestBookmarks(WorkingCopy).shouldBeNull()
    }

    @Test
    fun `queries the closest-ancestor-bookmark revset, then the between revset`() {
        val service = FakeLogService(
            listOf(
                Result.success(listOf(entry("h1", listOf(Bookmark("main"))))),
                Result.success(emptyList())
            )
        )

        service.closestBookmarks(WorkingCopy)

        service.revsets.map { it.toString() } shouldBe listOf(
            "heads(::@ & bookmarks())",
            "heads(::@ & bookmarks())..@"
        )
    }
}
