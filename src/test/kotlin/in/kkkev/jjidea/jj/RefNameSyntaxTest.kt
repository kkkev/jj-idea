package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RefNameSyntaxTest {
    @Test
    fun `names jj accepts are valid`() {
        listOf(
            "main",
            "feature/x",
            "a.b",
            "a-b",
            "a+b",
            "a--b",
            "_x",
            "1",
            "a*b",
            "é",
            "日本",
            "v1.2.3",
            "\"q&b\"",
            "'raw&b'",
            "\"esc\\\"aped\""
        ).forEach { refNameSyntaxError(it) shouldBe null }
    }

    @Test
    fun `invalid characters report the character and its index`() {
        refNameSyntaxError("a&b") shouldBe RefNameSyntaxError.InvalidChar("&", 1)
        refNameSyntaxError("a b") shouldBe RefNameSyntaxError.InvalidChar(" ", 1)
        refNameSyntaxError("a@origin") shouldBe RefNameSyntaxError.InvalidChar("@", 1)
        listOf("a|b", "a~", "a:b", "a,b", "a(b", "a^", "a=b", "a!b", "a?b", "a#b", "a\\b", "a;b", "a[b", "a`b")
            .forEach {
                refNameSyntaxError(it) shouldBe
                    RefNameSyntaxError.InvalidChar(
                        it.first { c ->
                            !c.isLetter()
                        }.toString(),
                        it.indexOfFirst { c -> !c.isLetter() }
                    )
            }
    }

    @Test
    fun `dangling or mixed separators are rejected`() {
        refNameSyntaxError("a.") shouldBe RefNameSyntaxError.DanglingSeparator(1)
        refNameSyntaxError(".a") shouldBe RefNameSyntaxError.DanglingSeparator(0)
        refNameSyntaxError("-x") shouldBe RefNameSyntaxError.DanglingSeparator(0)
        refNameSyntaxError("ab-") shouldBe RefNameSyntaxError.DanglingSeparator(2)
        refNameSyntaxError("a..b") shouldBe RefNameSyntaxError.DanglingSeparator(2)
        refNameSyntaxError("a++b") shouldBe RefNameSyntaxError.DanglingSeparator(2)
        refNameSyntaxError("a.-b") shouldBe RefNameSyntaxError.DanglingSeparator(2)
        refNameSyntaxError("a-+b") shouldBe RefNameSyntaxError.DanglingSeparator(2)
        refNameSyntaxError("-") shouldBe RefNameSyntaxError.DanglingSeparator(0)
    }

    @Test
    fun `quoted literals must be complete`() {
        refNameSyntaxError("\"unterminated") shouldBe RefNameSyntaxError.UnterminatedQuote
        refNameSyntaxError("'raw") shouldBe RefNameSyntaxError.UnterminatedQuote
        refNameSyntaxError("\"a\"b") shouldBe RefNameSyntaxError.InvalidChar("b", 3)
    }

    @Test
    fun `empty name`() {
        refNameSyntaxError("") shouldBe RefNameSyntaxError.Empty
    }
}
