package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [appendClosestBookmarkStatus] and [appendSummaryAndStatuses]'s `danglingHeadClosest`
 * parameter (jj-idea-uyu9 follow-up): a commit-row tooltip for a dangling head (a visible head
 * with no bookmark on it) now shows the same "[slashed-bookmark icon] N commits ahead of foo"
 * status the bookmarks panel's own "Unbookmarked heads" tooltips show, DRY via one shared phrase
 * builder. Wired up in [in.kkkev.jjidea.ui.log.JujutsuGraphAndDescriptionRenderer] and
 * [in.kkkev.jjidea.ui.log.JujutsuCommitDetailsPanel], both of which resolve the actual
 * [ClosestBookmarks] via the state model - not covered here (platform-level lookup, see
 * `docs/manual-tests.md`), only the pure rendering logic once that value is known.
 */
class DanglingHeadStatusTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val entry = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp"),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "wip"
    )

    @Test
    fun `appendClosestBookmarkStatus uses singular commit for exactly one`() {
        val closest = ClosestBookmarks(listOf(Bookmark("main").name), distance = 1, distanceCapped = false)

        val html = htmlString { appendClosestBookmarkStatus(closest) }

        html shouldContain "1 commit ahead of"
    }

    @Test
    fun `appendClosestBookmarkStatus uses plural commits for more than one`() {
        val closest = ClosestBookmarks(listOf(Bookmark("main").name), distance = 2, distanceCapped = false)

        val html = htmlString { appendClosestBookmarkStatus(closest) }

        html shouldContain "2 commits ahead of"
    }

    @Test
    fun `appendClosestBookmarkStatus has no comma before a single bookmark name`() {
        val closest = ClosestBookmarks(listOf(Bookmark("main").name), distance = 2, distanceCapped = false)

        val html = htmlString { appendClosestBookmarkStatus(closest) }

        html shouldNotContain ", "
    }

    @Test
    fun `appendClosestBookmarkStatus comma-separates multiple bookmark names`() {
        val closest = ClosestBookmarks(
            listOf(Bookmark("main").name, Bookmark("release").name),
            distance = 2,
            distanceCapped = false
        )

        val html = htmlString { appendClosestBookmarkStatus(closest) }

        html shouldContain "main"
        html shouldContain "release"
        html shouldContain ", "
    }

    @Test
    fun `a commit tooltip with a resolved dangling-head distance shows the status tag`() {
        val closest = ClosestBookmarks(listOf(Bookmark("main").name), distance = 3, distanceCapped = false)

        val html = htmlString { appendSummaryAndStatuses(entry, closest) }

        html.shouldNotBeNull()
        html shouldContain "3 commits ahead of"
        html shouldContain "main"
    }

    @Test
    fun `a commit tooltip omits the status tag when there's no dangling-head data`() {
        val html = htmlString { appendSummaryAndStatuses(entry, danglingHeadClosest = null) }

        html shouldNotContain "commits ahead of"
    }
}
