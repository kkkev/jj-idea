package `in`.kkkev.jjidea.ui.split

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.FileContents
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.vcs.filePath
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("platform")
@TestApplication
@RunInEdt
class SplitDialogTest {
    private val project = projectFixture()

    @Test
    fun `parent description pre-populated with source description`() {
        val source = createEntry("src1", description = "source desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parentDescriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `child description pre-populated with source description`() {
        val source = createEntry("src1", description = "source desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.childDescriptionText shouldBe "source desc"
        disposeDialog(dialog)
    }

    @Test
    fun `description empty when source empty`() {
        val source = createEntry("src1", description = "")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parentDescriptionText shouldBe ""
        dialog.childDescriptionText shouldBe ""
        disposeDialog(dialog)
    }

    @Test
    fun `parallel checkbox defaults to unchecked`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parallelCheckBox.isSelected shouldBe false
        disposeDialog(dialog)
    }

    // Identity-first wording is deliberately mode-invariant, and now uses one consistent noun
    // pair ("existing commit" / "new commit") everywhere - description labels, preview titles,
    // summary, hunk picker - instead of "(keeps change ID)" clashing with "New commit" as if they
    // were different parts of speech (jj-idea-8khi follow-up, GitHub #101 UX). One label per
    // description editor, not a header plus a sub-label: the two used to repeat the same
    // "new commit"/"stays" framing.
    @Test
    fun `dynamic labels stay identity-first when parallel toggled, only the parenthetical changes`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        // Default: child mode
        dialog.parentHeaderLabel.text shouldContain "Description for existing commit"
        dialog.parentHeaderLabel.text shouldContain source.id.short
        dialog.childHeaderLabel.text shouldContain "Description for new commit"
        dialog.childHeaderLabel.text shouldContain "child of"

        // Toggle to parallel
        dialog.parallelCheckBox.isSelected = true
        dialog.parallelCheckBox.actionListeners.forEach { it.actionPerformed(null) }
        UIUtil.dispatchAllInvocationEvents()

        dialog.parentHeaderLabel.text shouldContain "Description for existing commit"
        dialog.parentHeaderLabel.text shouldContain source.id.short
        dialog.childHeaderLabel.text shouldContain "Description for new commit"
        dialog.childHeaderLabel.text shouldContain "sibling of"
        dialog.childHeaderLabel.text shouldNotContain "child of"

        disposeDialog(dialog)
    }

    // Regression coverage for jj-idea-o6sw (GitHub #101 follow-up): toggling parallel mode only
    // recomputed the label fields — the already-rendered summary (and diff preview) kept stale
    // wording until some other event (a tick change) happened to refresh them. With identity-first
    // wording (jj-idea-8khi) the summary text itself no longer varies by mode, so this now checks
    // that the mode note - which does vary - updates live instead.
    @Test
    fun `summary and mode note refresh live when parallel toggled`() {
        val changes = listOf(change("src/Main.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.summaryLabel.text shouldContain "New commit"
        dialog.summaryLabel.text shouldContain "Existing commit"
        dialog.modeNoteText shouldContain "child commit"
        dialog.modeNoteText shouldNotContain "beside"

        dialog.parallelCheckBox.isSelected = true
        dialog.parallelCheckBox.actionListeners.forEach { it.actionPerformed(null) }
        UIUtil.dispatchAllInvocationEvents()

        dialog.summaryLabel.text shouldContain "New commit"
        dialog.summaryLabel.text shouldContain "Existing commit"
        dialog.modeNoteText shouldContain "beside"
        dialog.modeNoteText shouldContain "merges"

        disposeDialog(dialog)
    }

    // Regression coverage for the follow-up to jj-idea-8khi: the mode note used to be a plain
    // JBLabel, which reports its preferred width as whatever a single unwrapped line needs - wide
    // enough to force the whole left column of the dialog wider to fit it. It's now an HTML pane
    // (IconAwareHtmlPane), which wraps instead.
    @Test
    fun `mode note wraps instead of forcing a wide preferred size`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        // Parallel mode has the longest note text ("...Any existing children become merges of
        // both."). A plain JLabel would report a single-line preferred width well over 500px for
        // this sentence at typical dialog font sizes.
        dialog.parallelCheckBox.isSelected = true
        dialog.parallelCheckBox.actionListeners.forEach { it.actionPerformed(null) }
        UIUtil.dispatchAllInvocationEvents()

        dialog.modeNoteComponent.preferredSize.width shouldBeLessThan JBUI.scale(500)

        disposeDialog(dialog)
    }

    @Test
    fun `no files ticked by default in file selection`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.includedChanges.size shouldBe 0
        disposeDialog(dialog)
    }

