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
        var searchFailure: Throwable? = null
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
                    return searchFailure?.let { Result.failure(it) } ?: Result.success(searchResult)
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

    /** Waits for the async reload pipeline (predicate, filter, graph layout) to deliver to the EDT. */
    private fun settle(picker: CommitPickerPanel) = waitUntil { !picker.isReloading() }

    private fun waitForInitialLoad(picker: CommitPickerPanel) {
        waitUntil { picker.entries.isNotEmpty() }
        settle(picker)
    }

    @Test
    fun `reload filters the loaded set by the predicate`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)

        picker.reload { it.id.full != "bbbbbb" }

        settle(picker)

        picker.rowCount shouldBe 1
        picker.entryAt(0)?.id?.full shouldBe "aaaaaa"
    }

    @Test
    fun `reloadFor runs the factory once per reload, not once per entry (jj-idea-i3bh)`() {
        val fake = FakeRepo()
        val n = 10_000
        val all = (0 until n).map { entry(fake.repo, "e$it") }
        every { fake.repo.logCache.all } returns all

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)

        var factoryCalls = 0
        var seenSize = -1
        picker.reloadFor { entries ->
            factoryCalls++
            seenSize = entries.size
            val excluded = setOf(all[0].id)
            ({ it.id !in excluded })
        }
        settle(picker)

        factoryCalls shouldBe 1
        seenSize shouldBe n
        picker.rowCount shouldBe n - 1

        picker.searchField.text = "e1"
        settle(picker)
        factoryCalls shouldBe 2
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
        settle(picker)

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
        settle(picker)
        picker.table.setRowSelectionInterval(0, 0)
        picker.selectedIds().map { it.full } shouldBe listOf("aaaaaa")

        // Re-running the same predicate must not disturb the existing selection.
        picker.reload { true }
        settle(picker)

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
        settle(picker)
        picker.table.setRowSelectionInterval(0, 0)
        picker.selectedIds().map { it.full } shouldBe listOf("aaaaaa")

        picker.reload { it.id.full != "aaaaaa" }

        settle(picker)

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
        settle(picker)

        // Keystrokes alone (no Enter) only re-run the client-side filter.
        picker.searchField.text = "ccc"
        settle(picker)
        fake.getLogCallCount shouldBe 0

        picker.searchField.submit()
        waitUntil { picker.entries.size > 1 }
        settle(picker)

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
        settle(picker)

        picker.searchField.text = "ccc"
        settle(picker)
        picker.rowCount shouldBe 0 // not in the loaded window yet

        picker.searchField.submit()
        waitUntil { picker.rowCount > 0 }

        picker.rowCount shouldBe 1
        picker.entryAt(0)?.id?.full shouldBe "cccccc"
    }

    @Test
    fun `rapid reloads apply only the latest predicate`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)

        picker.reload { it.id.full != "aaaaaa" }
        picker.reload { it.id.full != "bbbbbb" }
        settle(picker)

        picker.rowCount shouldBe 1
        picker.entryAt(0)?.id?.full shouldBe "aaaaaa"
    }

    @Test
    fun `a reload does not render synchronously on the EDT`() {
        val fake = FakeRepo()
        every { fake.repo.logCache.all } returns listOf(entry(fake.repo, "aaaaaa"), entry(fake.repo, "bbbbbb"))

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.rowCount shouldBe 2

        picker.reload { it.id.full == "aaaaaa" }
        picker.rowCount shouldBe 2 // old rows stay until the background result is applied
        settle(picker)

        picker.rowCount shouldBe 1
    }

    @Test
    fun `selection made while a reload is in flight is kept`() {
        val fake = FakeRepo()
        every { fake.repo.logCache.all } returns listOf(entry(fake.repo, "aaaaaa"), entry(fake.repo, "bbbbbb"))

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)

        picker.reload { true }
        picker.table.setRowSelectionInterval(1, 1) // before the result lands
        settle(picker)

        picker.selectedIds().map { it.full } shouldBe listOf("bbbbbb")
    }

    @Test
    fun `revset mode runs one jj log per Enter and narrows rows to its matches (jj-idea-cl4d)`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        val offWindow = entry(fake.repo, "cccccc", "gamma")
        every { fake.repo.logCache.all } returns listOf(a, b)
        fake.searchResult = listOf(b, offWindow)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }
        settle(picker)

        picker.searchField.revsetMode = true
        picker.searchField.text = "bbbbbb | cccccc"
        settle(picker)
        fake.getLogCallCount shouldBe 0 // typing alone never runs jj
        picker.rowCount shouldBe 2 // not applied yet

        picker.searchField.submit()
        waitUntil { picker.rowCount == 2 && picker.entries.size == 3 }
        settle(picker)

        fake.getLogCallCount shouldBe 1
        (0 until picker.rowCount).map { picker.entryAt(it)?.id?.full }.toSet() shouldBe setOf("bbbbbb", "cccccc")

        // Editing keeps the applied result without another jj call.
        picker.searchField.text = "bbbbbb | cccccc | aaaaaa"
        settle(picker)
        fake.getLogCallCount shouldBe 1
        picker.rowCount shouldBe 2

        picker.searchField.revsetMode = false
        picker.searchField.text = ""
        settle(picker)
        picker.rowCount shouldBe 3
    }

    @Test
    fun `a revset jj rejects leaves the rows and flags the field`() {
        val fake = FakeRepo()
        every { fake.repo.logCache.all } returns listOf(entry(fake.repo, "aaaaaa"), entry(fake.repo, "bbbbbb"))
        fake.searchFailure = RuntimeException("Error from jj log: Revision `zz` doesn't exist")

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.searchField.revsetMode = true
        picker.searchField.text = "zz"
        settle(picker)

        picker.searchField.submit()
        waitUntil { fake.getLogCallCount > 0 }
        UIUtil.dispatchAllInvocationEvents()
        settle(picker)

        fake.getLogCallCount shouldBe 1
        picker.rowCount shouldBe 2
    }

    @Test
    fun `a pick hidden by the search stays selected and a new pick adds to it (jj-idea-cl4d)`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }
        settle(picker)
        picker.table.setRowSelectionInterval(0, 0) // alpha

        picker.searchField.text = "beta"
        settle(picker)
        picker.rowCount shouldBe 1
        picker.table.addRowSelectionInterval(0, 0)

        picker.selectedIds().map { it.full }.toSet() shouldBe setOf("aaaaaa", "bbbbbb")

        picker.searchField.text = ""
        settle(picker)
        picker.table.selectedRows.toList() shouldBe listOf(0, 1)
    }

    @Test
    fun `a pick the predicate rejects is dropped even while hidden by the search`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.reload { true }
        settle(picker)
        picker.table.setRowSelectionInterval(0, 0)
        picker.searchField.text = "beta"
        settle(picker)

        picker.reload { it.id.full != "aaaaaa" }
        settle(picker)

        picker.selectedIds() shouldBe emptySet()
    }

    @Test
    fun `a single-select pick replaces an earlier one`() {
        val fake = FakeRepo()
        every { fake.repo.logCache.all } returns listOf(entry(fake.repo, "aaaaaa"), entry(fake.repo, "bbbbbb"))

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = false)
        waitForInitialLoad(picker)
        picker.table.setRowSelectionInterval(0, 0)
        picker.table.setRowSelectionInterval(1, 1)

        picker.selectedIds().map { it.full } shouldBe listOf("bbbbbb")
    }

    @Test
    fun `typing over a revset keeps the applied result, clearing the box drops it`() {
        val fake = FakeRepo()
        val a = entry(fake.repo, "aaaaaa", "alpha")
        val b = entry(fake.repo, "bbbbbb", "beta")
        every { fake.repo.logCache.all } returns listOf(a, b)
        fake.searchResult = listOf(b)

        val picker = CommitPickerPanel(project.get(), fake.repo, "search", multiSelect = true)
        waitForInitialLoad(picker)
        picker.searchField.revsetMode = true
        picker.searchField.text = "bbbbbb"
        picker.searchField.submit()
        waitUntil { picker.rowCount == 1 }
        settle(picker)

        // Typing over the selection: the box is empty for an instant, then holds the new text.
        picker.searchField.text = ""
        picker.searchField.text = "bogus("
        UIUtil.dispatchAllInvocationEvents()
        settle(picker)
        picker.rowCount shouldBe 1

        // A box left empty restores the full list.
        picker.searchField.text = ""
        UIUtil.dispatchAllInvocationEvents()
        settle(picker)
        picker.rowCount shouldBe 2
    }
}
