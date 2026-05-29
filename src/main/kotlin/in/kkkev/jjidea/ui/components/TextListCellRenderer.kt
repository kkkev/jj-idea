package `in`.kkkev.jjidea.ui.components

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JList
import javax.swing.ListCellRenderer

abstract class TextListCellRenderer<T>() : ListCellRenderer<T> {
    override fun getListCellRendererComponent(
        list: JList<out T?>,
        value: T?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ) = TextCanvasPanel().apply {
        font = list.font
        background = if (isSelected) list.selectionBackground else null

        // TODO Look at ColoredListCellRenderer.setPaintFocusBorder if has focus

        val fg = when {
            isSelected -> list.selectionForeground
            isEnabled -> list.foreground
            else -> UIUtil.getLabelDisabledForeground()
        }

        val canvas = FragmentRecordingCanvas()
        value?.let { canvas.foreground(fg) { render(this, it) } }

        // Subtract list insets and a per-SCC ipad allowance (SimpleColoredComponent adds
        // JBInsets.create(1,2) = 2+2 px per SCC; assume at most 2 SCCs per row).
        val insets = list.insets
        val w = list.width - insets.left - insets.right - JBUI.scale(8)
        val fragments = if (w > 0) {
            val frc = list.getFontMetrics(list.font).fontRenderContext
            FragmentLayout.truncateToFit(canvas.fragments, canvas.truncateRange, w.toDouble(), list.font, frc)
        } else {
            canvas.fragments
        }
        renderFrom(FragmentRecordingCanvas(fragments))
    }

    abstract fun render(canvas: TextCanvas, value: T)
}
