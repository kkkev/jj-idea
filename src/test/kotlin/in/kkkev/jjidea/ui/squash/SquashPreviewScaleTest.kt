package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Operation-count test for [SquashIntoDialog]'s per-file preview cache (`fileDataCache`).
 *
 * The preview loads a file's content via [loadSquashFileData] only on a cache miss (see
 * `showPreviewForChange`), so re-selecting an already-viewed file or toggling its checkbox
 * should issue zero further content loads — the fetch count should track *distinct files
 * viewed*, not *selection events* or *total files in the change*.
 *
 * Lives in `platformTest` rather than the default `test` task (contributing.md's usual home
 * for operation-count tests) because the cache lives inside [SquashIntoDialog]'s real
 * `DialogWrapper`/`ChangesTree` listener wiring, which is what this test needs to exercise —
 * a synthetic pure-function replica of the caching logic wouldn't catch a regression in that
 * wiring itself. [in.kkkev.jjidea.ui.split.SplitDialog]'s equivalent preview-load path has the
 * same property and is untested for the same reason; this is the first coverage of the pattern.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SquashPreviewScaleTest {
    private val project = projectFixture()

    @Test
    fun `selecting, re-selecting, and toggling files loads content once per distinct file`() {
        val loadCount = AtomicInteger(0)
        val fileCount = 50
        val changes = (0 until fileCount).map { countingChange("file$it.txt", loadCount) }
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = SquashIntoDialog(
            project.get(),
            source.repo,
            SquashMode.PickDestination(listOf(source), listOf(parent)),
            changes
        )
        try {
            // The tree auto-selects its first row on populate, which triggers one preview load —
            // exactly 2 fetches (before + after) for that one file, not one per file in the change.
            selectAndAwaitPreview(dialog, changes[0].filePath, loadCount, expected = 2)
            loadCount.get() shouldBe 2

            selectAndAwaitPreview(dialog, changes[0].filePath, loadCount, expected = 2)
            loadCount.get() shouldBe 2 // re-selecting the same (cached) file: no new loads.

            // Each toggle re-renders the preview with genuinely different content (included vs.
            // excluded), so 10 toggles legitimately rebuild the diff viewer 20 times in a row.
            // On 2025.1 only, DiffRequestProcessor.buildToolbar force-updates the diff toolbar
            // synchronously on every setRequest, and ActionToolbarImpl.
            // reportActionButtonChangedEveryTimeIfNeeded logs an error once a toolbar creates new
            // button instances for 20 updates in a row — a real diagnostic for a toolbar whose
            // actions actually don't change, but a false positive here where the diff content
            // does change every time. 2025.2+ dropped that synchronous update from buildToolbar,
            // so this never fires there. Suppress only that one message, scoped to this loop —
            // any other logged error still fails the test.
            suppressingToolbarRebuildDiagnostic {
                repeat(10) {
                    dialog.fileSelection.changesTree.setIncludedChanges(
                        dialog.fileSelection.includedChanges - changes[0]
                    )
                    dialog.fileSelection.changesTree.setIncludedChanges(changes.take(1))
                }
            }
            loadCount.get() shouldBe 2 // toggling the checked state 10x: still no new loads.

            val distinctFilesToView = 5
            for (i in 1 until distinctFilesToView) {
                selectAndAwaitPreview(dialog, changes[i].filePath, loadCount, expected = 2 * (i + 1))
            }
            loadCount.get() shouldBe (2 * distinctFilesToView) // 2 * distinct files viewed, not 2 * fileCount.
        } finally {
            disposeDialog(dialog)
        }
    }

    /**
     * Runs [action] with [LoggedErrorProcessor] set to swallow only the 2025.1-only
     * "toolbar creates new components for N updates in a row" diagnostic (see call site KDoc);
     * any other logged error still propagates and fails the test.
     */
    private fun suppressingToolbarRebuildDiagnostic(action: () -> Unit) {
        LoggedErrorProcessor.executeWith<RuntimeException>(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<String>,
                    t: Throwable?
                ): Set<Action> =
                    if (message.contains("toolbar creates new components")) Action.NONE else Action.ALL
            },
            action
        )
    }

    @Test
    fun `content fetches stay linear in distinct files viewed, not quadratic`() {
        val loadCount = AtomicInteger(0)
        val n = 200
        val changes = (0 until n).map { countingChange("file$it.txt", loadCount) }
        val source = createEntry("src1", description = "desc")
        val parent = createEntry("par1", description = "")
        val dialog = SquashIntoDialog(
            project.get(),
            source.repo,
            SquashMode.PickDestination(listOf(source), listOf(parent)),
            changes
        )
        try {
            waitForRefresh(dialog)

            for (change in changes) {
                selectAndAwaitPreview(dialog, change.filePath, loadCount, expected = loadCount.get() + 2)
            }

            // Exactly 2 per distinct file (each selected once here) — a quadratic re-fetch-on-every-
            // -selection regression would blow well past this bound.
            loadCount.get().toLong() shouldBeLessThan (3L * n)
        } finally {
            disposeDialog(dialog)
        }
    }

    private fun selectAndAwaitPreview(
        dialog: SquashIntoDialog,
        filePath: FilePath,
        loadCount: AtomicInteger,
        expected: Int
    ) {
        dialog.fileSelection.changesTree.selectFile(filePath)
        val deadline = System.currentTimeMillis() + 5_000
        while (loadCount.get() < expected && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    private fun waitForRefresh(dialog: SquashIntoDialog) {
        var refreshed = false
        dialog.fileSelection.changesTree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }

    private fun createEntry(id: String, description: String = "") = LogEntry(
        repo = mockk(relaxed = true),
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = description
    )

    /** A [Change] whose before/after content revisions increment [counter] on every getContent() call. */
    private fun countingChange(path: String, counter: AtomicInteger): Change {
        val filePath = LocalFilePath(path, false)
        return Change(
            CountingContentRevision(filePath, "before", counter),
            CountingContentRevision(filePath, "after", counter)
        )
    }

    private class CountingContentRevision(
        private val filePath: FilePath,
        private val text: String,
        private val counter: AtomicInteger
    ) : ContentRevision {
        override fun getContent(): String {
            counter.incrementAndGet()
            return text
        }

        override fun getFile() = filePath

        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
