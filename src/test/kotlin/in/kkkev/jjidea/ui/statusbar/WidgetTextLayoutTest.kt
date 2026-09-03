package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.FragmentLayout
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * Pins the width-budget formula and description-only truncation behind
 * [JujutsuStatusBarWidget]'s width cap (jj-idea-6nas, GitHub #95).
 */
class WidgetTextLayoutTest {
    private val repo = mockk<JujutsuRepository>()
    private val changeId = ChangeId("qpvuntsm", "qp", 2)
    private val commitId = CommitId("abc123def456", "ab")
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)

    @Nested
    inner class `budget` {
        @Test
        fun `wide status bar returns the fixed scaled cap`() {
            WidgetTextLayout.budget(10_000) shouldBe JBUI.scale(WidgetTextLayout.MAX_PX).toDouble()
        }

        @Test
        fun `narrow status bar returns a proportional share of its width`() {
            WidgetTextLayout.budget(400) shouldBe 400 * WidgetTextLayout.MAX_FRACTION
        }

        @Test
        fun `non-positive width falls back to the fixed cap`() {
            WidgetTextLayout.budget(0) shouldBe JBUI.scale(WidgetTextLayout.MAX_PX).toDouble()
            WidgetTextLayout.budget(-5) shouldBe JBUI.scale(WidgetTextLayout.MAX_PX).toDouble()
        }
    }

    @Nested
    inner class `fit` {
        @Test
        fun `pathologically long description is truncated while the change id survives`() {
            val entry = LogEntry(
                repo = repo,
                id = changeId,
                commitId = commitId,
                underlyingDescription = "x ".repeat(200)
            )
            val budget = 150.0

            val fragments = WidgetTextLayout.fit(entry, budget, font, frc)

            // The change id's short prefix is not truncatable, so it survives verbatim.
            fragments.filterIsInstance<Fragment.Text>()
                .any { it.text.contains(changeId.shortenable.short) } shouldBe true
            // The description was shortened and ellipsized.
            val lastText = fragments.filterIsInstance<Fragment.Text>().last()
            lastText.text shouldEndWith "..."
            val totalWidth = fragments.sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
            (totalWidth <= budget) shouldBe true
        }

        @Test
        fun `short description within budget is unchanged`() {
            val entry = LogEntry(
                repo = repo,
                id = changeId,
                commitId = commitId,
                underlyingDescription = "short"
            )
            val budget = 10_000.0

            val fragments = WidgetTextLayout.fit(entry, budget, font, frc)

            fragments.filterIsInstance<Fragment.Text>().any { it.text == "short" } shouldBe true
            fragments.none { it is Fragment.Text && it.text.endsWith("...") } shouldBe true
        }
    }
}
