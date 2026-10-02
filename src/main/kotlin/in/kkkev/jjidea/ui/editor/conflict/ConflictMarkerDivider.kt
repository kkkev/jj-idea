package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.conflict.AcceptChoice
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D

/** The text-attributes key whose background tints [choice]'s side - shared by tint, hover and divider. */
internal fun conflictSideKey(choice: AcceptChoice): TextAttributesKey = when (choice) {
    AcceptChoice.SIDE1 -> DiffColors.DIFF_DELETED
    AcceptChoice.SIDE2 -> DiffColors.DIFF_INSERTED
    AcceptChoice.BASE -> DiffColors.DIFF_MODIFIED
    AcceptChoice.BOTH -> error("BOTH has no side tint")
}

/** How far toward the scheme foreground a divider rule's colour moves from the side's tint, so it reads as a line, not a faint wash. */
private const val RULE_MIX = 0.35

/**
 * Full-width horizontal rule standing in for one run of conflict marker lines (jj-idea-6ja9),
 * coloured from the side whose content follows ([key], null for the block's closing run) and
 * labelled with that side's own label. Created per [in.kkkev.jjidea.jj.conflict.MarkerRun]; a
 * [CustomFoldRegion] can't be expanded, so [ConflictGutterController] drops these while the caret
 * sits in the block.
 */
internal class ConflictMarkerDivider(
    private val editor: Editor,
    private val key: TextAttributesKey?,
    private val label: String?
) : CustomFoldRegionRenderer {
    override fun calcWidthInPixels(region: CustomFoldRegion): Int =
        maxOf(editor.scrollingModel.visibleArea.width, JBUI.scale(100))

    override fun calcHeightInPixels(region: CustomFoldRegion): Int = editor.lineHeight

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes
    ) {
        val scheme = editor.colorsScheme
        val base = key?.let { scheme.getAttributes(it)?.backgroundColor } ?: UIUtil.getInactiveTextColor()
        val color = ColorUtil.mix(base, scheme.defaultForeground, RULE_MIX)
        val oldHint = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        g.color = color
        val midY = targetRegion.y + targetRegion.height / 2
        val thickness = JBUI.scale(2).toDouble()
        val ruleStart = targetRegion.x + JBUI.scale(4)
        var textEnd = ruleStart
        if (!label.isNullOrBlank()) {
            g.font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                .deriveFont(Font.PLAIN, editor.colorsScheme.editorFontSize2D * 0.85f)
            g.color = UIUtil.getInactiveTextColor()
            val metrics = g.fontMetrics
            val x = ruleStart + JBUI.scale(6)
            g.drawString(label, x.toFloat(), (midY + (metrics.ascent - metrics.descent) / 2.0).toFloat())
            textEnd = x + metrics.stringWidth(label) + JBUI.scale(6)
            g.color = color
        }
        g.fill(Rectangle2D.Double(textEnd, midY - thickness / 2, targetRegion.maxX - textEnd, thickness))
        if (textEnd > ruleStart) {
            g.fill(Rectangle2D.Double(ruleStart, midY - thickness / 2, JBUI.scale(4).toDouble(), thickness))
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldHint)
    }
}
