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
        val values = LogService.RefType.entries

        values shouldBe listOf(
            LogService.RefType.BOOKMARK,
            LogService.RefType.WORKING_COPY
        )
    }

    @Test
    fun `RefType valueOf works for BOOKMARK`() {
        LogService.RefType.valueOf("BOOKMARK") shouldBe LogService.RefType.BOOKMARK
    }

    @Test
    fun `RefType valueOf works for WORKING_COPY`() {
        LogService.RefType.valueOf("WORKING_COPY") shouldBe LogService.RefType.WORKING_COPY
    }
}
