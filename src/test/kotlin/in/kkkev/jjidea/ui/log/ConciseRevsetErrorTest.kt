package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class ConciseRevsetErrorTest {
    @Test
    fun `a syntax error keeps the headline, explanation and column but not the echoed revset`() {
        val message =
            """
            Error: Failed to parse revset: Syntax error
            Caused by:  --> 1:7
              |
            1 | (mine()) & (heads(
              |       ^---
              |
              = expected <strict_identifier> or <expression>
            Hint: See https://docs.jj-vcs.dev/latest/revsets/ or use `jj help -k revsets` for revsets syntax.
            """.trimIndent()

        val concise = conciseRevsetError(message)

        concise shouldBe "Failed to parse revset: Syntax error: expected <strict_identifier> or <expression> (column 7)"
        concise shouldNotContain "|"
        concise shouldNotContain "mine()"
    }

    @Test
    fun `a nested cause is preferred as the detail`() {
        val message =
            """
            Error: Failed to parse revset: Invalid string pattern
            Caused by:
            1:  --> 1:13
              |
            1 | description(substr:"x")
              |             ^--------^
              |
              = Invalid string pattern
            2: Invalid string pattern kind `substr:`
            Hint: Try prefixing with one of `exact:`
            """.trimIndent()

        conciseRevsetError(message) shouldBe
            "Failed to parse revset: Invalid string pattern: Invalid string pattern kind `substr:` (column 13)"
    }

    @Test
    fun `a plain one-line error is returned without its prefix`() {
        conciseRevsetError("Error: Revision `nosuchbookmark` doesn't exist") shouldBe
            "Revision `nosuchbookmark` doesn't exist"
    }
}
