package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuLogService interface components (enums, simple data classes).
 * Note: Tests for JujutsuRef and CommitGraphNode are in the full test suite
 * as they depend on ChangeId which references IntelliJ Hash class.
 */
class JujutsuLogServiceTest {

    @Test
    fun `RefType enum has expected values`() {
        val values = JujutsuLogService.RefType.entries

        values shouldBe listOf(
            JujutsuLogService.RefType.BOOKMARK,
            JujutsuLogService.RefType.WORKING_COPY
        )
    }

    @Test
    fun `RefType valueOf works for BOOKMARK`() {
        JujutsuLogService.RefType.valueOf("BOOKMARK") shouldBe JujutsuLogService.RefType.BOOKMARK
    }

    @Test
    fun `RefType valueOf works for WORKING_COPY`() {
        JujutsuLogService.RefType.valueOf("WORKING_COPY") shouldBe JujutsuLogService.RefType.WORKING_COPY
    }
}
