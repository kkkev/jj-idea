package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FileSpecTest {
    @Test
    fun `simple rename`() {
        val (old, new) = parseRenameSpec("{foo.txt => bar.txt}")
        old shouldBe "foo.txt"
        new shouldBe "bar.txt"
    }

    @Test
    fun `rename with shared prefix`() {
        val (old, new) = parseRenameSpec("foo/{bar.txt => bam.txt}")
        old shouldBe "foo/bar.txt"
        new shouldBe "foo/bam.txt"
    }

    @Test
    fun `rename with shared suffix`() {
        val (old, new) = parseRenameSpec("{foo => bar}/bar.txt")
        old shouldBe "foo/bar.txt"
        new shouldBe "bar/bar.txt"
    }

    @Test
    fun `move from parent to child directory - empty before side`() {
        val (old, new) = parseRenameSpec("pages/{ => blankExperience}/blankExperience.tsx")
        old shouldBe "pages/blankExperience.tsx"
        new shouldBe "pages/blankExperience/blankExperience.tsx"
    }

    @Test
    fun `move from child to parent directory - empty after side`() {
        val (old, new) = parseRenameSpec("pages/{blankExperience => }/blankExperience.tsx")
        old shouldBe "pages/blankExperience/blankExperience.tsx"
        new shouldBe "pages/blankExperience.tsx"
    }

    @Test
    fun `move from root parent to child directory`() {
        val (old, new) = parseRenameSpec("{ => child}/file.txt")
        old shouldBe "file.txt"
        new shouldBe "child/file.txt"
    }

    @Test
    fun `windows backslash separators - empty before side`() {
        val (old, new) = parseRenameSpec("src\\{ => child}\\file.txt")
        old shouldBe "src\\file.txt"
        new shouldBe "src\\child\\file.txt"
    }

    @Test
    fun `windows backslash separators - empty after side`() {
        val (old, new) = parseRenameSpec("src\\{child => }\\file.txt")
        old shouldBe "src\\child\\file.txt"
        new shouldBe "src\\file.txt"
    }

    @Test
    fun `windows backslash separators - both sides non-empty`() {
        val (old, new) = parseRenameSpec("src\\{foo.txt => bar.txt}")
        old shouldBe "src\\foo.txt"
        new shouldBe "src\\bar.txt"
    }

    @Test
    fun `both sides empty throws`() {
        assertThrows<IllegalArgumentException> { parseRenameSpec("foo/{ => }/bar.txt") }
    }

    @Test
    fun `no brace syntax throws`() {
        assertThrows<IllegalArgumentException> { parseRenameSpec("foo/bar.txt") }
    }
}
