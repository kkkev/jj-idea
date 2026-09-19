package `in`.kkkev.jjidea.ui.editor

import com.intellij.openapi.vcs.merge.MergeData
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConflictBannerModelTest {
    private fun conflict(
        currentTitle: String? = null,
        lastTitle: String? = null,
        currentIsJjSide1: Boolean = true
    ) = ExtractedConflict(
        mergeData = MergeData(),
        currentTitle = currentTitle,
        lastTitle = lastTitle,
        currentIsJjSide1 = currentIsJjSide1
    )

    @Test
    fun `null conflict - no accept actions offered`() {
        val model = conflictBannerModel(null)

        model.acceptCurrent.shouldBeNull()
        model.acceptLast.shouldBeNull()
    }

    @Test
    fun `labelled conflict - uses jj's own labels and the ours-theirs tools`() {
        val model = conflictBannerModel(
            conflict(
                currentTitle = "ulmlywnv \"my change\" (rebased revision)",
                lastTitle = "abc123 (rebase destination)"
            )
        )

        model.acceptCurrent!!.label shouldBe "ulmlywnv \"my change\" (rebased revision)"
        model.acceptCurrent.tool shouldBe ":ours"
        model.acceptLast!!.label shouldBe "abc123 (rebase destination)"
        model.acceptLast.tool shouldBe ":theirs"
    }

    @Test
    fun `no title text - falls back to Side #1 - Side #2, same as the merge tool pane titles`() {
        val model = conflictBannerModel(conflict())

        model.acceptCurrent!!.label shouldBe "Side #1"
        model.acceptLast!!.label shouldBe "Side #2"
    }

    @Test
    fun `reoriented conflict (GitHub #112) - tool mapping flips with currentIsJjSide1`() {
        val model = conflictBannerModel(conflict(currentIsJjSide1 = false))

        model.acceptCurrent!!.tool shouldBe ":theirs"
        model.acceptLast!!.tool shouldBe ":ours"
    }

    @Test
    fun `textFor - singular for exactly one block`() {
        conflictBannerModel(null).textFor(1) shouldBe "1 conflict remaining:"
    }

    @Test
    fun `textFor - plural for more than one block`() {
        conflictBannerModel(null).textFor(3) shouldBe "3 conflicts remaining:"
    }

    @Test
    fun `displayLabel - short label passes through unchanged`() {
        conflict(currentTitle = "Side #1").let(::conflictBannerModel)
            .acceptCurrent!!.displayLabel shouldBe "Side #1"
    }

    @Test
    fun `displayLabel - long jj label is truncated for the banner, full text kept in label`() {
        val long = "mnuwlyrx 572656e8 \"a fairly long description of side B\""
        val model = conflictBannerModel(conflict(currentTitle = long))

        model.acceptCurrent!!.label shouldBe long
        model.acceptCurrent.displayLabel shouldBe truncateForBanner(long, 24)
        model.acceptCurrent.displayLabel.length shouldBe 24
    }

    @Test
    fun `truncateForBanner - text at or under the limit is unchanged`() {
        truncateForBanner("exactly ten", 11) shouldBe "exactly ten"
        truncateForBanner("short", 24) shouldBe "short"
    }

    @Test
    fun `truncateForBanner - over the limit is cut with a trailing ellipsis`() {
        val result = truncateForBanner("this text is much too long to fit", 10)

        result shouldBe "this text…"
        result.length shouldBe 10
    }

    @Test
    fun `truncateForBanner - trims whitespace left dangling right before the ellipsis`() {
        truncateForBanner("one two three", 5) shouldBe "one…"
    }
}
