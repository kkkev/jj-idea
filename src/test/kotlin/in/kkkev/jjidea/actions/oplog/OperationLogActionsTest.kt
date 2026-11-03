package `in`.kkkev.jjidea.actions.oplog

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.ui.oplog.OperationLogView
import `in`.kkkev.jjidea.ui.oplog.OperationRow
import `in`.kkkev.jjidea.ui.oplog.RowActionState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor

@Tag("platform")
@TestApplication
@RunInEdt
class OperationLogActionsTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(
        id: String,
        root: Boolean = false,
        description: String = "describe",
        args: String = "args: jj describe"
    ) = OperationEntry(
        OperationId(id),
        emptyList(),
        description,
        Instant.fromEpochSeconds(1788000000),
        "kevin",
        false,
        root,
        args
    )

    private val head = entry("head")
    private val older = entry("older")

    private class FakeView(private val head: OperationEntry, var refreshed: Int = 0, var wentToHead: Int = 0) :
        OperationLogView {
        override fun head(repo: JujutsuRepository) = head

        override fun laterCount(row: OperationRow) = 1

        override fun refresh() {
            refreshed++
        }

        override fun goToHead() {
            wentToHead++
        }

        override fun goToHeadState() = RowActionState(true)
    }

    private fun eventWith(row: OperationRow?, view: OperationLogView? = null) = TestActionEvent.createTestEvent(
        DataContext {
            when (it) {
                JujutsuDataKeys.OPERATION_ROW.name -> row
                JujutsuDataKeys.OPERATION_LOG.name -> view
                else -> null
            }
        }
    )

    private fun action(id: String) = ActionManager.getInstance().getAction(id)

    private fun clipboard() = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)

    @Test
    fun `all actions are registered and keymap-assignable`() {
        listOf("Revert", "Restore", "CopyId", "CopyDescription", "CopyCommand", "GoToCurrent", "Refresh").forEach {
            (action("Jujutsu.Oplog.$it") != null) shouldBe true
        }
    }

    @Test
    fun `row actions are disabled with a reason when nothing is selected`() {
        listOf("Revert", "Restore", "CopyId", "CopyDescription", "CopyCommand").forEach {
            val event = eventWith(null)
            action("Jujutsu.Oplog.$it").update(event)
            event.presentation.isEnabled shouldBe false
            event.presentation.description shouldBe "Select an operation"
        }
    }

    @Test
    fun `revert and restore are disabled on the root operation`() {
        val root = OperationRow(repo, entry("0", root = true, description = ""))
        listOf("Revert", "Restore").forEach {
            val event = eventWith(root, FakeView(head))
            action("Jujutsu.Oplog.$it").update(event)
            event.presentation.isEnabled shouldBe false
            event.presentation.description shouldContain "root operation"
        }
    }

    @Test
    fun `restore is disabled on the head operation but enabled on an older one`() {
        val view = FakeView(head)
        val onHead = eventWith(OperationRow(repo, head), view)
        action("Jujutsu.Oplog.Restore").update(onHead)
        onHead.presentation.isEnabled shouldBe false

        val onOlder = eventWith(OperationRow(repo, older), view)
        action("Jujutsu.Oplog.Restore").update(onOlder)
        onOlder.presentation.isEnabled shouldBe true
    }

    @Test
    fun `copy actions put the id, full description and command on the clipboard`() {
        val row = OperationRow(repo, entry("abc123", description = "line one\nline two", args = "args: jj new -m x"))

        action("Jujutsu.Oplog.CopyId").actionPerformed(eventWith(row))
        clipboard() shouldBe "abc123"
        action("Jujutsu.Oplog.CopyDescription").actionPerformed(eventWith(row))
        clipboard() shouldBe "line one\nline two"
        action("Jujutsu.Oplog.CopyCommand").actionPerformed(eventWith(row))
        clipboard() shouldBe "jj new -m x"
    }

    @Test
    fun `copy description and command are disabled with a reason when empty`() {
        val row = OperationRow(repo, entry("0", description = "", args = ""))

        val description = eventWith(row)
        action("Jujutsu.Oplog.CopyDescription").update(description)
        description.presentation.isEnabled shouldBe false
        description.presentation.description shouldBe "This operation has no description"

        val command = eventWith(row)
        action("Jujutsu.Oplog.CopyCommand").update(command)
        command.presentation.isEnabled shouldBe false
        command.presentation.description shouldBe "This operation has no recorded command"
    }

    @Test
    fun `refresh and go to current call through to the tab and hide outside it`() {
        val view = FakeView(head)

        action("Jujutsu.Oplog.Refresh").actionPerformed(eventWith(null, view))
        action("Jujutsu.Oplog.GoToCurrent").actionPerformed(eventWith(null, view))
        view.refreshed shouldBe 1
        view.wentToHead shouldBe 1

        listOf("Refresh", "GoToCurrent").forEach {
            val event = eventWith(null)
            action("Jujutsu.Oplog.$it").update(event)
            event.presentation.isVisible shouldBe false
        }
    }

    @Test
    fun `refresh and copy id follow the platform shortcuts`() {
        fun shortcuts(id: String) = action(id).shortcutSet.shortcuts.toList()

        shortcuts("Jujutsu.Oplog.Refresh") shouldBe shortcuts("Refresh")
        shortcuts("Jujutsu.Oplog.CopyId") shouldBe shortcuts("\$Copy")
    }
}
