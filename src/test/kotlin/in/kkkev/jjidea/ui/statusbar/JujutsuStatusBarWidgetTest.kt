package `in`.kkkev.jjidea.ui.statusbar

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuStatusBarWidgetTest {
    private val repo = mockk<JujutsuRepository>()
    private val changeId = ChangeId("qpvuntsm", "qp", 2)
    private val commitId = CommitId("abc123def456", "ab")

    @Test
    fun `shows local bookmark name when bookmarks present`() {
        val entry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = "Some work",
            bookmarks = listOf(Bookmark("my-feature"))
        )
        JujutsuStatusBarWidget.displayTextFor(entry) shouldBe "my-feature"
    }

    @Test
    fun `joins multiple local bookmarks with comma`() {
        val entry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = "",
            bookmarks = listOf(Bookmark("main"), Bookmark("release-1.0"))
        )
        JujutsuStatusBarWidget.displayTextFor(entry) shouldBe "main, release-1.0"
    }

    @Test
    fun `prefers local bookmarks over remote when both present`() {
        val entry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = "",
            bookmarks = listOf(Bookmark("main@origin"), Bookmark("main"))
        )
        // Local "main" should appear before remote "main@origin"
        JujutsuStatusBarWidget.displayTextFor(entry) shouldBe "main, main@origin"
    }

    @Test
    fun `shows description summary when no bookmarks`() {
        val entry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = "Fix the thing\n\nMore detail here"
        )
        JujutsuStatusBarWidget.displayTextFor(entry) shouldBe "Fix the thing"
    }

    @Test
    fun `shows placeholder when empty description and no bookmarks`() {
        val entry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = ""
        )
        JujutsuStatusBarWidget.displayTextFor(entry) shouldBe "(no description set)"
    }
}
