package `in`.kkkev.jjidea.ui.squash

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    @Test
    fun `describeSquashState labels unticked state as destination unchanged`() {
        val (beforeTitle, afterTitle) = describeSquashState(
            content = "before\n",
            before = "before\n",
            after = "after\n"
        )
        beforeTitle shouldBe "Before"
        afterTitle shouldContain "unchanged"
    }

    @Test
    fun `describeSquashState labels ticked state as destination all changes, before constant`() {
        val (beforeTitle, afterTitle) = describeSquashState(
            content = "after\n",
            before = "before\n",
            after = "after\n"
        )
        beforeTitle shouldBe "Before"
        afterTitle shouldContain "all changes"
    }

    @Test
    fun `describeSquashState labels partial content as partial on the destination side`() {
        val (beforeTitle, afterTitle) = describeSquashState(
            content = "partial\n",
            before = "before\n",
            after = "after\n"
        )
        beforeTitle shouldBe "Before"
        afterTitle shouldContain "partial"
    }
}
