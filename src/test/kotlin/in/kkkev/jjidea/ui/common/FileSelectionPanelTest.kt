package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class FileSelectionPanelTest {
    private val project = projectFixture()

    @Test
    fun `all files included by default after setChanges`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"), change("README.md"))
        val panel = FileSelectionPanel(project.get())

        panel.setChanges(changes)

        waitForRefresh(panel)
        panel.includedChanges shouldHaveSize 3
        panel.allIncluded shouldBe true
    }

    @Test
    fun `unchecking a file updates includedChanges and allIncluded`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"), change("README.md"))
        val panel = FileSelectionPanel(project.get())
        panel.setChanges(changes)

        waitForRefresh(panel)
        panel.changesTree.setIncludedChanges(changes.take(2))

        panel.includedChanges shouldHaveSize 2
        panel.allIncluded shouldBe false
    }

    @Test
    fun `empty changes`() {
        val panel = FileSelectionPanel(project.get())
        panel.setChanges(emptyList())

        waitForRefresh(panel)
        panel.includedChanges.shouldBeEmpty()
        panel.allIncluded shouldBe true
    }

    private fun waitForRefresh(panel: FileSelectionPanel) {
        var refreshed = false
        panel.changesTree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }

    private fun change(path: String): Change {
        val filePath = LocalFilePath(path, false)
        return Change(null, SimpleContentRevision("", filePath, "1"))
    }
}