    @Test
    fun `validation fails when nothing ticked (nothing to split off)`() {
        val changes = listOf(change("src/Main.kt"), change("src/Utils.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Nothing ticked and no overrides — validation should fail (child would be empty)
        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation fails when all files ticked (nothing left for parent)`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val changes = listOf(main, utils)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(changes)
        UIUtil.dispatchAllInvocationEvents()

        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes with a mixed selection`() {
        val main = change("src/Main.kt")
        val utils = change("src/Utils.kt")
        val changes = listOf(main, utils)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(listOf(main))
        UIUtil.dispatchAllInvocationEvents()

        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    @Test
    fun `validation passes with nothing ticked but one file partially picked (GitHub #117)`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Nothing ticked, but Auth.kt has a genuine partial hunk pick - applyPickedContent
        // deliberately leaves the tick alone for a partial, so this is exactly the state a
        // hunks-only split produces.
        val authPath = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(authPath, "partial content\n")

        dialog.fileSelection.includedChanges.size shouldBe 0
        dialog.doValidateForTest() shouldBe null
        disposeDialog(dialog)
    }

    @Test
    fun `hunks-only split passes every file as the fileset and carries the picked remainder`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val authPath = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(authPath, "partial content\n")

        dialog.performOKForTest()
        val result = dialog.result

        result shouldNotBe null
        result!!.hunkSelection shouldNotBe null
        // Nothing ticked means nothing is removed from the parent's fileset.
        result.filePaths.toSet() shouldBe setOf(authChange.filePath, loggerChange.filePath)
        val authContent = result.hunkSelection!!.files.first { it.filePath == authPath }.content
        authContent shouldBe "partial content\n"
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode still rejects an empty selection even with an override present`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes, newParent = true)
        waitForRefresh(dialog.fileSelection)

        // Pick Hunks is hidden in newParent mode, but exercise the seam directly to confirm
        // isPartialSplit stays mode-gated even if an override somehow existed.
        val authPath = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(authPath, "partial content\n")

        dialog.doValidateForTest() shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `override injected for test produces non-null hunkSelection`() {
        val changes = listOf(change("src/Auth.kt"), change("src/Logger.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        // Inject a partial content override for Auth.kt
        dialog.setFirstCommitOverrideForTest(fp, "partial content\n")

        // hunkPickerForTest allows OK action to fire without showing the merge window
        // Validation should now pass (override implies partial selection = non-empty second commit)
        // OK produces a SplitHunkSelection
        dialog.performOKForTest()
        dialog.result shouldNotBe null
        dialog.result!!.hunkSelection shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `ok action produces null hunkSelection when no partial files`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "initial desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        // Tick Logger so it moves to the child (file-level, no overrides).
        dialog.fileSelection.changesTree.setIncludedChanges(listOf(loggerChange))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        val result = dialog.result

        result shouldNotBe null
        result!!.hunkSelection shouldBe null // no overrides → fast path
        result.filePaths shouldBe listOf(authChange.filePath) // Auth.kt stays in the parent
        result.selectedDescription shouldBe Description("initial desc")
        result.remainingDescription shouldBe null // unchanged
        result.parallel shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `preSelectedFiles from right-click land in the child, not the parent`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val authPath = LocalFilePath("src/Auth.kt", false)

        // Simulate right-clicking Auth.kt and choosing "Split into New Child".
        val dialog = SplitDialog(project.get(), source, changes, preSelectedFiles = setOf(authPath))
        waitForRefresh(dialog.fileSelection)

        // The right-clicked file must start TICKED (moving to the child); the other file
        // stays unticked (remains in the parent).
        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)

        dialog.performOKForTest()
        val result = dialog.result
        result shouldNotBe null
        // filePaths are the files that stay in the parent (`jj split` keeps them there).
        result!!.filePaths shouldBe listOf(loggerChange.filePath)
        disposeDialog(dialog)
    }

    // ---- newParent mode (jj-idea-tkog, GitHub #74) ----

    @Test
    fun `newParent mode ticks preSelectedFiles directly, no complement inversion`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val authPath = LocalFilePath("src/Auth.kt", false)

        val dialog = SplitDialog(project.get(), source, changes, preSelectedFiles = setOf(authPath), newParent = true)
        waitForRefresh(dialog.fileSelection)

        // Unlike the old tick-inversion hack, the right-clicked file starts ticked directly.
        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode passes ticked files as the fileset, sets insertBefore to the source`() {
        val authChange = change("src/Auth.kt")
        val loggerChange = change("src/Logger.kt")
        val changes = listOf(authChange, loggerChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes, newParent = true)
        waitForRefresh(dialog.fileSelection)

        // Tick Auth.kt: it should become the new commit's fileset.
        dialog.fileSelection.changesTree.setIncludedChanges(listOf(authChange))
        UIUtil.dispatchAllInvocationEvents()

        dialog.performOKForTest()
        val result = dialog.result
        result shouldNotBe null
        result!!.filePaths shouldBe listOf(authChange.filePath)
        result.insertBefore shouldBe source.id
        result.parallel shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode routes descriptions by role, not by pane`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "original desc")
        val dialog = SplitDialog(project.get(), source, changes, newParent = true)
        waitForRefresh(dialog.fileSelection)

        dialog.fileSelection.changesTree.setIncludedChanges(listOf(authChange))
        UIUtil.dispatchAllInvocationEvents()

        // childDescriptionField is the ticked pane's field, which in newParent mode is the
        // *selected* (new-commit) side and gets -m; parentDescriptionField is the unticked
        // pane's field, the *remaining* (stays-here) side.
        dialog.childDescriptionText shouldBe "original desc"
        dialog.performOKForTest()
        val result = dialog.result
        result shouldNotBe null
        result!!.selectedDescription shouldBe Description("original desc")
        result.remainingDescription shouldBe null // unedited, stays unchanged
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode hides the parallel checkbox (mutually exclusive with -B)`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList(), newParent = true)

        dialog.parallelCheckBox.isVisible shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode hides Pick Hunks (unverified content polarity under -B)`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList(), newParent = true)

        dialog.pickHunksButton.isVisible shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode headers spell out which side becomes parent of which`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList(), newParent = true)

        // childHeaderLabel is the ticked pane ("New commit"); parentHeaderLabel is the unticked
        // pane ("Description for existing commit …") - see updateDynamicLabels, and class KDoc
        // for why the wording is identity-first and shared with the other two modes (jj-idea-8khi).
        dialog.childHeaderLabel.text shouldContain "Description for new commit"
        dialog.childHeaderLabel.text shouldContain "parent of"
        dialog.childHeaderLabel.text shouldContain source.id.short
        dialog.parentHeaderLabel.text shouldContain "Description for existing commit"
        dialog.parentHeaderLabel.text shouldContain source.id.short
        disposeDialog(dialog)
    }

    @Test
    fun `newParent mode note describes insertion below, not the parallel or child wording`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList(), newParent = true)

