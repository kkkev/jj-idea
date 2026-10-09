package `in`.kkkev.jjidea.ui.common

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** jj-idea-v17o (GitHub #142): a long jj error must not force the log's minimum width. */
class StatusMessageLabelTest {
    private val message =
        "Error: The working copy is stale ${"x".repeat(400)}\nHint: run <jj workspace update-stale>"

    @Test
    fun `label can shrink to zero width`() {
        val label = statusMessageLabel(message)
        label.preferredSize.width shouldBeGreaterThan 500
        label.minimumSize.width shouldBe 0
    }

    @Test
    fun `text is single line and tooltip keeps every line escaped`() {
        val label = statusMessageLabel(message)
        label.text shouldNotContain "\n"
        label.toolTipText shouldContain "Error: The working copy is stale"
        label.toolTipText shouldContain "Hint: run &lt;jj workspace update-stale&gt;"
        label.toolTipText shouldContain "<br>"
    }
}
