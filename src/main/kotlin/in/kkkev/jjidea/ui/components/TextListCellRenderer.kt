package `in`.kkkev.jjidea.ui.components

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

        value?.let { canvas.colored(fg) { render(this, it) } }
        renderFrom(canvas)
    }

    abstract fun render(canvas: TextCanvas, value: T)
}
