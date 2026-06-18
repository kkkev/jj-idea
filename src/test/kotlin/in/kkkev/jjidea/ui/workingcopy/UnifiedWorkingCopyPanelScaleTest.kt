package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Operation-count scale guard for the wc-rebuild fan-out (jj-idea-f21f), the deferred
 * fourth hot path from jj-idea-edjs.2. Asserts the two mechanisms that keep a burst of
 * VFS/change-list events to <=1 tree rebuild (cf. closed jj-idea-7jn1, per-VFS-event
 * rebuilds):
 *
 * 1. [UnifiedWorkingCopyPanel.scheduleReloadChanges] coalesces via a `MergingUpdateQueue`
 *    with a single identity `Update`, so a burst collapses into one reload.
 * 2. [UnifiedWorkingCopyPanel.updateChangesView] early-outs when the new changes equal
 *    the tree's current changes, so re-displaying identical content does not rebuild.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class UnifiedWorkingCopyPanelScaleTest {
    private val project = projectFixture()

    @Test
    fun `burst of scheduled reloads coalesces into a single reload`() {
        val panel = UnifiedWorkingCopyPanel(project.get())
        try {
            val burstSize = 1_000
            repeat(burstSize) { panel.scheduleReloadChanges() }

            panel.flushReloadQueue()
            pumpEdt()

            // Bound: must be exactly 1, not burstSize - a regression that rebuilds per
            // event (or that gives each queued Update a distinct identity) blows through.
            panel.reloadCount shouldBe 1
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `re-displaying identical changes does not rebuild`() {
        val panel = UnifiedWorkingCopyPanel(project.get())
        try {
            val a = change("src/Main.kt")
            val b = change("src/Utils.kt")

            panel.updateChangesView(listOf(a))
            pumpEdt()
            panel.rebuildCount shouldBe 1

            // Same content again -> early-out, no extra rebuild.
            panel.updateChangesView(listOf(a))
            pumpEdt()
            panel.rebuildCount shouldBe 1

            // Different content -> rebuilds again.
            panel.updateChangesView(listOf(a, b))
            pumpEdt()
            panel.rebuildCount shouldBe 2
        } finally {
            Disposer.dispose(panel)
        }
    }

    // The counters under test increment synchronously when the queue/update runs; this
    // only drains any invokeLater hops already posted to the EDT, so a couple of passes
    // suffice (cf. FileSelectionPanelTest.waitForRefresh, which polls for an async signal
    // that doesn't exist here).
    private fun pumpEdt() = repeat(3) { UIUtil.dispatchAllInvocationEvents() }

    private fun change(path: String): Change {
        val filePath = LocalFilePath(path, false)
        return Change(null, SimpleContentRevision("", filePath, "1"))
    }
}
