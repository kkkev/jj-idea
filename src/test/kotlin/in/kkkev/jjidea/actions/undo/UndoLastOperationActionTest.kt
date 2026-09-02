package `in`.kkkev.jjidea.actions.undo

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.ui.services.JujutsuUndoService
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [resolveUndoLastOperationPresentation] - the pure logic behind
 * [UndoLastOperationAction]'s text/enabled/description, per contributing.md's action-
 * availability-hints tenet: disabled with a reason, never hidden, when there's nothing to undo.
 */
class UndoLastOperationActionTest {
    private val repo = mockk<JujutsuRepository>()

    @Test
    fun `no pending record - disabled with a generic label and a reason`() {
        val presentation = resolveUndoLastOperationPresentation(null)

        presentation.text shouldBe "Undo Last Jujutsu Operation"
        presentation.enabled shouldBe false
        presentation.description.shouldNotBeNull()
    }

    @Test
    fun `pending record - enabled and names the action`() {
        val record = repo to JujutsuUndoService.Record(OperationId("op1"), "Abandon")

        val presentation = resolveUndoLastOperationPresentation(record)

        presentation.text shouldBe "Undo Abandon"
        presentation.enabled shouldBe true
        presentation.description.shouldBeNull()
    }
}
