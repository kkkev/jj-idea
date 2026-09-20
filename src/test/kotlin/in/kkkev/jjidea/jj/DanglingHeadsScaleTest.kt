package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [danglingHeads] (jj-idea-lig7), following contributing.md's
 * "Writing a scale test" recipe (see [ClosestBookmarksScaleTest] for the sibling this extends).
 *
 * Complexity claim: `danglingHeads()` issues exactly `1 + 2 * min(headCount, limit)` bounded `jj
 * log` calls per refresh — one for the dangling heads themselves, then a full [closestBookmarks]
 * lookup (two more bounded calls) per dangling head found, capped at [limit] regardless of how
 * many heads or commits exist in the repo. Never a per-commit loop.
 */
class DanglingHeadsScaleTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String, bookmarks: List<Bookmark> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id.take(4), null),
        commitId = CommitId(id),
        underlyingDescription = "desc",
        bookmarks = bookmarks
    )

    @Test
    fun `exactly 1 + 2N bounded log calls for N dangling heads, regardless of repo size`() {
        val limit = 5
        val heads = (0 until limit).map { entry("h$it") }
        val closestHeads = listOf(entry("m1", listOf(Bookmark("main"))))
        val between = (0 until 1000).map { entry("d$it") }
        var callCount = 0
        val limitsSeen = mutableListOf<Int?>()
        val service = object : LogService by mockk(relaxed = true) {
            override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
                callCount++
                limitsSeen.add(limit)
                return when {
                    callCount == 1 -> Result.success(heads) // the dangling-heads query
                    callCount % 2 == 0 -> Result.success(closestHeads) // per-head closestBookmarks heads query
                    else -> Result.success(between) // per-head closestBookmarks distance query
                }
            }
        }

        val result = service.danglingHeads(limit = limit)

        result.size shouldBe limit
        callCount shouldBe 1 + 2 * limit
        // Every limit passed is one of the three bounded constants, never repo-size-dependent.
        limitsSeen.toSet().size shouldBeLessThanOrEqual 3
        limitsSeen shouldContain limit
    }
}
