package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [appendStatusIndicators] (jj-idea-0zfc). Mutable revisions — the common case — must
 * show no status icon; only immutable revisions get the padlock icon. The conflict icon is
 * independent of mutability and must still appear when [LogEntry.hasConflict] is set.
 */
class AppendStatusIndicatorsTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(immutable: Boolean = false, hasConflict: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        immutable = immutable,
        hasConflict = hasConflict
    )

    private fun icons(entry: LogEntry): List<Fragment.Icon> {
        val canvas = FragmentRecordingCanvas()
        canvas.appendStatusIndicators(entry)
        return canvas.fragments.filterIsInstance<Fragment.Icon>()
    }

    @Test
    fun `mutable non-conflict entry shows no status icon`() {
        icons(entry(immutable = false, hasConflict = false)).shouldBeEmpty()
    }

    @Test
    fun `immutable non-conflict entry shows exactly one status icon`() {
        icons(entry(immutable = true, hasConflict = false)) shouldHaveSize 1
    }

    @Test
    fun `mutable conflict entry still shows the conflict icon`() {
        icons(entry(immutable = false, hasConflict = true)) shouldHaveSize 1
    }

    @Test
    fun `immutable conflict entry shows both icons`() {
        icons(entry(immutable = true, hasConflict = true)) shouldHaveSize 2
    }
}
