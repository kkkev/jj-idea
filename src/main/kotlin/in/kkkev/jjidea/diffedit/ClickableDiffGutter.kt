package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.util.DiffGutterOperation
import com.intellij.diff.util.DiffGutterRenderer
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.editor.ex.EditorEx
import javax.swing.Icon

/**
 * A clickable gutter icon for a diff editor: a public [DiffGutterRenderer] with a click callback.
 * Shared by [HunkArrowDiffExtension] (arrows) and [HunkCheckboxDiffExtension] (checkboxes).
 */
class ClickableDiffGutterRenderer(
    icon: Icon,
    tooltip: String,
    private val onClicked: () -> Unit
) : DiffGutterRenderer(icon, tooltip) {
    override fun handleMouseClick() = onClicked()
}

/**
 * The currently-installed gutter operations of one viewer. [replaceWith] disposes the previous set
 * first, so callers can simply rebuild everything on each `onAfterRediff` / state change.
 */
class DiffGutterOperationSet {
    private var installed: List<DiffGutterOperation> = emptyList()

    val size: Int get() = installed.size

    fun replaceWith(operations: List<DiffGutterOperation>) {
        installed.forEach { it.dispose() }
        installed = operations
    }

    fun clear() = replaceWith(emptyList())

    /** Re-run every operation's renderer builder on its existing highlighter (no dispose/recreate). */
    fun updateAll() = installed.forEach { it.update(true) }
}

/** One [DiffGutterOperation] with a clickable icon (re-read on each update) on [line] of [editor], clamped. */
fun clickableGutterOperation(
    editor: EditorEx,
    line: Int,
    icon: () -> Icon,
    tooltip: String,
    onClicked: () -> Unit
): DiffGutterOperation {
    val safeLine = line.coerceIn(0, DiffUtil.getLineCount(editor.document))
    val offset = DiffGutterOperation.lineToOffset(editor, safeLine)
    return DiffGutterOperation.Simple(
        editor,
        offset,
        DiffGutterOperation.RendererBuilder { ClickableDiffGutterRenderer(icon(), tooltip, onClicked) }
    )
}
