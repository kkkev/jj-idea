package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.CopyProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.TransferHandler
import javax.swing.text.JTextComponent
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

/**
 * Copies an [IconAwareHtmlPane] selection as plain text with each atomic unit's real text in it
 * (jj-idea-5zio). A bookmark/tag chip, an author `name <email>` or a date is one `<img>` element whose
 * text lives only in its encoded `src` ([UnbreakableContent]), so the document's own `getText` - what
 * the default text transfer handler copies - sees a single placeholder character for it instead.
 *
 * Copy-only: the pane is never editable, and it's never a Swing drag source (`dragEnabled` stays false
 * - see [installRefDragSource]'s doc), so nothing else goes through this handler.
 */
internal object CopyableTextTransferHandler : TransferHandler() {
    override fun getSourceActions(c: JComponent) = COPY

    override fun createTransferable(c: JComponent): Transferable? {
        val pane = c as? JTextComponent ?: return null
        val doc = pane.document as? HTMLDocument ?: return null
        val start = pane.selectionStart
        val end = pane.selectionEnd
        if (start == end) return null
        return StringSelection(doc.copyableText(start, end))
    }
}

/**
 * The IDE's Copy (Cmd/Ctrl+C) never reaches [CopyableTextTransferHandler]: the keymap binds the shortcut
 * straight to `EditorCopy`, which wraps the pane as a text-component editor and reads the selection through
 * the IDE's own document view - where each chip is the same placeholder character again. [IconAwareHtmlPane]
 * therefore supplies this as its `COPY_PROVIDER` *and* registers the platform's Copy action on itself (a
 * component-registered action is tried before the keymap's), so the shortcut ends up here (jj-idea-5zio).
 */
internal class CopyableTextCopyProvider(private val pane: JTextComponent) : CopyProvider {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isCopyEnabled(dataContext: DataContext) = pane.selectionStart != pane.selectionEnd

    override fun isCopyVisible(dataContext: DataContext) = true

    override fun performCopy(dataContext: DataContext) {
        val doc = pane.document as? HTMLDocument ?: return
        if (pane.selectionStart == pane.selectionEnd) return
        val text = doc.copyableText(pane.selectionStart, pane.selectionEnd)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}

/**
 * The text of [start]..[end] as a user would expect to copy it: ordinary text as-is, each atomic unit
 * replaced by its [ChipContent.plainText], and a standalone icon dropped (it has no text of its own).
 *
 * One `getCharacterElement` lookup per leaf element in the range - O(elements selected), bounded by
 * the pane's own content (a single commit's metadata or a tooltip), not by repo size.
 */
internal fun HTMLDocument.copyableText(start: Int, end: Int): String = buildString {
    var pos = start
    while (pos < end) {
        val elem = getCharacterElement(pos)
        val segmentEnd = minOf(end, maxOf(elem.endOffset, pos + 1))
        val src = if (elem.name == "img") elem.attributes.getAttribute(HTML.Attribute.SRC) as? String else null
        when {
            src == null -> append(getText(pos, segmentEnd - pos))
            src.startsWith(UNBREAKABLE_PREFIX) ->
                append(UnbreakableContent.decode(src.removePrefix(UNBREAKABLE_PREFIX)).plainText)
            else -> Unit
        }
        pos = segmentEnd
    }
}
