package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * Tests for [findPersonClickTarget] — the author/committer column hit-test behind clicking a log
 * row's author/committer name (jj-idea-iesq). Deliberately narrower than the whole cell: clicking
 * blank cell space to the right of a short name must not resolve to a click target, since
 * left-click on it launches the OS mail client.
 */
class PersonClickTargetTest {
    private val font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val frc = FontRenderContext(AffineTransform(), true, true)
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val alice = VcsUserImpl("Alice", "alice@example.com")
    private val bob = VcsUserImpl("Bob", "bob@example.com")

    private fun entry(
        author: VcsUserImpl? = alice,
        committer: VcsUserImpl? = null,
        isWorkingCopy: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        isWorkingCopy = isWorkingCopy,
        author = author,
        committer = committer
    )

    private fun nameWidth(name: String, bold: Boolean = false): Double {
        val f = if (bold) font.deriveFont(Font.BOLD) else font
        return f.getStringBounds(name, frc).width
    }

    @Test
    fun `click within the author name resolves to a PersonClick that can filter`() {
        val target = findPersonClickTarget(entry(), JujutsuLogTableModel.COLUMN_AUTHOR, 3, font, frc)

        target.shouldNotBeNull()
        target.user.email shouldBe "alice@example.com"
        target.canFilter shouldBe true
    }

    @Test
    fun `click past the end of the author name in blank cell space does not resolve`() {
        val past = nameWidth("Alice").toInt() + 20

        findPersonClickTarget(entry(), JujutsuLogTableModel.COLUMN_AUTHOR, past, font, frc).shouldBeNull()
    }

    @Test
    fun `click before the name's left inset does not resolve`() {
        findPersonClickTarget(entry(), JujutsuLogTableModel.COLUMN_AUTHOR, 0, font, frc).shouldBeNull()
    }

    @Test
    fun `committer column resolves to a PersonClick that cannot filter`() {
        val target = findPersonClickTarget(
            entry(author = alice, committer = bob),
            JujutsuLogTableModel.COLUMN_COMMITTER,
            3,
            font,
            frc
        )

        target.shouldNotBeNull()
        target.user.email shouldBe "bob@example.com"
        target.canFilter shouldBe false
    }

    @Test
    fun `committer column falls back to author when no committer is recorded`() {
        val target = findPersonClickTarget(
            entry(author = alice, committer = null),
            JujutsuLogTableModel.COLUMN_COMMITTER,
            3,
            font,
            frc
        )

        target.shouldNotBeNull()
        target.user.email shouldBe "alice@example.com"
        target.canFilter shouldBe false
    }

    @Test
    fun `blank email does not resolve`() {
        val noEmail = VcsUserImpl("Nameless", "")

        findPersonClickTarget(entry(author = noEmail), JujutsuLogTableModel.COLUMN_AUTHOR, 3, font, frc).shouldBeNull()
    }

    @Test
    fun `missing author does not resolve`() {
        findPersonClickTarget(entry(author = null), JujutsuLogTableModel.COLUMN_AUTHOR, 3, font, frc).shouldBeNull()
    }

    @Test
    fun `other columns never resolve`() {
        findPersonClickTarget(entry(), JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION, 3, font, frc).shouldBeNull()
        findPersonClickTarget(entry(), JujutsuLogTableModel.COLUMN_DATE, 3, font, frc).shouldBeNull()
    }

    @Test
    fun `working copy row hit-tests against the bold name width`() {
        // A proportional font (unlike MONOSPACED) renders bold visibly wider than plain - a click
        // just past the plain width must still resolve on the (bold-rendered) working-copy row,
        // matching UserCellRenderer's bold styling.
        val proportional = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val plainWidth = proportional.getStringBounds("Alice", frc).width.toInt()
        val boldWidth = proportional.deriveFont(Font.BOLD).getStringBounds("Alice", frc).width.toInt()
        require(boldWidth > plainWidth) { "test fixture assumption: bold text should be wider" }

        val target = findPersonClickTarget(
            entry(isWorkingCopy = true),
            JujutsuLogTableModel.COLUMN_AUTHOR,
            plainWidth + 1,
            proportional,
            frc
        )

        target.shouldNotBeNull()
    }
}
