package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JujutsuActionPromoterTest {
    private val showDiffAction = mockk<AnAction>(relaxed = true)
    private val compareAction = mockk<AnAction>(relaxed = true)
    private val duplicateAction = mockk<AnAction>(relaxed = true)
    private val newChangeAction = mockk<AnAction>(relaxed = true)
    private val gotoFileAction = mockk<AnAction>(relaxed = true)
    private val newScratchFileAction = mockk<AnAction>(relaxed = true)

    private val promoter = JujutsuActionPromoter { action ->
        when (action) {
            showDiffAction -> "Jujutsu.ShowChangesDiff"
            compareAction -> "Compare.SameVersion"
            duplicateAction -> "EditorDuplicate"
            newChangeAction -> "Jujutsu.NewChange"
            gotoFileAction -> "GotoFile"
            newScratchFileAction -> "NewScratchFile"
            else -> null
        }
    }

    private lateinit var context: DataContext

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        every { context.getData(VcsDataKeys.SELECTED_CHANGES) } returns null
        every { context.getData(VcsDataKeys.CHANGES) } returns null
        every { context.getData(JujutsuDataKeys.LOG_ENTRY) } returns null
        every { context.getData(JujutsuDataKeys.LOG_ENTRIES) } returns null
    }

    @Test
    fun `returns list unchanged when ShowChangesDiff not in list`() {
        val actions = listOf(duplicateAction, compareAction)
        promoter.promote(actions, context) shouldBe actions
    }

    @Test
    fun `returns list unchanged when no builtin diff action present`() {
        val actions = listOf(duplicateAction, showDiffAction)
        promoter.promote(actions, context) shouldBe actions
    }

    @Test
    fun `returns list unchanged when context has no VCS data`() {
        val actions = listOf(duplicateAction, compareAction, showDiffAction)
        promoter.promote(actions, context) shouldBe actions
    }

    @Test
    fun `promotes ShowChangesDiff when CHANGES in context`() {
        every { context.getData(VcsDataKeys.CHANGES) } returns arrayOf(mockk<Change>())
        val actions = listOf(duplicateAction, compareAction, showDiffAction)
        val result = promoter.promote(actions, context)
        result.first() shouldBe showDiffAction
    }

    @Test
    fun `promotes ShowChangesDiff when SELECTED_CHANGES in context`() {
        every { context.getData(VcsDataKeys.SELECTED_CHANGES) } returns arrayOf(mockk<Change>())
        val actions = listOf(duplicateAction, compareAction, showDiffAction)
        val result = promoter.promote(actions, context)
        result.first() shouldBe showDiffAction
    }

    @Test
    fun `promotes ShowChangesDiff when LOG_ENTRY in context`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val logEntry = LogEntry(
            repo = repo,
            id = ChangeId("abc123abc123", "abc1"),
            commitId = CommitId("abc0000000000000000000000000000000000000000"),
            underlyingDescription = "Test",
            isWorkingCopy = false
        )
        every { context.getData(JujutsuDataKeys.LOG_ENTRY) } returns logEntry
        val actions = listOf(duplicateAction, compareAction, showDiffAction)
        val result = promoter.promote(actions, context)
        result.first() shouldBe showDiffAction
    }

    @Test
    fun `preserves relative order of non-promoted actions`() {
        every { context.getData(VcsDataKeys.CHANGES) } returns arrayOf(mockk<Change>())
        val actions = listOf(duplicateAction, compareAction, showDiffAction)
        val result = promoter.promote(actions, context)
        result shouldBe listOf(showDiffAction, duplicateAction, compareAction)
    }

    @Test
    fun `returns list unchanged when NewChange not in list`() {
        val actions = listOf(gotoFileAction, newScratchFileAction)
        promoter.promote(actions, context) shouldBe actions
    }

    @Test
    fun `returns list unchanged when no builtin new-file action present`() {
        val actions = listOf(duplicateAction, newChangeAction)
        promoter.promote(actions, context) shouldBe actions
    }

    @Test
    fun `returns list unchanged when context has no log data`() {
        val actions = listOf(gotoFileAction, newScratchFileAction, newChangeAction)
        promoter.promote(actions, context) shouldBe actions
    }

    @Test
    fun `promotes NewChange over GotoFile when LOG_ENTRIES in context`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val entry = LogEntry(
            repo = repo,
            id = ChangeId("abc123abc123", "abc1"),
            commitId = CommitId("abc0000000000000000000000000000000000000000"),
            underlyingDescription = "Test",
            isWorkingCopy = false
        )
        every { context.getData(JujutsuDataKeys.LOG_ENTRIES) } returns listOf(entry)
        val actions = listOf(gotoFileAction, newScratchFileAction, newChangeAction)
        val result = promoter.promote(actions, context)
        result.first() shouldBe newChangeAction
    }

    @Test
    fun `promotes NewChange over NewScratchFile when LOG_ENTRY in context`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val entry = LogEntry(
            repo = repo,
            id = ChangeId("abc123abc123", "abc1"),
            commitId = CommitId("abc0000000000000000000000000000000000000000"),
            underlyingDescription = "Test",
            isWorkingCopy = false
        )
        every { context.getData(JujutsuDataKeys.LOG_ENTRY) } returns entry
        val actions = listOf(newScratchFileAction, newChangeAction)
        val result = promoter.promote(actions, context)
        result.first() shouldBe newChangeAction
    }

    @Test
    fun `promotes both ShowChangesDiff and NewChange independently when both contexts present`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val entry = LogEntry(
            repo = repo,
            id = ChangeId("abc123abc123", "abc1"),
            commitId = CommitId("abc0000000000000000000000000000000000000000"),
            underlyingDescription = "Test",
            isWorkingCopy = false
        )
        every { context.getData(JujutsuDataKeys.LOG_ENTRY) } returns entry
        val actions = listOf(duplicateAction, compareAction, gotoFileAction, showDiffAction, newChangeAction)
        val result = promoter.promote(actions, context)
        result.take(2).toSet() shouldBe setOf(showDiffAction, newChangeAction)
    }
}
