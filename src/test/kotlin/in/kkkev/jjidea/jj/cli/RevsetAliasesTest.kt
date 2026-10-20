package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RevsetAliasesTest {
    @Test
    fun `accepts plain identifiers`() {
        listOf("mine", "_x", "Mine2", "a_b_c").forEach { isValidRevsetAliasName(it) shouldBe true }
    }

    @Test
    fun `rejects anything that is not a plain symbol`() {
        listOf("", "1bad", "a-b", "a.b", "a b", "f(x)", "a+", "né", "a\n").forEach {
            isValidRevsetAliasName(it) shouldBe false
        }
    }

    @Test
    fun `wraps plain text in quotes`() {
        tomlBasicString("main | @") shouldBe "\"main | @\""
    }

    @Test
    fun `escapes quotes and backslashes`() {
        tomlBasicString("""author(exact:"a@b")""") shouldBe """"author(exact:\"a@b\")""""
        tomlBasicString("""files("a\b")""") shouldBe """"files(\"a\\b\")""""
    }

    @Test
    fun `escapes control characters`() {
        tomlBasicString("a\nb\tc\r") shouldBe "\"a\\nb\\tc\\r\""
        tomlBasicString("\u0001") shouldBe "\"\\u0001\""
    }

    @Test
    fun `bare words that TOML would read as non-strings stay strings`() {
        tomlBasicString("true") shouldBe "\"true\""
        tomlBasicString("1") shouldBe "\"1\""
    }
}
