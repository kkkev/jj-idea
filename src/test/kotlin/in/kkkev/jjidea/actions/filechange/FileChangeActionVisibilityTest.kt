package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.file.CompareFileWithBranchAction
import `in`.kkkev.jjidea.actions.file.RestoreSelectionAction
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests that each file-change action shows/hides and enables/disables correctly
 * across the different UI contexts in which it can be invoked.
 *
 * The action system has 5 UI contexts, each providing different DataContext keys:
 * - **Details panel (historical entry)**: LOG_ENTRY (isWorkingCopy=false), CHANGES
 * - **Details panel (WC entry selected)**: LOG_ENTRY (isWorkingCopy=true), CHANGES
 * - **Working copy panel**: CHANGES only (no LOG_ENTRY)
 * - **Editor (current file)**: VIRTUAL_FILE, PROJECT (no LOG_ENTRY, no CHANGES)
 * - **Editor (historical file)**: VIRTUAL_FILE with VIRTUAL_FILE_LOG_ENTRY user data
 *
 * Actions visibility is verified by calling [action.update(event)] with a mock event
 * that provides the appropriate keys, then asserting [Presentation.isVisible] / [Presentation.isEnabled].
 */
class FileChangeActionVisibilityTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private lateinit var presentation: Presentation
    private lateinit var event: AnActionEvent

    @BeforeEach
    fun setup() {
        presentation = Presentation()
        event = mockk(relaxed = true)
        every { event.presentation } returns presentation
        every { event.getData(CommonDataKeys.PROJECT) } returns null
        every { event.getData(JujutsuDataKeys.LOG_ENTRY) } returns null
        every { event.getData(VcsDataKeys.SELECTED_CHANGES) } returns null
        every { event.getData(VcsDataKeys.CHANGES) } returns null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE) } returns null
        every { event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns null
    }

    private fun withLogEntry(entry: LogEntry) = also {
        every { event.getData(JujutsuDataKeys.LOG_ENTRY) } returns entry
    }
    private fun withChanges(vararg changes: Change) = also {
        every { event.getData(VcsDataKeys.CHANGES) } returns changes.toList().toTypedArray()
    }

    private fun historicalEntry(immutable: Boolean = false, parentCount: Int = 1) = LogEntry(
        repo = repo,
        id = ChangeId("abc123abc123", "abc1"),
        commitId = CommitId("abc0000000000000000000000000000000000000000"),
        underlyingDescription = "Test",
        isWorkingCopy = false,
        immutable = immutable,
        parentIdentifiers = (1..parentCount).map {
            LogEntry.Identifiers(
                ChangeId("par${it}23456789ab", "par$it"),
                CommitId("par${it}00000000000000000000000000000000000000000")
            )
        }
    )

    private fun workingCopyEntry() = LogEntry(
        repo = repo,
        id = ChangeId("wc1234567890", "wc12"),
        commitId = CommitId("wc000000000000000000000000000000000000000000"),
        underlyingDescription = "WC",
        isWorkingCopy = true
    )

    /** A Change whose after-revision is a historical (non-working-copy) version */
    private fun historicalChange(path: String): Change {
        val filePath = LocalFilePath("/project/$path", false)
        val revision = mockk<ContentRevision> {
            every { file } returns filePath
            every { revisionNumber } returns ChangeIdRevisionNumber(ChangeId("abc123abc123", "abc1"))
            every { content } returns ""
        }
        return Change(null, revision)
    }

    /** A Change whose after-revision is the working copy */
    private fun workingCopyChange(path: String) = Change(
        null,
        CurrentContentRevision(LocalFilePath("/project/$path", false))
    )

    // ── CompareWithLocalAction ────────────────────────────────────────────────

    @Nested
    inner class `CompareWithLocal` {
        @Test
        fun `hidden when no LOG_ENTRY (working copy panel context)`() {
            CompareWithLocalAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is working copy (details WC context)`() {
            withLogEntry(workingCopyEntry())
            CompareWithLocalAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `visible but disabled when historical entry with no changes selected`() {
            withLogEntry(historicalEntry())
            CompareWithLocalAction().update(event)
            presentation.isVisible shouldBe true
            presentation.isEnabled shouldBe false
        }

        @Test
        fun `visible and enabled when historical entry with historical changes`() {
            withLogEntry(historicalEntry())
            withChanges(historicalChange("Main.kt"))
            CompareWithLocalAction().update(event)
            presentation.isVisible shouldBe true
            presentation.isEnabled shouldBe true
        }

        @Test
        fun `visible but disabled when changes are working copy`() {
            withLogEntry(historicalEntry())
            withChanges(workingCopyChange("Main.kt"))
            CompareWithLocalAction().update(event)
            presentation.isVisible shouldBe true
            presentation.isEnabled shouldBe false
        }
    }

    // ── CompareBeforeWithLocalAction ──────────────────────────────────────────

    @Nested
    inner class `CompareBeforeWithLocal` {
        @Test
        fun `hidden when no LOG_ENTRY`() {
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithLocalAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is working copy`() {
            withLogEntry(workingCopyEntry())
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithLocalAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when entry has no parents (root commit)`() {
            withLogEntry(historicalEntry(parentCount = 0))
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithLocalAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `visible when historical entry with parents and changes`() {
            withLogEntry(historicalEntry(parentCount = 1))
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithLocalAction().update(event)
            presentation.isVisible shouldBe true
        }
    }

    // ── RestoreToChangeAction ─────────────────────────────────────────────────

    @Nested
    inner class `RestoreToChange` {
        @Test
        fun `hidden when no LOG_ENTRY (working copy panel context)`() {
            withChanges(historicalChange("Main.kt"))
            RestoreToChangeAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is working copy`() {
            withLogEntry(workingCopyEntry())
            withChanges(historicalChange("Main.kt"))
            RestoreToChangeAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `hidden when no changes selected`() {
            withLogEntry(historicalEntry())
            RestoreToChangeAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `visible and enabled when historical entry with changes (details panel context)`() {
            withLogEntry(historicalEntry())
            withChanges(historicalChange("Main.kt"))
            RestoreToChangeAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }
    }

    // ── RestoreSelectionAction ────────────────────────────────────────────────

    @Nested
    inner class `RestoreSelection` {
        @Test
        fun `hidden when LOG_ENTRY is historical (use RestoreToChange instead)`() {
            withLogEntry(historicalEntry())
            RestoreSelectionAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is historical even with changes`() {
            withLogEntry(historicalEntry())
            withChanges(historicalChange("Main.kt"))
            RestoreSelectionAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `disabled when no LOG_ENTRY and no file resolution (empty context)`() {
            // No log entry, no files, no changes — cannot resolve repo → disabled
            RestoreSelectionAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }
    }

    // ── ShowDiffAction ────────────────────────────────────────────────────────

    @Nested
    inner class `ShowDiff` {
        @Test
        fun `hidden when no changes and no file`() {
            ShowDiffAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `visible when changes are present`() {
            withChanges(historicalChange("Main.kt"))
            ShowDiffAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }
    }

    // ── SquashFilesAction ─────────────────────────────────────────────────────

    @Nested
    inner class `SquashFiles` {
        @Test
        fun `hidden when no LOG_ENTRY and no repo resolution`() {
            SquashFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `visible when mutable historical entry with single parent`() {
            withLogEntry(historicalEntry(immutable = false, parentCount = 1))
            SquashFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Test
        fun `hidden when entry is immutable`() {
            withLogEntry(historicalEntry(immutable = true, parentCount = 1))
            SquashFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `hidden when entry has multiple parents (merge commit)`() {
            withLogEntry(historicalEntry(immutable = false, parentCount = 2))
            SquashFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `hidden when entry has no parents (root commit)`() {
            withLogEntry(historicalEntry(immutable = false, parentCount = 0))
            SquashFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }
    }

    // ── SplitFilesAction ──────────────────────────────────────────────────────

    @Nested
    inner class `SplitFiles` {
        @Test
        fun `hidden when no LOG_ENTRY and no repo resolution`() {
            SplitFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `visible when mutable historical entry`() {
            withLogEntry(historicalEntry(immutable = false))
            SplitFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Test
        fun `hidden when entry is immutable`() {
            withLogEntry(historicalEntry(immutable = true))
            SplitFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }
    }

    // ── OpenFileInRemoteGroup ─────────────────────────────────────────────────

    @Nested
    inner class `OpenFileInRemote` {
        @Test
        fun `hidden when no LOG_ENTRY`() {
            withChanges(historicalChange("Main.kt"))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is working copy`() {
            withLogEntry(workingCopyEntry())
            withChanges(historicalChange("Main.kt"))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when no changes with afterRevision (immutable entry, no files selected)`() {
            // Use immutable=true so hasPushedAncestor() returns true immediately,
            // isolating the "no changes" condition as the reason for hiding
            withLogEntry(historicalEntry(immutable = true))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe false
        }
    }

    // ── CompareFileWithBranchAction ───────────────────────────────────────────

    @Nested
    inner class `CompareWithBranch` {
        /**
         * CompareWithBranchAction checks [AnActionEvent.repoForFile] which requires VIRTUAL_FILE.
         * In changes tree context only CHANGES are available — so the action is invisible.
         * See: bug tracked in beads for this limitation.
         */
        @Test
        fun `hidden in changes tree context because VIRTUAL_FILE is absent`() {
            withLogEntry(historicalEntry())
            withChanges(historicalChange("Main.kt"))
            CompareFileWithBranchAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }
    }
}
