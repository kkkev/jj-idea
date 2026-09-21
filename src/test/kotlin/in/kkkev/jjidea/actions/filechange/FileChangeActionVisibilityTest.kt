package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.file.CompareFileWithBranchAction
import `in`.kkkev.jjidea.actions.file.RestoreSelectionAction
import `in`.kkkev.jjidea.actions.file.ShowFileHistoryAction
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.actions.restorePaths
import `in`.kkkev.jjidea.actions.singleRepoForRestore
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import javax.swing.JTextArea
import javax.swing.JTree

/**
 * Tests that each file-change action shows/hides and enables/disables correctly
 * across the different UI contexts in which it can be invoked.
 *
 * The action system has 5 UI contexts, each providing different DataContext keys:
 * - **Details panel (historical entry)**: LOG_ENTRY (isWorkingCopy=false), CHANGES
 * - **Details panel (WC entry selected)**: LOG_ENTRY (isWorkingCopy=true), CHANGES,
 *   VIRTUAL_FILE/VIRTUAL_FILE_ARRAY (JujutsuChangesTree.showsLocalFiles is true here)
 * - **Working copy panel**: CHANGES, VIRTUAL_FILE/VIRTUAL_FILE_ARRAY (no LOG_ENTRY)
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
        every { event.getData(PlatformDataKeys.CONTEXT_COMPONENT) } returns null
    }

    private fun withLogEntry(entry: LogEntry) = also {
        every { event.getData(JujutsuDataKeys.LOG_ENTRY) } returns entry
    }
    private fun withChanges(vararg changes: Change) = also {
        every { event.getData(VcsDataKeys.CHANGES) } returns changes.toList().toTypedArray()
    }

    private fun historicalEntry(
        immutable: Boolean = false,
        parentCount: Int = 1,
        hasPushedAncestor: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId("abc123abc123", "abc1"),
        commitId = CommitId("abc0000000000000000000000000000000000000000"),
        underlyingDescription = "Test",
        isWorkingCopy = false,
        immutable = immutable,
        hasPushedAncestor = hasPushedAncestor,
        parentIds = (1..parentCount).map { ChangeId("par${it}23456789ab", "par$it") }
    )

    private fun workingCopyEntry(hasPushedAncestor: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId("wc1234567890", "wc12"),
        commitId = CommitId("wc000000000000000000000000000000000000000000"),
        underlyingDescription = "WC",
        isWorkingCopy = true,
        hasPushedAncestor = hasPushedAncestor
    )

    private fun jujutsuRevision(path: String): ContentRevision {
        val filePath = LocalFilePath("/project/$path", false)
        return mockk<ContentRevision> {
            every { file } returns filePath
            every { revisionNumber } returns ChangeIdRevisionNumber(ChangeId("abc123abc123", "abc1"))
            every { content } returns ""
        }
    }

    /** A Change whose after-revision is a historical (non-working-copy) version */
    private fun historicalChange(path: String): Change = Change(null, jujutsuRevision(path))

    /** A Change whose after-revision is the working copy */
    private fun workingCopyChange(path: String) = Change(
        null,
        CurrentContentRevision(LocalFilePath("/project/$path", false))
    )

    /** A Change with no after-revision — a deleted file, only reachable via [filePaths]'s `changes` fallback. */
    private fun deletedChange(path: String): Change = Change(jujutsuRevision(path), null)

    // ── OpenLocalFileAction ───────────────────────────────────────────────────

    @Nested
    inner class `OpenLocalFile` {
        @Test
        fun `hidden when no LOG_ENTRY (working copy panel context)`() {
            withChanges(historicalChange("Main.kt"))
            OpenLocalFileAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is working copy`() {
            withLogEntry(workingCopyEntry())
            withChanges(historicalChange("Main.kt"))
            OpenLocalFileAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `visible but disabled when historical entry with no changes and no file`() {
            withLogEntry(historicalEntry())
            OpenLocalFileAction().update(event)
            presentation.isVisible shouldBe true
            presentation.isEnabled shouldBe false
        }

        @Test
        fun `visible and enabled when historical entry with historical changes`() {
            withLogEntry(historicalEntry())
            withChanges(historicalChange("Main.kt"))
            OpenLocalFileAction().update(event)
            presentation.isVisible shouldBe true
            presentation.isEnabled shouldBe true
        }
    }

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

    // ── CompareBeforeWithBranchAction ─────────────────────────────────────────

    @Nested
    inner class `CompareBeforeWithBranch` {
        @Test
        fun `hidden when no LOG_ENTRY`() {
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithBranchAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when LOG_ENTRY is working copy`() {
            withLogEntry(workingCopyEntry())
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithBranchAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when entry has no parents (root commit)`() {
            withLogEntry(historicalEntry(parentCount = 0))
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithBranchAction().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `visible when historical entry with parents and changes`() {
            withLogEntry(historicalEntry(parentCount = 1))
            withChanges(historicalChange("Main.kt"))
            CompareBeforeWithBranchAction().update(event)
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

        @Test
        fun `resolves a deleted file's path for restoring (jj-idea-c2m8, GitHub #122)`() {
            // actionPerformed (RestoreToChangeAction.kt:42) reads restorePaths to build the
            // preSelected set - visible above via .changes alone, but restoring the actual file
            // depends on restorePaths surviving the changes-tree's empty VIRTUAL_FILE_ARRAY for a
            // deleted-only selection (see restorePaths's kdoc).
            withLogEntry(historicalEntry())
            every { event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns emptyArray()
            withChanges(deletedChange("Deleted.kt"))
            event.restorePaths shouldBe listOf(LocalFilePath("/project/Deleted.kt", false))
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

        @Nested
        inner class `deleted-file selection (jj-idea-c2m8, GitHub #122)` {
            // Real repo resolution (possibleJujutsuRepositoryFor -> VcsUtil.getVcsRootFor) needs a
            // live Application this test doesn't have, so - like the ShowFileHistory nested class
            // below - stub the accessors themselves rather than the changes-tree data they read.
            @BeforeEach
            fun setupStatics() = mockkStatic("in.kkkev.jjidea.actions.ActionEventExtensionsKt")

            @AfterEach
            fun teardown() = unmockkAll()

            @Test
            fun `enabled for a deleted-only working-copy selection`() {
                // The working-copy JujutsuChangesTree publishes an empty (not null)
                // VIRTUAL_FILE_ARRAY here, since a deleted file has no VirtualFile — see
                // JujutsuChangesTree.uiDataSnapshot and restorePaths's kdoc. That empty array used
                // to defeat the changes fallback entirely, hiding Restore.
                every { event.restorePaths } returns listOf(LocalFilePath("/project/Deleted.kt", false))
                every { event.singleRepoForRestore } returns repo
                RestoreSelectionAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }

            @Test
            fun `enabled for a mixed deleted+edited working-copy selection, restoring both`() {
                // Union, not choice: restorePaths must carry both paths, or the deleted file is
                // silently dropped from the operation even though the action is enabled.
                every { event.restorePaths } returns
                    listOf(LocalFilePath("/project/Deleted.kt", false), LocalFilePath("/project/Main.kt", false))
                every { event.singleRepoForRestore } returns repo
                RestoreSelectionAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }
        }
    }

    // ── OpenChangeFileAction ──────────────────────────────────────────────────

    @Nested
    inner class `OpenChangeFile` {
        @BeforeEach
        fun setupProject() {
            every { event.getData(CommonDataKeys.PROJECT) } returns mockk(relaxed = true)
        }

        @Test
        fun `disabled when no files and no changes with an after-revision`() {
            OpenChangeFileAction().update(event)
            presentation.isEnabled shouldBe false
        }

        @Test
        fun `disabled for a deleted-only selection (jj-idea-c2m8, GitHub #122)`() {
            // filesFor can't resolve a VirtualFile for a delete, so - unlike filePaths, which is
            // non-empty here since jj-idea-c2m8 - this must stay disabled rather than open nothing.
            every { event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns emptyArray()
            withChanges(deletedChange("Deleted.kt"))
            OpenChangeFileAction().update(event)
            presentation.isEnabled shouldBe false
        }

        @Test
        fun `enabled when a change has an after-revision`() {
            withChanges(historicalChange("Main.kt"))
            OpenChangeFileAction().update(event)
            presentation.isEnabled shouldBe true
        }

        @Test
        fun `enabled when VIRTUAL_FILE_ARRAY is non-empty`() {
            every { event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) } returns
                arrayOf(mockk<VirtualFile>(relaxed = true))
            OpenChangeFileAction().update(event)
            presentation.isEnabled shouldBe true
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

        @Test
        fun `visible when log entry is present`() {
            withLogEntry(historicalEntry())
            ShowDiffAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Nested
        inner class `keyboard shortcut place` {
            @BeforeEach
            fun setupPlace() {
                every { event.place } returns ActionPlaces.KEYBOARD_SHORTCUT
                mockkStatic("in.kkkev.jjidea.actions.ActionEventExtensionsKt")
            }

            @AfterEach
            fun teardown() = unmockkAll()

            @Test
            fun `disabled when only repo in context (no changes, no log entry)`() {
                every { event.repoForFile } returns repo
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe false
            }

            @Test
            fun `enabled when changes are present`() {
                every { event.repoForFile } returns repo
                withChanges(historicalChange("Main.kt"))
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }

            @Test
            fun `enabled when log entry is present`() {
                every { event.repoForFile } returns repo
                withLogEntry(historicalEntry())
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }

            @Test
            fun `disabled when focus is on an editable text component, even with changes present (jj-idea-qa8i)`() {
                // Enter is bound to this action globally, but it's also how a text field (e.g. the
                // Working Copy description box) inserts a newline — don't let the diff shortcut
                // steal that keystroke just because the surrounding panel's data context happens
                // to expose changes/a log entry.
                withChanges(historicalChange("Main.kt"))
                every { event.getData(PlatformDataKeys.CONTEXT_COMPONENT) } returns mockk<JTextArea> {
                    every { isEditable } returns true
                }
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe false
            }

            @Test
            fun `enabled when focus is on a non-editable text component`() {
                withChanges(historicalChange("Main.kt"))
                every { event.getData(PlatformDataKeys.CONTEXT_COMPONENT) } returns mockk<JTextArea> {
                    every { isEditable } returns false
                }
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }

            @Test
            fun `enabled when focus is on a non-text component, such as the changes tree`() {
                withChanges(historicalChange("Main.kt"))
                every { event.getData(PlatformDataKeys.CONTEXT_COMPONENT) } returns mockk<JTree>()
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }
        }

        @Nested
        inner class `non-keyboard place` {
            @BeforeEach
            fun setupPlace() {
                every { event.place } returns ActionPlaces.EDITOR_POPUP
                mockkStatic("in.kkkev.jjidea.actions.ActionEventExtensionsKt")
            }

            @AfterEach
            fun teardown() = unmockkAll()

            @Test
            fun `enabled when only repo in context (context menu)`() {
                every { event.repoForFile } returns repo
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }

            @Test
            fun `focus-on-text-component guard does not apply outside the keyboard shortcut place`() {
                withChanges(historicalChange("Main.kt"))
                every { event.getData(PlatformDataKeys.CONTEXT_COMPONENT) } returns mockk<JTextArea> {
                    every { isEditable } returns true
                }
                ShowDiffAction().update(event)
                presentation.isEnabledAndVisible shouldBe true
            }
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
        fun `visible when entry has multiple parents (merge commit)`() {
            withLogEntry(historicalEntry(immutable = false, parentCount = 2))
            SquashFilesAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
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
        fun `hidden when LOG_ENTRY is working copy with no pushed ancestor`() {
            withLogEntry(workingCopyEntry(hasPushedAncestor = false))
            withChanges(historicalChange("Main.kt"))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `visible when LOG_ENTRY is working copy with pushed ancestor`() {
            every { repo.cachedGitRemotes } returns listOf(GitRemote("origin", "https://github.com/user/repo.git"))
            withLogEntry(workingCopyEntry(hasPushedAncestor = true))
            withChanges(historicalChange("Main.kt"))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe true
        }

        @Test
        fun `hidden when no changes with afterRevision (no files selected)`() {
            // hasPushedAncestor=true so the only hiding condition is the absent changes
            withLogEntry(historicalEntry(hasPushedAncestor = true))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe false
        }

        @Test
        fun `hidden when entry has no pushed ancestor`() {
            withLogEntry(historicalEntry(hasPushedAncestor = false))
            withChanges(historicalChange("Main.kt"))
            OpenFileInRemoteGroup().update(event)
            presentation.isVisible shouldBe false
        }
    }

    // ── CompareFileWithBranchAction ───────────────────────────────────────────

    @Nested
    inner class `CompareWithBranch` {
        @Test
        fun `hidden when no file and no changes`() {
            CompareFileWithBranchAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }
    }

    // ── ShowFileHistoryAction (jj-idea-v9g4) ─────────────────────────────────

    @Nested
    inner class `ShowFileHistory` {
        @BeforeEach
        fun setupStatics() = mockkStatic("in.kkkev.jjidea.actions.ActionEventExtensionsKt")

        @AfterEach
        fun teardown() = unmockkAll()

        @Test
        fun `disabled when neither a VIRTUAL_FILE nor a changes-tree selection resolve`() {
            every { event.filePaths } returns emptyList()
            every { event.singleRepoForRestore } returns null
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `enabled in a changes-tree context (commit details panel, working copy panel)`() {
            // No VIRTUAL_FILE here - this is the bug: the action used to only ever read
            // CommonDataKeys.VIRTUAL_FILE, which a historical JujutsuChangesTree selection never
            // supplies (only a working-copy one does, via JujutsuChangesTree.showsLocalFiles).
            every { event.filePaths } returns listOf(LocalFilePath("/project/Main.kt", false))
            every { event.singleRepoForRestore } returns repo
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Test
        fun `enabled for a deleted file (no after-revision, only reachable via filePaths)`() {
            every { event.filePaths } returns listOf(LocalFilePath("/project/Deleted.kt", false))
            every { event.singleRepoForRestore } returns repo
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Test
        fun `enabled for a renamed file (single target path, not source+target)`() {
            // jj-idea-c2m8: filePaths (not restorePaths) is used here specifically so a rename's
            // single target path still passes the singleOrNull() check below.
            every { event.filePaths } returns listOf(LocalFilePath("/project/New.kt", false))
            every { event.singleRepoForRestore } returns repo
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Test
        fun `disabled when the changes-tree selection spans multiple repos`() {
            every { event.filePaths } returns
                listOf(LocalFilePath("/a/Main.kt", false), LocalFilePath("/b/Main.kt", false))
            every { event.singleRepoForRestore } returns null
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `disabled for a multi-file working-copy tree selection, even though VIRTUAL_FILE resolves a lead file`() {
            // A working-copy JujutsuChangesTree (showsLocalFiles = true) publishes VIRTUAL_FILE as
            // some single file out of a multi-file selection alongside VIRTUAL_FILE_ARRAY/CHANGES.
            // hasEditorTarget must defer to hasTreeTarget here rather than treat that lead file as
            // the whole (single-file) target - see the class kdoc.
            every { event.filePaths } returns
                listOf(LocalFilePath("/project/Main.kt", false), LocalFilePath("/project/Utils.kt", false))
            every { event.singleRepoForRestore } returns repo
            every { event.file } returns mockk<VirtualFile> { every { isDirectory } returns false }
            every { event.repoForFile } returns repo
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }

        @Test
        fun `enabled via VIRTUAL_FILE in the editor Project View context`() {
            every { event.filePaths } returns emptyList()
            every { event.singleRepoForRestore } returns null
            every { event.file } returns mockk<VirtualFile> { every { isDirectory } returns false }
            every { event.repoForFile } returns repo
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe true
        }

        @Test
        fun `disabled when VIRTUAL_FILE is a directory`() {
            every { event.filePaths } returns emptyList()
            every { event.singleRepoForRestore } returns null
            every { event.file } returns mockk<VirtualFile> { every { isDirectory } returns true }
            every { event.repoForFile } returns repo
            ShowFileHistoryAction().update(event)
            presentation.isEnabledAndVisible shouldBe false
        }
    }
}