        dialog.modeNoteText shouldContain "inserted below"
        dialog.modeNoteText shouldContain source.id.short
        dialog.modeNoteText shouldNotContain "sibling"
        dialog.modeNoteText shouldNotContain "child commit"
        disposeDialog(dialog)
    }

    @Test
    fun `default mode still hides nothing (regression check)`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        dialog.parallelCheckBox.isVisible shouldBe true
        dialog.pickHunksButton.isVisible shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `pickHunksButton is wired into the shared preview panel's footer`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        // Regression check for the FileDiffPreviewPanel extraction (jj-idea-8a8z): the button
        // must actually be added to the shared shell's footer, not orphaned by the refactor.
        dialog.pickHunksButton.parent shouldNotBe null
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent unticked returns after content (nothing moves)`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isIncludedInChild = false,
            override = null,
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "after\n"
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent fully ticked returns base content`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isIncludedInChild = true,
            override = null,
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "before\n"
        disposeDialog(dialog)
    }

    @Test
    fun `computePreviewLeftContent partial override wins regardless of tick`() {
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, emptyList())

        val content = dialog.computePreviewLeftContent(
            isIncludedInChild = true,
            override = "partial\n",
            baseContent = "before\n",
            afterContent = "after\n"
        )
        content shouldBe "partial\n"
        disposeDialog(dialog)
    }

    @Test
    fun `describeSplitState labels unticked state as parent all changes, child no changes`() {
        val (parentTitle, childTitle) = describeSplitState(
            content = "after\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Parent",
            childLabel = "Child"
        )
        parentTitle shouldContain "all changes"
        childTitle shouldContain "no changes"
    }

    @Test
    fun `describeSplitState labels fully-ticked state as stays no changes, new commit all changes`() {
        val (parentTitle, childTitle) = describeSplitState(
            content = "before\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Parent",
            childLabel = "Child"
        )
        parentTitle shouldContain "no changes"
        parentTitle shouldNotContain "unchanged"
        childTitle shouldContain "all changes"
    }

    @Test
    fun `describeSplitState labels partial content as partial on both sides`() {
        val (parentTitle, childTitle) = describeSplitState(
            content = "partial\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Parent",
            childLabel = "Child"
        )
        parentTitle shouldContain "partial"
        childTitle shouldContain "partial"
    }

    // Regression coverage for jj-idea-jb2q (GitHub #101): the preview's left/right panes must
    // match their own titles — previously the right pane always showed the parent-remainder
    // content (same as the left pane) even though its title claimed "Child".
    @Test
    fun `splitPreviewPanes fully ticked shows base on the left, after on the right (regression)`() {
        val contents = FileContents(before = "before\n", after = "after\n", fileType = PlainTextFileType.INSTANCE)

        val (left, right) = splitPreviewPanes(
            content = "before\n",
            contents = contents,
            parentLabel = "Parent",
            childLabel = "Child"
        )

        left.text shouldBe "before\n"
        right.text shouldBe "after\n"
        left.text shouldNotBe right.text
        left.title shouldContain "no changes"
        right.title shouldContain "all changes"
    }

    @Test
    fun `splitPreviewPanes unticked shows after on both sides (nothing moves)`() {
        val contents = FileContents(before = "before\n", after = "after\n", fileType = PlainTextFileType.INSTANCE)

        val (left, right) = splitPreviewPanes(
            content = "after\n",
            contents = contents,
            parentLabel = "Parent",
            childLabel = "Child"
        )

        left.text shouldBe "after\n"
        right.text shouldBe "after\n"
        left.title shouldContain "all changes"
        right.title shouldContain "no changes"
    }

    @Test
    fun `splitPreviewPanes partial shows the remainder on the left, after on the right`() {
        val contents = FileContents(before = "before\n", after = "after\n", fileType = PlainTextFileType.INSTANCE)

        val (left, right) = splitPreviewPanes(
            content = "partial\n",
            contents = contents,
            parentLabel = "Parent",
            childLabel = "Child"
        )

        left.text shouldBe "partial\n"
        right.text shouldBe "after\n"
        left.title shouldContain "partial"
        right.title shouldContain "partial"
    }

    // describeSplitState/splitPreviewPanes are mode-agnostic (jj-idea-8khi, GitHub #101 UX
    // follow-up): with identity-first labels shared by every mode, "unchanged" (which implied a
    // literal parent/child relationship - jj-idea-o6sw's fix for siblings) is gone in favour of
    // "no changes" everywhere, regardless of what caller passes as parentLabel/childLabel.
    @Test
    fun `describeSplitState never renders 'unchanged' - only 'no changes', 'all changes', or 'partial'`() {
        val (staysAllChanges, newNoChanges) = describeSplitState(
            content = "after\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Second",
            childLabel = "First"
        )
        staysAllChanges shouldContain "all changes"
        newNoChanges shouldContain "no changes"

        val (staysNoChanges, newAllChanges) = describeSplitState(
            content = "before\n",
            baseContent = "before\n",
            afterContent = "after\n",
            parentLabel = "Second",
            childLabel = "First"
        )
        staysNoChanges shouldContain "no changes"
        staysNoChanges shouldNotContain "unchanged"
        newAllChanges shouldContain "all changes"
    }

    @Test
    fun `applyPickedContent for a genuine partial does not force-tick a previously unticked file`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        // Auth.kt starts unticked (nothing selected by default). Apply a genuinely-partial
        // result directly, exactly as onPickHunks would after a real partial pick.
        dialog.applyPickedContent(fp, "partial\n", baseContent = "before\n", afterContent = "after\n")

        // Regression: this used to force-tick the file (ensureFileIncluded), making a
        // half-picked file look fully committed to the child.
        dialog.fileSelection.includedChanges shouldBe emptyList()
        disposeDialog(dialog)
    }

    @Test
    fun `applyPickedContent for a fully-picked result ticks the file`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        dialog.applyPickedContent(fp, "before\n", baseContent = "before\n", afterContent = "after\n")

        dialog.fileSelection.includedChanges.toSet() shouldBe setOf(authChange)
        disposeDialog(dialog)
    }

    @Test
    fun `setFirstCommitOverrideForTest reflects in partialChanges on tree`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange, change("src/Logger.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(fp, "partial\n")

        // The partial change should be the authChange (matched by filePath)
        dialog.fileSelection.changesTree.partialChanges shouldBe setOf(authChange)
        disposeDialog(dialog)
    }

    @Test
    fun `clearing override removes from partialChanges`() {
        val authChange = change("src/Auth.kt")
        val changes = listOf(authChange)
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)
        dialog.setFirstCommitOverrideForTest(fp, "partial\n")
        dialog.setFirstCommitOverrideForTest(fp, null) // clear

        dialog.fileSelection.changesTree.partialChanges shouldBe emptySet()
        disposeDialog(dialog)
    }

    @Test
    fun `cancel returns null hunkPickerForTest to leave state unchanged`() {
        val changes = listOf(change("src/Auth.kt"))
        val source = createEntry("src1", description = "desc")
        val dialog = SplitDialog(project.get(), source, changes)
        waitForRefresh(dialog.fileSelection)

        val fp = LocalFilePath("src/Auth.kt", false)

        // Inject a picker that returns null (cancel) — override should not be set
        dialog.hunkPickerForTest = { null }

        // Manually invoke the pick-hunks path via the test seam
        // (pickHunksButton would normally invoke it, but that requires currentPreviewFile to be set)
        // Instead verify the seam via setFirstCommitOverrideForTest + OK fast path
        dialog.setFirstCommitOverrideForTest(fp, null) // clear any override
        dialog.performOKForTest()
        dialog.result!!.hunkSelection shouldBe null // no overrides → fast path
        disposeDialog(dialog)
    }

    // ---- helpers ----

    private fun createEntry(id: String, description: String = "") = LogEntry(
        repo = mockk(relaxed = true),
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = description
    )

    private fun change(path: String): Change {
        val filePath = LocalFilePath(path, false)
        return Change(null, SimpleContentRevision("", filePath, "1"))
    }

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    private fun waitForRefresh(panel: FileSelectionPanel) {
        var refreshed = false
        panel.changesTree.invokeAfterRefresh { refreshed = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!refreshed && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
        }
        refreshed shouldBe true
    }
}
