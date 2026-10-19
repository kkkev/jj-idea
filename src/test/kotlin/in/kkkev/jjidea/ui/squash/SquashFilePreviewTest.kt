package `in`.kkkev.jjidea.ui.squash

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SquashFilePreviewTest {
    @Test
    fun `computePreviewAfterContent unticked returns before content (nothing moves)`() {
        val content = computePreviewAfterContent(
            isIncluded = false,
            override = null,
            before = "before\n",
            after = "after\n"
        )
        content shouldBe "before\n"
    }

    @Test
    fun `computePreviewAfterContent ticked returns after content (whole file moves)`() {
        val content = computePreviewAfterContent(
            isIncluded = true,
            override = null,
            before = "before\n",
            after = "after\n"
        )
        content shouldBe "after\n"
    }

    @Test
    fun `computePreviewAfterContent override wins regardless of tick`() {
        val content = computePreviewAfterContent(
            isIncluded = false,
            override = "partial\n",
            before = "before\n",
            after = "after\n"
        )
        content shouldBe "partial\n"
    }
}
