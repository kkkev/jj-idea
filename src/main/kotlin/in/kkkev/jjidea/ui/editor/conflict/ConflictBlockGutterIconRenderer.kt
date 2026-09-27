package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.conflict.AcceptChoice
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import `in`.kkkev.jjidea.jj.conflict.choicesFor
import java.util.Objects
import javax.swing.Icon

/**
 * One gutter icon for one side of one jj conflict marker block (jj-idea-82fo follow-up:
 * per-side icons, superseding stage 4's single whole-block icon), installed by
 * [JujutsuConflictGutterInstaller] via [com.intellij.diff.util.DiffGutterOperation.Simple] - see
 * that installer's KDoc for why a diff-viewer API is reused for a plain text editor.
 *
 * One instance exists per [choicesFor] entry [block] currently offers, each anchored on that
 * side's own first content line (`ConflictSide.contentStartOffset` - see
 * [JujutsuConflictGutterInstaller]'s installer for how the offset is picked) rather than a
 * single icon on the block's opening `<<<<<<<` line - directly answering "which of these icons
 * is for which side" by *placement*, not by reading labels first. [primaryChoice] is *this*
 * icon's own side.
 *
 * - [getClickAction] (left-click) shows a one-item confirmation popup for [primaryChoice] via
 *   [ConflictAcceptConfirmAction] rather than applying it immediately - see that class's KDoc.
 * - [getPopupMenuActions] (right-click) still offers the *full* [choicesFor] menu from every
 *   icon, so "Accept Both" and the base option stay reachable from any of them - no separate
 *   whole-block icon is needed for those.
 *
 * Hover feedback (a genuine first for this codebase - no `EditorMouseMotionListener` anywhere
 * in it yet) is deferred to jj-idea-sr42, a separate follow-up.
 *
 * [getAlignment] is [Alignment.RIGHT], not the default [Alignment.CENTER] - `DiffbaseContentLoader`'s
 * per-line change-status markers already use the gutter's center column (jj-idea-fwea), so this
 * avoids the two colliding.
 *
 * [equals]/[hashCode] compare the block's own offsets/style/labels plus [primaryChoice] rather
 * than object identity, per [GutterIconRenderer]'s own doc ("highly advisable ... to avoid icon
 * flickering") - largely inert today since [JujutsuConflictGutterInstaller] disposes and
 * recreates every icon on each debounced rescan (the same pattern
 * [in.kkkev.jjidea.diffedit.HunkArrowDiffExtension] uses for its arrows), but correct now in
 * case a future caller diffs instead of recreating. [primaryChoice] must be included -
 * otherwise two icons for the same block's different sides would wrongly compare equal.
 */
class ConflictBlockGutterIconRenderer(
    private val project: Project,
    private val document: Document,
    private val block: ConflictBlock,
    private val primaryChoice: AcceptChoice
) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.Vcs.Merge

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun getClickAction(): AnAction =
        ConflictAcceptConfirmAction(project, document, block, primaryChoice, labelFor(primaryChoice))

    override fun getTooltipText(): String =
        JujutsuBundle.message("gutter.conflict.tooltip.side", labelFor(primaryChoice))

    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
        choicesFor(block).map { choice ->
            acceptAction(choice)
        }
    )

    private fun acceptAction(choice: AcceptChoice) =
        AcceptConflictBlockAction(project, document, block, choice, labelFor(choice))

    private fun labelFor(choice: AcceptChoice): String = when (choice) {
        AcceptChoice.SIDE1 -> JujutsuBundle.message(
            "notification.conflict.accept",
            block.side1.label ?: JujutsuBundle.message("merge.column.side1")
        )

        AcceptChoice.SIDE2 -> JujutsuBundle.message(
            "notification.conflict.accept",
            block.side2.label ?: JujutsuBundle.message("merge.column.side2")
        )

        AcceptChoice.BOTH -> JujutsuBundle.message("gutter.conflict.accept.both")

        AcceptChoice.BASE -> JujutsuBundle.message(
            "notification.conflict.accept",
            block.base?.label ?: JujutsuBundle.message("merge.column.base")
        )
    }

    override fun equals(other: Any?): Boolean = other is ConflictBlockGutterIconRenderer &&
        other.primaryChoice == primaryChoice &&
        other.block.startOffset == block.startOffset &&
        other.block.endOffset == block.endOffset &&
        other.block.style == block.style &&
        other.block.side1.label == block.side1.label &&
        other.block.side2.label == block.side2.label

    override fun hashCode(): Int =
        Objects.hash(
            primaryChoice,
            block.startOffset,
            block.endOffset,
            block.style,
            block.side1.label,
            block.side2.label
        )
}
