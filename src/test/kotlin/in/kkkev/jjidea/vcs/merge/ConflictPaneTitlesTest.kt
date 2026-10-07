package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.vcs.merge.MergeData
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** GitHub #138: jj labels carry change descriptions; the platform renders pane titles as HTML. */
class ConflictPaneTitlesTest {
    private fun conflict(currentTitle: String?, lastTitle: String?) = ExtractedConflict(
        mergeData = MergeData(),
        currentTitle = currentTitle,
        lastTitle = lastTitle,
        currentIsJjSide1 = true
    )

    @Test
    fun `angle brackets and ampersands in labels are escaped`() {
        val titles = conflictPaneTitles(
            conflict("abc 123 \"fix <foo@bar>\" (rebased revision)", "x & y"),
            "Result"
        )

        titles[0] shouldBe "abc 123 &quot;fix &lt;foo@bar&gt;&quot; (rebased revision)"
        titles[2] shouldBe "x &amp; y"
    }

    @Test
    fun `null labels fall back to plain side numbers and the middle title passes through`() {
        conflictPaneTitles(conflict(null, null), "Result") shouldBe listOf("Side #1", "Result", "Side #2")
    }
}
