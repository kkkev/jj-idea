package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [withDerivedDivergence] (jj-idea-ks5k), following
 * contributing.md's "Writing a scale test" recipe (see `DanglingHeadsScaleTest` for the sibling
 * this mirrors).
 *
 * Complexity claim: zero `jj log` calls when no local bookmark is conflicted, regardless of how
 * many bookmarks the repo has - the exact-count path only ever runs over conflicted local
 * bookmarks. When bookmarks are conflicted, calls are bounded by `2 * min(conflicted bookmarks,
 * DIVERGENCE_BOOKMARKS_LIMIT) * tracked remotes per bookmark`, never by total repo size.
 */
class BookmarkDivergenceScaleTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId(id.padEnd(40, '0')),
        underlyingDescription = "desc"
    )

    @Test
    fun `zero jj log calls for a large repo with no conflicted bookmark`() {
        var callCount = 0
        val service = object : LogService by mockk(relaxed = true) {
            override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
                callCount++
                return Result.success(emptyList())
            }
        }
        val bookmarkCount = 5_000
        val items = (0 until bookmarkCount).flatMap { i ->
            listOf(
                BookmarkItem(Bookmark("main$i"), listOf(ChangeId("m$i", "m$i", null))),
                BookmarkItem(Bookmark("main$i@origin", behindCount = 1), listOf(ChangeId("m$i", "m$i", null)))
            )
        }

        val result = service.withDerivedDivergence(items)

        result.size shouldBe items.size
        callCount shouldBe 0
    }

    @Test
    fun `conflicted bookmarks are capped at DIVERGENCE_BOOKMARKS_LIMIT, independent of how many exist`() {
        var callCount = 0
        val service = object : LogService by mockk(relaxed = true) {
            override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
                callCount++
                return Result.success(listOf(entry("x")))
            }
        }
        val conflictedCount = 200
        val items = (0 until conflictedCount).flatMap { i ->
            listOf(
                BookmarkItem(
                    Bookmark("main$i", conflict = true),
                    listOf(ChangeId("a$i", "a$i", null), ChangeId("b$i", "b$i", null))
                ),
                BookmarkItem(Bookmark("main$i@origin"), listOf(ChangeId("b$i", "b$i", null)))
            )
        }

        service.withDerivedDivergence(items)

        // 2 calls (ahead + behind) per derived bookmark, capped at DIVERGENCE_BOOKMARKS_LIMIT (10)
        // regardless of the 200 conflicted bookmarks actually present - never O(repo size).
        callCount shouldBe 2 * 10
    }
}
