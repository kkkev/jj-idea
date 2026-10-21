package `in`.kkkev.jjidea.ui.components

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EmptyEditorHighlighter
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.components.JBLabel
import com.intellij.util.textCompletion.TextFieldWithCompletion
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.RevsetAlias
import `in`.kkkev.jjidea.jj.cli.listRevsetAliases
import `in`.kkkev.jjidea.jj.revset.RevsetLexer
import `in`.kkkev.jjidea.jj.revset.RevsetSyntaxHighlighter
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/** A titled group of history entries in the drop-down; [title] null means no header. */
data class HistorySection(val title: String?, val items: List<String>)

/** Largest history the platform's `SearchTextField` keeps; this field reads and writes the same format. */
internal const val REVSET_HISTORY_SIZE = 5

/** Most-recent-first history stored under [key], newline-joined as `SearchTextField` does, so existing history carries over. */
internal fun readRevsetHistory(key: String): List<String> =
    PropertiesComponent.getInstance().getValue(key).orEmpty().split('\n').filter { it.isNotEmpty() }

internal fun addToRevsetHistory(key: String, text: String) {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) {
        val updated = (listOf(trimmed) + readRevsetHistory(key).filter { it != trimmed }).take(REVSET_HISTORY_SIZE)
        PropertiesComponent.getInstance().setValue(key, updated.joinToString("\n"))
    }
}

/**
 * The shared revset input (jj-idea-ehwm.7): an editor-based text field with lexer highlighting,
 * completion of functions / bookmarks / tags / aliases, a squiggle at the column jj reports an error,
 * and a history drop-down. Highlighting and completion are a lexer plus cached data; jj stays the only
 * validator. Aliases are fetched with one `jj config list` per repo each time the field gains focus.
 */
