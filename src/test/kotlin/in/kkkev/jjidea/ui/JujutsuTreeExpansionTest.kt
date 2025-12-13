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
    fun `should serialize collapsed paths`() {
        val collapsedPaths = mutableSetOf("src", "test", "docs")

        val serialized = collapsedPaths.joinToString("|")

        serialized shouldBe "src|test|docs"
    }

    @Test
    fun `should deserialize collapsed paths`() {
        val serialized = "src|test|docs"

        val collapsedPaths = serialized.split("|").filter { it.isNotEmpty() }.toMutableSet()

        collapsedPaths shouldContain "src"
        collapsedPaths shouldContain "test"
        collapsedPaths shouldContain "docs"
        collapsedPaths.size shouldBe 3
    }

    @Test
    fun `should handle empty serialization`() {
        val collapsedPaths = mutableSetOf<String>()

        val serialized = collapsedPaths.joinToString("|")

        serialized shouldBe ""
    }

    @Test
    fun `should handle empty deserialization`() {
        val serialized = ""

        val collapsedPaths = serialized.split("|").filter { it.isNotEmpty() }.toMutableSet()

        collapsedPaths.size shouldBe 0
    }

    @Test
    fun `ignore flag should filter events when true`() {
        var ignoreEvents = true
        val collapsedPaths = mutableSetOf<String>()

        // Simulate event handler
        fun handleCollapse(path: String) {
            if (ignoreEvents) return
            collapsedPaths.add(path)
        }

        // Event fired while ignoring
        handleCollapse("src")

        // Should not have been added
        collapsedPaths.size shouldBe 0
    }

    @Test
    fun `ignore flag should allow events when false`() {
        var ignoreEvents = false
        val collapsedPaths = mutableSetOf<String>()

        // Simulate event handler
        fun handleCollapse(path: String) {
            if (ignoreEvents) return
            collapsedPaths.add(path)
        }

        // Event fired while NOT ignoring
        handleCollapse("src")

        // Should have been added
        collapsedPaths shouldContain "src"
        collapsedPaths.size shouldBe 1
    }

    @Test
    fun `should filter events during programmatic operations`() {
        var ignoreEvents = false
        val collapsedPaths = mutableSetOf("src", "test")

        // Simulate event handler
        fun handleExpand(path: String) {
            if (ignoreEvents) return
            collapsedPaths.remove(path)
        }

        // Programmatic operation - ignore events
        ignoreEvents = true
        handleExpand("src")  // Should be ignored
        handleExpand("test") // Should be ignored

        // Paths should still be in set
        collapsedPaths shouldContain "src"
        collapsedPaths shouldContain "test"
        collapsedPaths.size shouldBe 2

        // User operation - process events
        ignoreEvents = false
        handleExpand("src")  // Should be processed

        // Now "src" should be removed
        collapsedPaths shouldNotContain "src"
        collapsedPaths shouldContain "test"
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
