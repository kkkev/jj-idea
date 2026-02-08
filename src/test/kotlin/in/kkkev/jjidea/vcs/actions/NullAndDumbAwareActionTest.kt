package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the action helper functions [nullAndDumbAwareAction] and [emptyAndDumbAwareAction].
 *
 * These helpers create actions that automatically disable themselves when their target is null/empty.
 *
 * Note: These tests verify the action creation and target handling without invoking the IntelliJ
 * action system, since that requires full IDE initialization. The enable/disable behavior is
 * verified through the underlying list that determines enabled state.
 */
class NullAndDumbAwareActionTest {
    @Nested
    inner class `nullAndDumbAwareAction` {
        @Test
        fun `creates action with empty list when target is null`() {
            val action = nullAndDumbAwareAction<String>(null, "log.action.edit", AllIcons.Actions.Edit) {}

            // When target is null, listOfNotNull creates empty list
            action.target shouldBe emptyList()
        }

        @Test
        fun `creates action with single-element list when target is non-null`() {
            val action = nullAndDumbAwareAction("test-value", "log.action.edit", AllIcons.Actions.Edit) {}

            // When target is non-null, listOfNotNull creates single-element list
            action.target shouldBe listOf("test-value")
        }

        @Test
        fun `action is EmptyAndDumbAwareAction subclass`() {
            val action = nullAndDumbAwareAction("test", "log.action.edit", AllIcons.Actions.Edit) {}

            action.shouldBeInstanceOf<EmptyAndDumbAwareAction<String>>()
        }

        @Test
        fun `target list empty implies action will be disabled`() {
            val actionWithNull = nullAndDumbAwareAction<String>(null, "log.action.edit", AllIcons.Actions.Edit) {}
            val actionWithValue = nullAndDumbAwareAction("value", "log.action.edit", AllIcons.Actions.Edit) {}

            // EmptyAndDumbAwareAction.update() disables action when target.isEmpty()
            actionWithNull.target.isEmpty() shouldBe true // will be disabled
            actionWithValue.target.isEmpty() shouldBe false // will be enabled
        }
    }

    @Nested
    inner class `emptyAndDumbAwareAction` {
        @Test
        fun `creates action with empty list when given empty list`() {
            val action = emptyAndDumbAwareAction<String>(
                emptyList(),
                "log.action.new.from.plural",
                AllIcons.General.Add
            ) {
            }

            action.target shouldBe emptyList()
        }

        @Test
        fun `creates action with provided list when non-empty`() {
            val items = listOf("a", "b", "c")
            val action = emptyAndDumbAwareAction(items, "log.action.new.from.plural", AllIcons.General.Add) {}

            action.target shouldBe items
        }

        @Test
        fun `target list empty implies action will be disabled`() {
            val actionEmpty = emptyAndDumbAwareAction<String>(
                emptyList(),
                "log.action.new.from.plural",
                AllIcons.General.Add
            ) {
            }
            val actionNonEmpty = emptyAndDumbAwareAction(
                listOf("item"),
                "log.action.new.from.singular",
                AllIcons.General.Add
            ) {
            }

            // EmptyAndDumbAwareAction.update() disables action when target.isEmpty()
            actionEmpty.target.isEmpty() shouldBe true // will be disabled
            actionNonEmpty.target.isEmpty() shouldBe false // will be enabled
        }

        @Test
        fun `preserves list reference`() {
            val items = listOf("x", "y", "z")
            val action = emptyAndDumbAwareAction(items, "log.action.new.from.plural", AllIcons.General.Add) {}

            // Should be the same reference
            (action.target === items) shouldBe true
        }
    }

    @Nested
    inner class `ActionContext` {
        @Test
        fun `data class holds target`() {
            val context = ActionContext("my-target", mockEvent(), mockLogger())

            context.target shouldBe "my-target"
        }

        @Test
        fun `data class equality works`() {
            val event = mockEvent()
            val logger = mockLogger()
            val context1 = ActionContext("target", event, logger)
            val context2 = ActionContext("target", event, logger)

            context1 shouldBe context2
        }

        private fun mockEvent() = io.mockk.mockk<com.intellij.openapi.actionSystem.AnActionEvent>(relaxed = true)
        private fun mockLogger() = io.mockk.mockk<com.intellij.openapi.diagnostic.Logger>(relaxed = true)
    }
}
