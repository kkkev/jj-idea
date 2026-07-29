package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuCompareChangesPanelTest {
    private val project = projectFixture()

    @Test
    fun `setChanges populates the tree with the given files`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"), change("README.md"))
        val panel = JujutsuCompareChangesPanel(project.get()) { "context" }
        try {
            panel.setChanges(changes)

            waitForRefresh(panel)
            panel.changesTree.changes shouldHaveSize 3
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `empty changes leave the tree empty`() {
        val panel = JujutsuCompareChangesPanel(project.get()) { "context" }
        try {
            panel.setChanges(emptyList())

            waitForRefresh(panel)
            panel.changesTree.changes.shouldBeEmpty()
        } finally {
            Disposer.dispose(panel)
        }
    }

    private fun waitForRefresh(panel: JujutsuCompareChangesPanel) {
        var refreshed = false
        panel.changesTree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    private fun change(path: String): Change {
        val filePath = LocalFilePath(path, false)
        return Change(null, SimpleContentRevision("", filePath, "1"))
    }
}
