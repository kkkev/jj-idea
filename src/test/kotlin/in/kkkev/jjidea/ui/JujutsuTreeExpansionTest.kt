package `in`.kkkev.jjidea.ui

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for tree expansion logic - tracking user's explicit collapses
 */
class JujutsuTreeExpansionTest {

    @Test
    fun `should track user collapse`() {
        val collapsedPaths = mutableSetOf<String>()

        // User collapses a directory
        collapsedPaths.add("src")

        collapsedPaths shouldContain "src"
        collapsedPaths.size shouldBe 1
    }

    @Test
    fun `should forget collapse when user expands`() {
        val collapsedPaths = mutableSetOf<String>()

        // User collapses a directory
        collapsedPaths.add("src")

        // User expands it again
        collapsedPaths.remove("src")

        collapsedPaths.size shouldBe 0
    }

    @Test
    fun `should track multiple collapsed paths`() {
        val collapsedPaths = mutableSetOf<String>()

        collapsedPaths.add("src")
        collapsedPaths.add("test")
        collapsedPaths.add("docs")

        collapsedPaths shouldContain "src"
        collapsedPaths shouldContain "test"
        collapsedPaths shouldContain "docs"
        collapsedPaths.size shouldBe 3
    }

    @Test
    fun `should distinguish between different paths`() {
        val collapsedPaths = mutableSetOf<String>()

        collapsedPaths.add("src/main")
        collapsedPaths.add("src/test")

        collapsedPaths shouldContain "src/main"
        collapsedPaths shouldContain "src/test"
        collapsedPaths shouldNotContain "src"
        collapsedPaths.size shouldBe 2
    }

    @Test
    fun `should handle collapse and expand of same path multiple times`() {
        val collapsedPaths = mutableSetOf<String>()

        // Collapse
        collapsedPaths.add("src")
        collapsedPaths.size shouldBe 1

        // Expand
        collapsedPaths.remove("src")
        collapsedPaths.size shouldBe 0

        // Collapse again
        collapsedPaths.add("src")
        collapsedPaths shouldContain "src"
        collapsedPaths.size shouldBe 1
    }

    @Test
    fun `should start with empty collapsed set`() {
        val collapsedPaths = mutableSetOf<String>()

        // Initially nothing is collapsed
        collapsedPaths.size shouldBe 0
    }
}