class RevsetTextField(
    private val project: Project,
    private val repos: () -> List<JujutsuRepository>,
    private val historyKey: String? = null,
    multiline: Boolean = false,
    placeholder: String? = null,
    initialText: String = "",
    private val popupAnchor: () -> Component? = { null }
) : JPanel(BorderLayout()) {
    /** Called on Enter when no completion popup is showing. Not used by a multiline field. */
    var onEnter: (() -> Unit)? = null

    /** Called on Escape when no completion popup is showing; the editor otherwise swallows the key. */
    var onEscape: (() -> Unit)? = null

    /** The history sections shown by the drop-down; callers may add sections (e.g. saved aliases). */
    var historySections: () -> List<HistorySection> = {
        listOfNotNull(historyKey?.let { HistorySection(null, readRevsetHistory(it)) })
    }

    @Volatile
    private var aliases: List<RevsetAlias> = emptyList()

    /** Toggles highlighting and completion together; off renders plain text. */
    var highlightingEnabled = true
        set(value) {
            field = value
            applyHighlighter()
        }

    private val changeListeners = mutableListOf<() -> Unit>()
    private var errorHighlighter: RangeHighlighter? = null
    private var editorEx: EditorEx? = null

    private val completionProvider = RevsetCompletionProvider(project, repos, { aliases }, { highlightingEnabled })

    val editorField: TextFieldWithCompletion = object :
        TextFieldWithCompletion(project, completionProvider, initialText, !multiline, true, false) {
        // The platform only gives a one-line editor the border that paints the error outline; a multiline
        // field would be left with a plain border that never shows [setError]'s outline.
        override fun updateBorder(editor: EditorEx) = setupBorder(editor)
    }.apply {
        placeholder?.let { setPlaceholder(it) }
        setFontInheritedFromLAF(true)
        addSettingsProvider { editor ->
            editorEx = editor
            applyHighlighter()
            editor.settings.isShowIntentionBulb = false
            installKeys(editor, multiline)
        }
        addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    clearSquiggle()
                    updateClearButton()
                    changeListeners.toList().forEach { it() }
                }
            }
        )
        addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = refreshAliases()

                // A completion list must not outlive the field, e.g. when focus moves to a Test button.
                override fun focusLost(e: FocusEvent) = LookupManager.hideActiveLookup(project)
            }
        )
    }

    private val historyButton = JBLabel(AllIcons.Actions.SearchWithHistory).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        border = JBUI.Borders.empty(0, 4)
        toolTipText = null
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = showHistory()
            }
        )
    }

    internal val clearButton = JBLabel(AllIcons.Actions.Close).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(0, 4)
        isVisible = false
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    icon = AllIcons.Actions.CloseHovered
                }

                override fun mouseExited(e: MouseEvent) {
                    icon = AllIcons.Actions.Close
                }

                override fun mousePressed(e: MouseEvent) {
                    this@RevsetTextField.text = ""
                    editorField.requestFocusInWindow()
                }
            }
        )
    }

    init {
        if (historyKey != null) add(historyButton, BorderLayout.WEST)
        add(editorField, BorderLayout.CENTER)
        add(clearButton, BorderLayout.EAST)
        updateClearButton()
        sizeToColumns(DEFAULT_COLUMNS, if (multiline) DEFAULT_ROWS else 1)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        editorField.isEnabled = enabled
    }

    var text: String
        get() = editorField.text
        set(value) {
            editorField.text = value
        }

    /** Sizes the field to [columns] characters wide and, if [rows] > 1, that many text rows tall. */
    fun sizeToColumns(columns: Int, rows: Int = 1) {
        val metrics = editorField.getFontMetrics(editorField.font)
        val width = columns * metrics.charWidth('m')
        // An explicit height: before its editor exists the field would otherwise be sized to almost nothing.
        editorField.preferredSize = Dimension(width, rows * metrics.height + JBUI.scale(12))
    }

    fun setPlaceholder(text: String) {
        editorField.setPlaceholder(text)
    }

    fun onTextChanged(listener: () -> Unit) {
        changeListeners += listener
    }

    /** The component that takes keyboard focus. */
    val focusTarget: JComponent get() = editorField.focusTarget

    fun addCurrentTextToHistory() {
        historyKey?.let { addToRevsetHistory(it, text) }
    }

    /**
     * Shows [message] as a tooltip, and as a red outline when [showOutline] (null clears both). With a
     * jj-reported 1-based [position] in this field's own text, also underlines the token there. The
     * outline goes on the editor field, whose border paints it; callers that draw their own error
     * decoration (a wrapper border, a validation balloon) pass `showOutline = false`.
     */
    fun setError(message: String?, position: Pair<Int, Int>? = null, showOutline: Boolean = true) {
        clearSquiggle()
        editorField.putClientProperty("JComponent.outline", if (message != null && showOutline) "error" else null)
        toolTipText = message
        if (message != null && position != null) addSquiggle(position.first, position.second)
        editorField.repaint()
    }

    /** The number of error squiggles currently shown (for tests). */
    internal val squiggleCount get() = if (errorHighlighter?.isValid == true) 1 else 0

    private fun addSquiggle(line: Int, column: Int) {
        val editor = editorEx
        if (editor != null) {
            val document = editor.document
            val lineIndex = (line - 1).coerceIn(0, maxOf(document.lineCount - 1, 0))
            val start = (document.getLineStartOffset(lineIndex) + column - 1).coerceIn(0, document.textLength)
            val end = tokenEnd(document.charsSequence, start).coerceAtLeast(minOf(start + 1, document.textLength))
            if (end > start) {
                errorHighlighter = editor.markupModel.addRangeHighlighter(
                    CodeInsightColors.ERRORS_ATTRIBUTES,
                    start,
                    end,
                    HighlighterLayer.ERROR,
                    HighlighterTargetArea.EXACT_RANGE
                )
            }
        }
    }

    private fun tokenEnd(text: CharSequence, from: Int): Int {
        val lexer = RevsetLexer()
        lexer.start(text, from, text.length, 0)
        return lexer.tokenEnd
    }

    private fun clearSquiggle() {
        errorHighlighter?.let { editorEx?.markupModel?.removeHighlighter(it) }
        errorHighlighter = null
    }

    private fun updateClearButton() {
        clearButton.isVisible = text.isNotEmpty()
    }

    private fun applyHighlighter() {
        editorEx?.let {
            val scheme = EditorColorsManager.getInstance().globalScheme
            it.highlighter = if (highlightingEnabled) {
                LexerEditorHighlighter(RevsetSyntaxHighlighter(), scheme)
            } else {
                EmptyEditorHighlighter(scheme.getAttributes(HighlighterColors.TEXT))
            }
        }
    }

    private fun installKeys(editor: EditorEx, multiline: Boolean) {
        // Enter and Escape must still act on a showing completion popup, so they only fire without one.
        fun register(shortcuts: ShortcutSet, handler: () -> (() -> Unit)?) {
            object : DumbAwareAction() {
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = handler() != null && LookupManager.getActiveLookup(editor) == null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    handler()?.invoke()
                }
            }.registerCustomShortcutSet(shortcuts, editor.contentComponent)
        }
        if (!multiline) register(CommonShortcuts.ENTER) { onEnter }
        register(CommonShortcuts.ESCAPE) { onEscape }
    }

    /** One `jj config list` per repo per focus gain, off the EDT; never per keystroke. */
    private fun refreshAliases() {
        val snapshot = repos()
        runInBackground {
            val loaded = snapshot.flatMap { listRevsetAliases(it.commandExecutor) }.distinct()
            runLater { aliases = loaded }
        }
    }

    private fun showHistory() {
        val sections = historySections().filter { it.items.isNotEmpty() }
        if (sections.isNotEmpty()) {
            val entries = sections.flatMap { s -> s.items.mapIndexed { i, item -> Triple(item, s.title, i == 0) } }
            val step = object : BaseListPopupStep<Triple<String, String?, Boolean>>(null, entries) {
                override fun getTextFor(value: Triple<String, String?, Boolean>) = value.first.lineSequence().first()

                override fun getSeparatorAbove(value: Triple<String, String?, Boolean>) =
                    if (value.third && value.second != null) ListSeparator(value.second!!) else null

                override fun onChosen(
                    selectedValue: Triple<String, String?, Boolean>,
                    finalChoice: Boolean
                ): PopupStep<*>? {
                    text = selectedValue.first
                    editorField.requestFocusInWindow()
                    return FINAL_CHOICE
                }
            }
            JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(popupAnchor() ?: this)
        }
    }
}

private const val DEFAULT_COLUMNS = 50
private const val DEFAULT_ROWS = 3
