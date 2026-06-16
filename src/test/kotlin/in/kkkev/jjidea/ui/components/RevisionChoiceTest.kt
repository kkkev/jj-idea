package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.RevisionExpression
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RevisionChoiceTest {
    @Test
    fun `FreeForm resolves to a RevisionExpression of the typed text`() {
        val choice = RevisionChoice.FreeForm("zkptqxyz")
        choice.revision shouldBe RevisionExpression("zkptqxyz")
        choice.displayName shouldBe "zkptqxyz"
    }
}
