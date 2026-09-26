package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import java.util.Objects
import javax.swing.Icon

/**
 * One gutter icon for one jj conflict marker block (jj-idea-82fo, stage 3/4), installed by
 * [JujutsuConflictGutterInstaller] via [com.intellij.diff.util.DiffGutterOperation.Simple] - see
 * that installer's KDoc for why a diff-viewer API is reused for a plain text editor.
 *
 * [getAlignment] is [Alignment.RIGHT], not the default [Alignment.CENTER] - `DiffbaseContentLoader`'s
 * per-line change-status markers already use the gutter's center column (jj-idea-fwea), so this
 * avoids the two colliding.
 *
 * Stage 4 adds [getPopupMenuActions] (Accept side #1 / side #2 / both / base) and a click action;
 * this stage ships the icon and tooltip only.
 *
 * [equals]/[hashCode] compare the block's own offsets/style/labels rather than object identity,
 * per [GutterIconRenderer]'s own doc ("highly advisable ... to avoid icon flickering") - largely
 * inert today since [JujutsuConflictGutterInstaller] disposes and recreates every icon on each
 * debounced rescan (the same pattern [in.kkkev.jjidea.diffedit.HunkArrowDiffExtension] uses for
 * its arrows), but correct now in case a future caller diffs instead of recreating.
 */
class ConflictBlockGutterIconRenderer(private val block: ConflictBlock) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.Vcs.Merge

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun getTooltipText(): String = JujutsuBundle.message(
        "gutter.conflict.tooltip",
        block.side1.label ?: JujutsuBundle.message("merge.column.side1"),
        block.side2.label ?: JujutsuBundle.message("merge.column.side2")
    )

    override fun equals(other: Any?): Boolean = other is ConflictBlockGutterIconRenderer &&
        other.block.startOffset == block.startOffset &&
        other.block.endOffset == block.endOffset &&
        other.block.style == block.style &&
        other.block.side1.label == block.side1.label &&
        other.block.side2.label == block.side2.label

    override fun hashCode(): Int =
        Objects.hash(block.startOffset, block.endOffset, block.style, block.side1.label, block.side2.label)
}
