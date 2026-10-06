package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.FragmentLayout
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import `in`.kkkev.jjidea.ui.components.Linkifier
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/** jj-idea-t04a (GitHub #91): descriptions start at one x regardless of change-id width. */
class LaidOutCellIdAlignmentTest {
    // Proportional font: widths genuinely differ per id
    private val font = Font(Font.SERIF, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val idOnly = JujutsuColumnManager().apply {
        showStatus = false
        showDecorations = false
    }
    private val withStatus = JujutsuColumnManager().apply { showDecorations = false }

    private fun entry(
        id: ChangeId,
        workingCopy: Boolean = false,
        immutable: Boolean = false,
        conflict: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = id,
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Same description",
        isWorkingCopy = workingCopy,
        immutable = immutable,
        hasConflict = conflict
    )

    private val entries = listOf(
        entry(ChangeId("qpvuntsm", "q")),
        entry(ChangeId("mmmmmmmm", "mmmmmmm")),
        entry(ChangeId("iiiilllm", "iiiil", 2)),
        entry(ChangeId("wwwwwwww", "ww"), workingCopy = true)
    )

    private fun cell(e: LogEntry, budget: LogRowBudget, cm: JujutsuColumnManager = idOnly) =
        LaidOutCell.forRow(e, 2_000, 0, cm, Linkifier.None, Color.BLACK, font, frc, budget)

    private fun descriptionStartX(e: LogEntry, budget: LogRowBudget, cm: JujutsuColumnManager = idOnly): Double {
        val fragments = cell(e, budget, cm).leftFragments
        val descIndex = fragments.indexOfFirst { it is Fragment.Text && it.text.startsWith("Same") }
        return fragments.take(descIndex).sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
    }

    @Test
    fun `without a budget description starts differ`() {
        (entries.map { descriptionStartX(it, LogRowBudget.NONE) }.distinct().size > 1) shouldBe true
    }

    @Test
    fun `with the shared budget every description starts at the same x`() {
        val budget = LogRowBudget.of(entries, font, frc)
        val starts = entries.map { descriptionStartX(it, budget) }
        val space = FragmentLayout.fragmentWidth(
            Fragment.Text(" ", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES, false),
            font,
            frc
        )
        starts.forEach { it shouldBe (budget.id + space).plusOrMinus(1e-6) }
    }

    @Test
    fun `no gap when the change id column is hidden`() {
        val cm = JujutsuColumnManager().apply {
            showStatus = false
            showDecorations = false
            showChangeId = false
        }
        val c = LaidOutCell.forRow(
            entries[0],
            2_000,
            0,
            cm,
            Linkifier.None,
            Color.BLACK,
            font,
            frc,
            LogRowBudget(0.0, 0.0, 0.0, 500.0)
        )
        c.leftFragments.map { it::class } shouldNotContain Fragment.Gap::class
    }

    @Test
    fun `budget measures each entry exactly once`() {
        var calls = 0
        LogRowBudget.of(entries, font, frc) { e, f, c ->
            calls++
            LaidOutCell.changeIdRunWidth(e, f, c)
        }
        calls shouldBe entries.size
    }

    private val statusEntries = listOf(
        entry(ChangeId("qpvuntsm", "qp"), immutable = true, conflict = true),
        entry(ChangeId("mzlkwxyq", "mz"), immutable = true),
        entry(ChangeId("kkkkkkkk", "kk"), conflict = true),
        entry(ChangeId("wwrtyuio", "wwr"))
    )

    private fun iconStartX(e: LogEntry, budget: LogRowBudget, nth: Int): Double? {
        val fragments = cell(e, budget, withStatus).leftFragments
        val index = fragments.withIndex().filter { it.value is Fragment.Icon }.getOrNull(nth)?.index ?: return null
        return fragments.take(index).sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
    }

    private fun idStartX(e: LogEntry, budget: LogRowBudget): Double {
        val fragments = cell(e, budget, withStatus).leftFragments
        val index = fragments.indexOfFirst { it is Fragment.Text && it.text == e.id.short }
        return fragments.take(index).sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
    }

    @Test
    fun `status icons, ids and descriptions each line up across rows`() {
        val budget = LogRowBudget.of(statusEntries, font, frc)
        statusEntries.map { idStartX(it, budget) }.distinct().size shouldBe 1
        statusEntries.map { descriptionStartX(it, budget, withStatus) }.distinct().size shouldBe 1
    }

    @Test
    fun `status slot is one icon wide when no commit has both icons`() {
        val noBoth = statusEntries.drop(1)
        val budget = LogRowBudget.of(noBoth, font, frc)
        budget.statusSlot shouldBe maxOf(budget.immutableIconWidth, budget.conflictIconWidth)
        // immutable-only and conflict-only icons start at the same x
        iconStartX(noBoth[0], budget, 0) shouldBe iconStartX(noBoth[1], budget, 0)
    }

    @Test
    fun `status slot widens to two icons only when a commit has both`() {
        val budget = LogRowBudget.of(statusEntries, font, frc)
        budget.statusSlot shouldBe (budget.immutableIconWidth + budget.conflictIconWidth).plusOrMinus(1e-6)
    }

    @Test
    fun `no status slot when no loaded entry shows an icon`() {
        LogRowBudget.of(listOf(statusEntries[3]), font, frc).statusSlot shouldBe 0.0
    }

    @Test
    fun `NONE budget leaves the status icons unpadded`() {
        cell(statusEntries[1], LogRowBudget.NONE, withStatus).leftFragments.map { it::class } shouldNotContain
            Fragment.Gap::class
    }
}
