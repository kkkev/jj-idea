package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RevsetErrorPositionTest {
    private fun error(line: Int, column: Int) =
        """
        Error: Failed to parse revset: Syntax error
        Caused by:  --> $line:$column
          |
        $line | heads(
          |       ^---
          |
          = expected <strict_identifier> or <expression>
        Hint: See https://docs.jj-vcs.dev/latest/revsets/ or use `jj help -k revsets` for revsets syntax.
        """.trimIndent()

    @Test
    fun `single line error`() {
        revsetErrorPosition(error(1, 7)) shouldBe (1 to 7)
    }

    @Test
    fun `multi-line error reports the line`() {
        revsetErrorPosition(error(2, 7)) shouldBe (2 to 7)
    }

    @Test
    fun `message without position`() {
        revsetErrorPosition("Revision `x` doesn't exist") shouldBe null
    }
}
