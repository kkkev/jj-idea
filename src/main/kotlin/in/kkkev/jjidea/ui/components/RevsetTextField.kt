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
import `in`.kkkev.jjidea.JujutsuBundle
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

/**
 * One drop-down row: choosing it puts [text] in the field; [label] is what the row shows (first line only).
 * [modes] are the owner's switches (e.g. regex / revset) the entry was run with, or null if not recorded.
 */
data class HistoryItem(val text: String, val label: String = text, val modes: String? = null)

/** A titled group of history entries in the drop-down; [title] null means no header. */
data class HistorySection(val title: String?, val items: List<HistoryItem>)

/** A [HistorySection] row flattened for the popup list; [first] marks the row that carries the section header. */
internal data class HistoryRow(val item: HistoryItem, val sectionTitle: String?, val first: Boolean)

/** Largest history the platform's `SearchTextField` keeps; this field reads and writes the same format. */
internal const val REVSET_HISTORY_SIZE = 5

/** Separates an entry's text from its [HistoryEntry.modes] in storage; entries from before modes have no separator. */
private const val MODES_SEPARATOR = '\u001f'

/** A stored history entry: the [text] and the owner's switches it was run with (null: not recorded). */
data class HistoryEntry(val text: String, val modes: String? = null)

private fun decodeEntry(line: String): HistoryEntry {
    val at = line.lastIndexOf(MODES_SEPARATOR)
    return if (at < 0) HistoryEntry(line) else HistoryEntry(line.substring(0, at), line.substring(at + 1))
}

private fun HistoryEntry.encode() = if (modes == null) text else "$text$MODES_SEPARATOR$modes"

/** Most-recent-first entries stored under [key], newline-joined as `SearchTextField` does, so existing history carries over. */
internal fun readRevsetHistoryEntries(key: String): List<HistoryEntry> =
    PropertiesComponent.getInstance().getValue(key).orEmpty().split('\n').filter { it.isNotEmpty() }.map(::decodeEntry)

internal fun readRevsetHistory(key: String): List<String> = readRevsetHistoryEntries(key).map { it.text }

/** Adds [text] run with [modes] at the front; the same text with different modes is a separate entry. */
internal fun addToRevsetHistory(key: String, text: String, modes: String? = null) {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) {
        val entry = HistoryEntry(trimmed, modes)
        val updated = (listOf(entry) + readRevsetHistoryEntries(key).filter { it != entry }).take(REVSET_HISTORY_SIZE)
        PropertiesComponent.getInstance().setValue(key, updated.joinToString("\n") { it.encode() })
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

    /** The owner's current switches, stored with each history entry (null: this field has none). */
    var historyModes: () -> String? = { null }

    /** Called with an entry's stored switches before its text is put in the field, so the owner can restore them. */
    var onHistoryModes: ((String) -> Unit)? = null

    /** Describes stored switches for a row label (empty: none worth showing). */
    var describeModes: (String) -> String = { "" }

    /** The history sections shown by the drop-down: recent revsets, then saved aliases (jj-idea-ehwm.8/.9). */
    var historySections: () -> List<HistorySection> = {
        listOfNotNull(
            historyKey?.let { key -> HistorySection(null, readRevsetHistoryEntries(key).map(::historyItem)) },
            aliasSection()
        )
    }

    @Volatile
    private var aliases: List<RevsetAlias> = emptyList()

    @Volatile
    private var aliasesRequested = false

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

    private fun historyItem(entry: HistoryEntry): HistoryItem {
        val described = entry.modes?.let(describeModes).orEmpty()
        val label = if (described.isEmpty()) entry.text else "${entry.text}   [$described]"
        return HistoryItem(entry.text, label, entry.modes)
    }

    fun addCurrentTextToHistory() {
        historyKey?.let { addToRevsetHistory(it, text, historyModes()) }
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
        aliasesRequested = true
        val snapshot = repos()
        runInBackground {
            val loaded = snapshot.flatMap { listRevsetAliases(it.commandExecutor) }.distinct()
            runLater { aliases = loaded }
        }
    }

    /** Symbol aliases by name, repo-defined ones marked; function aliases need arguments so are left out. */
    private fun aliasSection(): HistorySection? {
        val items = aliases.filter { !it.isFunction }
            .groupBy { it.name }
            .map { (name, defs) ->
                val repoOnly = defs.all { it.isRepoLevel }
                HistoryItem(name, if (repoOnly) JujutsuBundle.message("revset.history.alias.repo", name) else name)
            }
            .sortedBy { it.text }
        return items.takeIf { it.isNotEmpty() }?.let {
            HistorySection(JujutsuBundle.message("revset.history.aliases"), it)
        }
    }

    internal fun historyRows(): List<HistoryRow> = historySections().filter { it.items.isNotEmpty() }
        .flatMap { s -> s.items.mapIndexed { i, item -> HistoryRow(item, s.title, i == 0) } }

    private fun showHistory() {
        // Aliases load on focus; if this is opened before any focus, start the (single) load for next time.
        if (!aliasesRequested) refreshAliases()
        val rows = historyRows()
        if (rows.isNotEmpty()) {
            val step = object : BaseListPopupStep<HistoryRow>(null, rows) {
                override fun getTextFor(value: HistoryRow) = value.item.label.lineSequence().first()

                override fun getSeparatorAbove(value: HistoryRow) =
                    if (value.first && value.sectionTitle != null) ListSeparator(value.sectionTitle) else null

                override fun onChosen(selectedValue: HistoryRow, finalChoice: Boolean): PopupStep<*>? {
                    selectedValue.item.modes?.let { onHistoryModes?.invoke(it) }
                    text = selectedValue.item.text
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
