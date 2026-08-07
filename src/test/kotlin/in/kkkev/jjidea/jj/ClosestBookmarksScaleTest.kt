package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [closestBookmarks] (jj-idea-l7wd), following contributing.md's
 * "Writing a scale test" recipe (see [RepoLogCacheScaleTest] for another example of the pattern).
 * Uses a plain counting override rather than mockk `every`/`verify` on the call — mockk's matcher
 * machinery can't handle [Revset]'s [Expression] implementation (see [ClosestBookmarksTest]).
 *
 * Complexity claim: [closestBookmarks] issues exactly two `jj log` calls per refresh, each capped
 * (`HEADS_LIMIT` / `DISTANCE_LIMIT` — both small constants) — never a per-commit loop, and
 * independent of total repo size. A stale bookmark far behind `@` costs at most one bounded
 * distance query, not an unbounded walk.
 */
class ClosestBookmarksScaleTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String, bookmarks: List<Bookmark> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(4), null),
        commitId = CommitId(id),
        underlyingDescription = "desc",
        bookmarks = bookmarks
    )

    @Test
    fun `exactly two bounded log calls, regardless of how many bookmarks or commits are involved`() {
        val heads = (0 until 10).map { entry("h$it", listOf(Bookmark("bm$it"))) }
        val between = (0 until 1000).map { entry("d$it") }
        var callCount = 0
        val limitsSeen = mutableListOf<Int?>()
        val service = object : LogService by mockk(relaxed = true) {
            override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
                callCount++
                limitsSeen.add(limit)
                return if (callCount == 1) Result.success(heads) else Result.success(between)
            }
        }

        service.closestBookmarks(WorkingCopy)

        callCount shouldBe 2
        limitsSeen shouldBe listOf(10, 1000)
    }
}
