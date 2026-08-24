package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * [LazyActionById] exists so [in.kkkev.jjidea.ui.common.CommitTablePanel]'s toolbar, which builds
 * its action list once and keeps it for the panel's lifetime, never keeps calling `update()` on a
 * stale action instance left over from a previous plugin classloader (jj-idea-nd8x). These tests
 * simulate that scenario without a real dynamic plugin reload: [ActionManager.replaceAction] swaps
 * in a fresh instance under the same id, standing in for "the plugin was hot-reloaded and a new
 * classloader now owns this id".
 */
@Tag("platform")
@TestApplication
@RunInEdt
class LazyActionByIdTest {
    private val actionId = "jj-idea-nd8x.LazyActionByIdTest.probe"

    @AfterEach
    fun unregister() {
        val manager = ActionManager.getInstance()
        if (manager.getAction(actionId) != null) manager.unregisterAction(actionId)
    }

    private class ProbeAction(val calls: MutableList<String>, private val label: String) : AnAction() {
        override fun update(e: AnActionEvent) {
            calls += "update:$label"
            e.presentation.isEnabledAndVisible = true
        }

        override fun actionPerformed(e: AnActionEvent) {
            calls += "performed:$label"
        }
    }

    @Test
    fun `update delegates to whichever action is currently registered under the id`() {
        val calls = mutableListOf<String>()
        val manager = ActionManager.getInstance()
        manager.registerAction(actionId, ProbeAction(calls, "first"))

        val lazyAction = LazyActionById(actionId)
        lazyAction.update(TestActionEvent.createTestEvent())
        calls shouldBe listOf("update:first")

        // Simulate a plugin hot-reload: a new instance takes over the same action id.
        manager.replaceAction(actionId, ProbeAction(calls, "second"))
        lazyAction.update(TestActionEvent.createTestEvent())

        calls shouldBe listOf("update:first", "update:second")
    }

    @Test
    fun `actionPerformed delegates to whichever action is currently registered under the id`() {
        val calls = mutableListOf<String>()
        val manager = ActionManager.getInstance()
        manager.registerAction(actionId, ProbeAction(calls, "first"))

        val lazyAction = LazyActionById(actionId)
        manager.replaceAction(actionId, ProbeAction(calls, "second"))
        lazyAction.actionPerformed(TestActionEvent.createTestEvent())

        calls shouldBe listOf("performed:second")
    }

    @Test
    fun `disables itself when the id is not registered`() {
        val lazyAction = LazyActionById("jj-idea-nd8x.LazyActionByIdTest.unregistered")
        val event = TestActionEvent.createTestEvent()

        lazyAction.update(event)

        event.presentation.isEnabledAndVisible shouldBe false
    }
}
