package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StringExtensionsTest {
    @Test
    fun `splitByComma returns empty list for empty string`() {
        val result = "".splitByComma { it }
        result.shouldBeEmpty()
    }

    @Test
    fun `splitByComma splits single element`() {
        val result = "apple".splitByComma { it }
        result shouldBe listOf("apple")
    }

    @Test
    fun `splitByComma splits multiple elements`() {
        val result = "apple,banana,cherry".splitByComma { it }
        result shouldBe listOf("apple", "banana", "cherry")
    }

    @Test
    fun `splitByComma applies transform function`() {
        val result = "1,2,3,4,5".splitByComma { it.toInt() }
        result shouldBe listOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `splitByComma handles elements with whitespace`() {
        val result = "foo, bar, baz".splitByComma { it }
        result shouldBe listOf("foo", " bar", " baz")
    }

    @Test
    fun `splitByComma with trim transform`() {
        val result = "foo, bar, baz".splitByComma { it.trim() }
        result shouldBe listOf("foo", "bar", "baz")
    }

    @Test
    fun `splitByComma handles empty elements between commas`() {
        val result = "a,,b".splitByComma { it }
        result shouldBe listOf("a", "", "b")
    }

    @Test
    fun `splitByComma with complex transform`() {
        val result = "hello,world,test".splitByComma { it.uppercase() }
        result shouldBe listOf("HELLO", "WORLD", "TEST")
    }

    @Test
    fun `splitByComma with single comma returns two empty strings`() {
        val result = ",".splitByComma { it }
        result shouldBe listOf("", "")
    }

    @Test
    fun `splitByComma with trailing comma`() {
        val result = "a,b,".splitByComma { it }
        result shouldBe listOf("a", "b", "")
    }

    @Test
    fun `splitByComma with leading comma`() {
        val result = ",a,b".splitByComma { it }
        result shouldBe listOf("", "a", "b")
    }
}
