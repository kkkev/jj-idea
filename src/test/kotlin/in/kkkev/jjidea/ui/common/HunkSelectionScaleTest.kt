package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Operation-count scale test for [buildHunkSelection] — the OK-time content backfill shared by
 * [in.kkkev.jjidea.ui.split.SplitDialog] and [in.kkkev.jjidea.ui.squash.SquashIntoDialog].
 *
 * `contentFor` is only meant to be consulted for files without an override and without a
 * cheaper (null/omitted) default — see this function's KDoc. A regression that called it for
 * every file regardless of state, or multiple times per file, would still produce identical
 * output (a correct-but-quadratic bug), so this asserts on invocation *count*, not on the
 * resulting [HunkSelection]. Synthetic in-memory changes, no real repo — see contributing.md's
 * "Writing a scale test" and [in.kkkev.jjidea.jj.RepoLogCacheScaleTest] for the pattern.
 */
class HunkSelectionScaleTest {
    private val root: VirtualFile = mockk { every { path } returns "/repo" }

    private fun change(path: String) = Change(
        SimpleContentRevision("before", LocalFilePath("/repo/$path", false), "1"),
        SimpleContentRevision("after", LocalFilePath("/repo/$path", false), "2")
    )

    @Test
    fun `contentFor is called at most once per file`() {
        val n = 500
        val changes = (0 until n).map { change("file$it.txt") }
        val callCount = AtomicInteger(0)

        buildHunkSelection(
            changes = changes,
            root = root,
            overrides = emptyMap(),
            isIncluded = { true },
            isDeletion = { false },
            contentFor = { _, included ->
                callCount.incrementAndGet()
                if (included) "after" else null
            }
        )

        callCount.get().toLong() shouldBeLessThan (2L * n)
        callCount.get() shouldBe n
    }

    @Test
    fun `contentFor is never called for a file with an override`() {
        val n = 200
        val changes = (0 until n).map { change("file$it.txt") }
        val overrides = changes.associate { it.filePath to "picked" }
        val callCount = AtomicInteger(0)

        val selection = buildHunkSelection(
            changes = changes,
            root = root,
            overrides = overrides,
            isIncluded = { true },
            isDeletion = { false },
            contentFor = { _, _ ->
                callCount.incrementAndGet()
                "should not be reached"
            }
        )

        callCount.get() shouldBe 0
        selection.files.all { it.content == "picked" } shouldBe true
    }

    @Test
    fun `isDeletion is called at most once per file and short-circuits contentFor`() {
        val n = 200
        val changes = (0 until n).map { change("file$it.txt") }
        val deletionCalls = AtomicInteger(0)
        val contentForCalls = AtomicInteger(0)

        buildHunkSelection(
            changes = changes,
            root = root,
            overrides = emptyMap(),
            isIncluded = { true },
            isDeletion = {
                deletionCalls.incrementAndGet()
                true
            },
            contentFor = { _, _ ->
                contentForCalls.incrementAndGet()
                "unreached"
            }
        )

        deletionCalls.get() shouldBe n
        contentForCalls.get() shouldBe 0
    }
}
