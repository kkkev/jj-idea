package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vcs.FilePath
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Covers [CommitPickerPanel]'s two responsibilities behind the Rebase/Squash Into…/Duplicate
 * Onto… destination pickers (jj-idea-tq4b): combining a caller predicate with the search text
 * client-side ([CommitPickerPanel.reload]), and falling back to a whole-repo `jj log -r` query on
 * Enter so a commit outside the loaded window can still be found.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class CommitPickerPanelTest {
    private val project = projectFixture()

    private fun entry(repo: JujutsuRepository, id: String, description: String = "") =
        LogEntry(repo, ChangeId(id, id), CommitId(id.padEnd(40, '0')), description)

    /**
     * A repo whose logCache.all starts empty (set via `every { repo.logCache.all } returns ...`
     * once entries referencing [repo] exist) and whose logService.getLog counts calls, returning
     * [searchResult] (set the same way, once found entries referencing [repo] exist).
     *
     * getLog is intercepted with a plain `object : LogService by mockk(relaxed = true)` override
     * rather than a mockk `every { }`/`match { }` on the call itself: mockk's matcher machinery
     * throws `IllegalStateException: null packRef` for [Revset], a sealed interface backed by an
     * inline value class ([in.kkkev.jjidea.jj.Expression]) — same workaround as
     * [in.kkkev.jjidea.actions.bookmark.MoveBookmarkDirectionTest].
     */
    private class FakeRepo {
        var getLogCallCount = 0
            private set
        var searchResult: List<LogEntry> = emptyList()
        val repo: JujutsuRepository = mockk(relaxed = true)

        init {
            val logCache = mockk<LogCache>(relaxed = true)
            every { logCache.all } returns emptyList()
            val logService = object : LogService by mockk(relaxed = true) {
                override fun getLog(
                    revset: Revset,
                    filePaths: List<FilePath>,
                    limit: Int?,
                    quiet: Boolean
                ): Result<List<LogEntry>> {
                    getLogCallCount++
                    return Result.success(searchResult)
                }
            }
            every { repo.logCache } returns logCache
            every { repo.logService } returns logService
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    private fun waitForInitialLoad(picker: CommitPickerPanel) = waitUntil { picker.entries.isNotEmpty() }

    @Test
    fun `reload filters the loaded set by the predicate`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)

        picker.reload { it.id.full != "bbbbbb" }

        picker.rowCount shouldBe 1
        picker.entryAt(0)?.id?.full shouldBe "aaaaaa"
    }

    @Test
    fun `search text further narrows the predicate-filtered set`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)

        picker.searchField.text = "alpha"

        picker.rowCount shouldBe 1
        picker.entryAt(0)?.id?.full shouldBe "aaaaaa"
    }

    @Test
    fun `selection is preserved across a reload when the entry still matches`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }
        picker.table.setRowSelectionInterval(0, 0)
        picker.selectedIds().map { it.full } shouldBe listOf("aaaaaa")

        // Re-running the same predicate must not disturb the existing selection.
        picker.reload { true }

        picker.selectedIds().map { it.full } shouldBe listOf("aaaaaa")
    }

    @Test
    fun `selection is dropped once the selected entry no longer matches`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }
        picker.table.setRowSelectionInterval(0, 0)
        picker.selectedIds().map { it.full } shouldBe listOf("aaaaaa")

        picker.reload { it.id.full != "aaaaaa" }

        picker.selectedIds() shouldBe emptySet()
    }

    @Test
    fun `typing does not issue a jj log call, only Enter does`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val found = entry(fake.repo, "cccccc", "gamma")
        every { fake.repo.logCache.all } returns listOf(a)
        fake.searchResult = listOf(found)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }

        // Keystrokes alone (no Enter) only re-run the client-side filter.
        picker.searchField.text = "ccc"
        fake.getLogCallCount shouldBe 0

        picker.searchField.textEditor.postActionEvent()
        waitUntil { picker.entries.size > 1 }

        fake.getLogCallCount shouldBe 1
        picker.entries.map { it.id.full } shouldBe listOf("aaaaaa", "cccccc")
    }

    @Test
    fun `a commit found by whole-repo search becomes visible under the caller predicate`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val found = entry(fake.repo, "cccccc", "gamma")
        every { fake.repo.logCache.all } returns listOf(a)
        fake.searchResult = listOf(found)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }

        picker.searchField.text = "ccc"
        picker.rowCount shouldBe 0 // not in the loaded window yet

        picker.searchField.textEditor.postActionEvent()
        waitUntil { picker.rowCount > 0 }

        picker.rowCount shouldBe 1
        picker.entryAt(0)?.id?.full shouldBe "cccccc"
    }
}
