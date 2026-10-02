package `in`.kkkev.jjidea.ui.components

import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** jj-idea-qr78 (GitHub #128): the "Bookmark Ahead/Behind Counts" toggle hides ↑n↓m on chips. */
@Tag("platform")
@TestApplication
class BookmarkDivergenceToggleTest {
    private val entry = LogEntry(
        repo = mockk<JujutsuRepository>(relaxed = true),
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = listOf(Bookmark("feature@origin", tracked = true, aheadCount = 3, behindCount = 2))
    )

    @AfterEach
    fun reset() {
        JujutsuApplicationSettings.getInstance().state.showBookmarkDivergence = true
    }

    private fun renderedText(): String {
        val canvas = FragmentRecordingCanvas()
        canvas.appendBookmarks(entry)
        return canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .joinToString("") { it.text }
    }

    @Test
    fun `counts are shown by default`() {
        renderedText().contains("↑3↓2") shouldBe true
    }

    @Test
    fun `counts are hidden when the setting is off`() {
        JujutsuApplicationSettings.getInstance().state.showBookmarkDivergence = false
        val text = renderedText()
        text.contains("↑") shouldBe false
        text.contains("↓") shouldBe false
        text.contains("feature") shouldBe true
    }

    @Test
    fun `ref chip builders honour the setting too`() {
        JujutsuApplicationSettings.getInstance().state.showBookmarkDivergence = false
        val canvas = FragmentRecordingCanvas()
        bookmarkRefChips(entry).forEach { it.build(canvas) }
        canvas.fragments.filterIsInstance<FragmentRecordingCanvas.Fragment.Text>()
            .any { "↑" in it.text || "↓" in it.text } shouldBe false
    }
}
