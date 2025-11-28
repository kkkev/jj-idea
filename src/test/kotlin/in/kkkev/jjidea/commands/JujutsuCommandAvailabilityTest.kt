package `in`.kkkev.jjidea.commands

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

/**
 * Tests to verify jj command is available and working
 * These tests only run if jj is installed on the system
 */
class JujutsuCommandAvailabilityTest {

    private val executor = JujutsuCliExecutor()

    companion object {
        @JvmStatic
        fun isJjAvailable(): Boolean {
            return try {
                val executor = JujutsuCliExecutor()
                executor.isAvailable()
            } catch (e: Exception) {
                false
            }
        }
    }

    @Test
    @EnabledIf("isJjAvailable")
    fun `jj command is available`() {
        val available = executor.isAvailable()
        available shouldBe true
    }

    @Test
    @EnabledIf("isJjAvailable")
    fun `jj version returns valid version string`() {
        val version = executor.version()
        version shouldContain "jj"
    }

    @Test
    fun `jj unavailable returns false gracefully`() {
        // This test should always pass - just checking error handling
        val executor = JujutsuCliExecutor("jj-nonexistent-binary")
        val available = executor.isAvailable()
        available shouldBe false
    }
}
