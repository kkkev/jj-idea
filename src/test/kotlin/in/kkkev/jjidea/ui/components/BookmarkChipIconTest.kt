package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for jj-idea-rskx: the bookmark chip's icon reflects its state (plain/tracked/deleted/
 * conflict) via [JujutsuIcons], with conflict taking precedence over deletion (deletion is still
 * conveyed by strikethrough on the label) - see the private `appendBookmarkChip` in TextCanvas.kt.
 */
class BookmarkChipIconTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(bookmark: Bookmark) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = listOf(bookmark)
    )

    private fun chipIconName(bookmark: Bookmark): String {
        val e = entry(bookmark)
        val canvas = FragmentRecordingCanvas()
        canvas.appendBookmarks(e)
        return canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Icon>()
            .single()
            .icon.name
    }

    @Test
    fun `untracked remote-only bookmark uses the plain pennant`() {
        val bookmark = Bookmark("feature@origin", tracked = false)
        chipIconName(bookmark) shouldBe icon(JujutsuIcons::Bookmark).name
    }

    @Test
    fun `local bookmark uses the tracked pennant`() {
        val bookmark = Bookmark("main", tracked = true)
        chipIconName(bookmark) shouldBe icon(JujutsuIcons::BookmarkTracked).name
    }

    @Test
    fun `pending-deletion bookmark uses the deleted pennant`() {
        val bookmark = Bookmark("main", deleted = true)
        chipIconName(bookmark) shouldBe icon(JujutsuIcons::BookmarkDeleted).name
    }

    @Test
    fun `conflicted bookmark uses the conflict pennant`() {
        val bookmark = Bookmark("main", conflict = true)
        chipIconName(bookmark) shouldBe icon(JujutsuIcons::BookmarkConflict).name
    }

    @Test
    fun `conflict takes precedence over deletion in the icon, but strikethrough still applies`() {
        val bookmark = Bookmark("main", deleted = true, conflict = true)
        val e = entry(bookmark)
        val canvas = FragmentRecordingCanvas()

        canvas.appendBookmarks(e)

        canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Icon>()
            .single().icon.name shouldBe icon(JujutsuIcons::BookmarkConflict).name
        canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .single { it.text == "main" }
            .style.isStrikeout shouldBe true
    }

    @Test
    fun `only one icon fragment renders per chip - no separate conflict prefix icon`() {
        val bookmark = Bookmark("main", conflict = true)
        val e = entry(bookmark)
        val canvas = FragmentRecordingCanvas()

        canvas.appendBookmarks(e)

        canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Icon>().size shouldBe 1
    }
}
