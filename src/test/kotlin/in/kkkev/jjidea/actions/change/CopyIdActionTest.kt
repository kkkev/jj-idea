package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor

@Tag("platform")
@TestApplication
@RunInEdt
class CopyIdActionTest {
    private val full = "0123456789abcdef0123456789abcdef01234567"

    private val entry = LogEntry(
        repo = mockk<JujutsuRepository>(relaxed = true),
        id = ChangeId("qpvuntsm", "qp"),
        commitId = CommitId(full, "01234567"),
        underlyingDescription = "Test"
    )

    private fun eventWith(entry: LogEntry?) = TestActionEvent.createTestEvent(
        DataContext { if (it == JujutsuDataKeys.LOG_ENTRY.name) entry else null }
    )

    @Test
    fun `registered Copy Commit ID action copies the selected entry's full commit id`() {
        val action = ActionManager.getInstance().getAction("Jujutsu.CopyCommitId")

        action.actionPerformed(eventWith(entry))

        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) shouldBe full
    }

    @Test
    fun `registered Copy Change ID action copies the selected entry's full change id`() {
        val action = ActionManager.getInstance().getAction("Jujutsu.CopyChangeId")

        action.actionPerformed(eventWith(entry))

        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) shouldBe "qpvuntsm"
    }

    @Test
    fun `registered copy actions are disabled without a log selection`() {
        listOf("Jujutsu.CopyChangeId", "Jujutsu.CopyCommitId").forEach {
            val event = eventWith(null)
            ActionManager.getInstance().getAction(it).update(event)
            event.presentation.isEnabled shouldBe false
        }
    }
}
