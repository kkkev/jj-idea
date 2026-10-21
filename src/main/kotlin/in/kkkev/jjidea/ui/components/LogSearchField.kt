package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.SearchFieldWithExtension
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.logSearchRevset
import `in`.kkkev.jjidea.ui.log.LogFilterMatcher
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import kotlin.reflect.KMutableProperty1

/**
 * A [SearchTextField] with regex / match-case / whole-words toggles (jj-idea-lpbv), backed by
 * [LogFilterMatcher] for client-side filtering of already-loaded entries and [logSearchRevset]
 * for a whole-repo `jj log -r` fallback on Enter.
 *
 * Extracted from [in.kkkev.jjidea.ui.common.CommitTablePanel] (the log tool window's original
 * home for this search) so the commit-picker dialogs (jj-idea-tq4b) can reuse the exact same
 * search behavior instead of each hand-rolling a weaker always-case-insensitive filter.
 *
 * With [collapsible], when the field is squeezed so far that the toggles would leave almost no room to
 * type, it collapses to a compact chip that pops the full field out (as the log's Scope chip does).
 *
 * [onFilterChanged] fires on every keystroke and toggle change. [onSubmitted] fires on Enter with
 * the trimmed, non-blank text — callers that want jj-idea-lpbv's whole-repo search wire it there;
 * callers that don't (e.g. a dialog willing to search only the already-loaded set) simply omit it.
 */
class LogSearchField(
    private val placeholder: String,
    tooltip: String? = null,
    historyKey: String? = null,
    private val revsetModeAvailable: Boolean = false,
    private val collapsible: Boolean = false,
    private val onFilterChanged: () -> Unit = {},
    private val onSubmitted: (String) -> Unit = {},
    private val onSaveRevsetAlias: ((String) -> Unit)? = null,
    project: Project? = null,
    repos: () -> List<JujutsuRepository> = { emptyList() }
) : JPanel(BorderLayout()) {
    // With revset mode on offer the box is an editor-based RevsetTextField (highlighting and completion while
    // in revset mode, plain text otherwise); the pickers keep the plain SearchTextField.
    private val input: SearchInput = if (revsetModeAvailable) {
        RevsetInput(
            requireNotNull(project) { "A revset-capable LogSearchField needs a project" },
            repos,
            historyKey,
            { popupContent }
        )
    } else {
        PlainInput(historyKey) { popupContent }
    }.apply {
        setPlaceholder(placeholder)
        tooltip?.let { setTooltip(it) }
        onTextChanged { onFilterChanged() }
        onSubmit { submit() }
    }

    private fun submit() {
        val trimmed = input.text.trim()
        if (trimmed.isNotEmpty()) {
            input.addCurrentTextToHistory()
            onSubmitted(trimmed)
            popup?.cancel()
        }
    }

    var useRegex = false
        set(value) {
            field = value
            onFilterChanged()
        }
    var matchCase = false
        set(value) {
            field = value
            onFilterChanged()
        }
    var matchWholeWords = false
        set(value) {
            field = value
            onFilterChanged()
        }

    /**
     * When true (only offered if [revsetModeAvailable], jj-idea-ehwm.5) the text is a jj revset rather
     * than text to match, so the regex / match-case / whole-words toggles don't apply.
     */
    var revsetMode = false
        set(value) {
            field = value
            input.setPlaceholder(placeholderText())
            input.highlighting = value
            updateChip()
            onFilterChanged()
        }

    /**
     * Shows [message] (a revset the search can't use) as a red outline plus tooltip; null clears it. With a
     * jj-reported 1-based [position] in the box's own text, also underlines the token there.
     */
    fun setError(message: String?, position: Pair<Int, Int>? = null) {
        error = message
        input.setError(message, position)
        field.repaint()
        updateChip()
    }

    private var error: String? = null

    /** Current search text. Setting it fires [onFilterChanged] the same as a keystroke would. */
    var text: String
        get() = input.text
        set(value) {
            input.text = value
        }

    /**
     * For focus requests and key listeners (e.g. up/down navigation in a list-based picker). Only the
     * plain (non revset-capable) field has a [JTextField]; the log's revset-capable box does not.
     */
    val textEditor: JTextField
        get() = checkNotNull((input as? PlainInput)?.searchTextField?.textEditor) {
            "A revset-capable LogSearchField has no JTextField"
        }

    private val toolbar = ActionManager.getInstance().createActionToolbar(
        "JujutsuLogSearchFilter",
        BackgroundActionGroup(
            *listOfNotNull(
                FilterToggleAction("regex", AllIcons.Actions.RegexHovered, LogSearchField::useRegex),
                FilterToggleAction("matchcase", AllIcons.Actions.MatchCase, LogSearchField::matchCase),
                FilterToggleAction("words", AllIcons.Actions.Words, LogSearchField::matchWholeWords),
                if (revsetModeAvailable) {
                    FilterToggleAction("revset.mode", AllIcons.Ide.LocalScope, LogSearchField::revsetMode, true)
                } else {
                    null
                },
                if (revsetModeAvailable && onSaveRevsetAlias != null) SaveRevsetAliasAction() else null
            ).toTypedArray()
        ),
        true
    ).apply { targetComponent = input.focusComponent }

    /** The text field with its toggles; lives in [cards] unless the popup is showing it. */
    private val field = input.wrap(toolbar.component).apply {
        // Allow the search field to shrink under width pressure; below [minTextWidth] of typing room
        // the whole thing collapses to [chip] (when [collapsible]) rather than squeezing further.
        minimumSize = Dimension(JBUI.scale(150), preferredSize.height)
    }

    // A SimpleColoredComponent rather than a JLabel: it ellipsizes, never centres, and takes its colours from
    // SimpleTextAttributes, so the typed text is drawn exactly as typed.
    private val chip = SimpleColoredComponent().apply {
        icon = AllIcons.Actions.Find
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(2, 6)
        )
        // Lets the layout squeeze the chip to little more than its icon; the text then ellipsizes.
        minimumSize = Dimension(JBUI.scale(80), preferredSize.height)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = showPopup()
            }
        )
    }

    private val cards = JPanel(CardLayout())
    private var popup: JBPopup? = null
    private var popupContent: JPanel? = null
    private var collapsed = false

    // Esc (or a click away) closes the popup and focus then lands back on the box; without this it would pop out again.
    private var popOutSuppressedUntil = 0L

    /** True while the field is showing as the compact chip (see [collapsible]). */
    internal val isCollapsed get() = collapsed

    init {
        cards.add(field, INLINE)
        cards.add(chip, CHIP)
        add(cards, BorderLayout.CENTER)
        if (collapsible) {
            input.onTextChanged { updateChip() }
            addComponentListener(
                object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) = updateCollapsed()
                }
            )
            updateChip()
            // The search box pops out into the wide popup the chip uses, so what's typed has room to be read.
            if (revsetModeAvailable) {
                input.onActivated {
                    if (shouldPopOut(
                            collapsed,
                            popup != null,
                            isShowing,
                            System.nanoTime(),
                            popOutSuppressedUntil
                        )
                    ) {
                        popOut()
                    }
                }
            }
        }
    }

    /**
     * Shows the chip in the box's place and pops the full field out under it (jj-idea-ehwm.7), as happens when the
     * box is squeezed; the field returns when the popup closes.
     */
    private fun popOut() {
        updateChip()
        (cards.layout as CardLayout).show(cards, CHIP)
        cards.validate()
        showPopup()
    }

    // Always report the full field's size, wherever it currently is, so the parent layout offers the
    // width needed to expand again; only the minimum is the chip's.
    override fun getPreferredSize(): Dimension =
        Dimension(field.preferredSize.width, maxOf(field.preferredSize.height, chip.preferredSize.height))

    override fun getMinimumSize(): Dimension =
        if (collapsible) Dimension(chip.minimumSize.width, preferredSize.height) else field.minimumSize

    /** Width below which the field leaves too little room to type once the toggles are laid out. */
    private fun collapseBelowWidth() = toolbar.component.preferredSize.width + JBUI.scale(MIN_TEXT_WIDTH + 40)

    private fun updateCollapsed() {
        if (width <= 0) return
        val shouldCollapse = width < collapseBelowWidth()
        if (shouldCollapse == collapsed) return
        collapsed = shouldCollapse
        if (!collapsed) popup?.cancel()
        (cards.layout as CardLayout).show(cards, if (collapsed) CHIP else INLINE)
    }

    private fun updateChip() {
        if (!collapsible) return
        val typed = text.trim()
        chip.clear()
        chip.append(
            typed.ifEmpty { placeholderText() },
            when {
                error != null -> SimpleTextAttributes.ERROR_ATTRIBUTES
                typed.isEmpty() -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
        )
        chip.toolTipText = error ?: typed.ifEmpty { null }
    }

    private fun placeholderText() =
        if (revsetMode) JujutsuBundle.message("log.filter.text.placeholder.revset") else placeholder

    /** Pops the full field (text and toggles) out of the chip into a wide popup; it returns when the popup closes. */
    private fun showPopup() {
        if (popup != null) return
        val content = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            preferredSize = Dimension(JBUI.scale(POPUP_WIDTH), field.preferredSize.height + JBUI.scale(12))
        }
        popupContent = content
        input.onEscape = { popup?.cancel() }
        cards.remove(field)
        // CardLayout hid the field when it showed the chip, and removing it does not undo that.
        field.isVisible = true
        content.add(field, BorderLayout.CENTER)
        toolbar.updateActionsAsync()
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, input.focusComponent)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        created.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    content.remove(field)
                    cards.add(field, INLINE)
                    popup = null
                    popupContent = null
                    input.onEscape = null
                    popOutSuppressedUntil = System.nanoTime() + POP_OUT_SUPPRESS_NANOS
                    (cards.layout as CardLayout).show(cards, if (collapsed) CHIP else INLINE)
                    cards.revalidate()
                    cards.repaint()
                }
            }
        )
        popup = created
        created.showUnderneathOf(chip)
    }

    /** Builds a [LogFilterMatcher] from the current text and toggles, or null for a blank query. */
    fun matcher(): LogFilterMatcher? =
        if (revsetMode) null else LogFilterMatcher.create(text, useRegex, matchCase, matchWholeWords)

    /** Builds the whole-repo search revset (jj-idea-lpbv) from the current text and toggles. */
    fun revset(matchIds: Boolean = true): Expression? = when {
        revsetMode -> text.trim().takeIf { it.isNotEmpty() }?.let(::Expression)
        else -> logSearchRevset(text, useRegex, matchCase, matchWholeWords, matchIds)
    }

    /** jj-idea-ehwm.6: saves the revset in the box as a jj alias; only meaningful in revset mode with text. */
    private inner class SaveRevsetAliasAction : AnAction(
        JujutsuBundle.message("log.filter.revset.alias.action"),
        JujutsuBundle.message("log.filter.revset.alias.tooltip"),
        AllIcons.Actions.MenuSaveall
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = revsetMode && text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            onSaveRevsetAlias?.invoke(text.trim())
        }
    }

    private inner class FilterToggleAction(
        messageKeySuffix: String,
        icon: javax.swing.Icon,
        private val property: KMutableProperty1<LogSearchField, Boolean>,
        private val isModeSwitch: Boolean = false
    ) : ToggleAction(
            JujutsuBundle.message("log.filter.$messageKeySuffix"),
            JujutsuBundle.message("log.filter.$messageKeySuffix.tooltip"),
            icon
        ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            super.update(e)
            // The text toggles configure plain-text matching, which a revset doesn't use.
            e.presentation.isEnabled = isModeSwitch || !revsetMode
        }

        override fun isSelected(e: AnActionEvent) = property.get(this@LogSearchField)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            property.set(this@LogSearchField, state)
        }
    }
}

private const val INLINE = "inline"
private const val CHIP = "chip"
private const val MIN_TEXT_WIDTH = 110
private const val POPUP_WIDTH = 460

/** The text box inside a [LogSearchField]: a plain [SearchTextField] for the pickers, a [RevsetTextField] for the log. */
private interface SearchInput {
    var text: String

    /** The component that takes keyboard focus. */
    val focusComponent: JComponent

    /** Highlighting and completion on or off (only meaningful for a revset-capable input). */
    var highlighting: Boolean

    /** Called on Escape while the box is shown in a popup, where the editor would otherwise swallow the key. */
    var onEscape: (() -> Unit)?

    /** The box with the toggles [toolbar] beside it. */
    fun wrap(toolbar: JComponent): JComponent

    fun setPlaceholder(text: String)

    fun setTooltip(text: String)

    fun onTextChanged(listener: () -> Unit)

    fun onSubmit(listener: () -> Unit)

    /** Called when the user focuses or clicks the box, for inputs that can pop out (see [LogSearchField.popOut]). */
    fun onActivated(listener: () -> Unit)

    fun addCurrentTextToHistory()

    fun setError(message: String?, position: Pair<Int, Int>?)
}

private class PlainInput(historyKey: String?, popupAnchor: () -> Component?) : SearchInput {
    val searchTextField = if (historyKey != null) {
        persistentHistorySearchField(historyKey, popupAnchor)
    } else {
        SearchTextField(false)
    }
    private var wrapper: JComponent? = null

    override var text: String
        get() = searchTextField.text.orEmpty()
        set(value) {
            searchTextField.text = value
        }
    override val focusComponent: JComponent get() = searchTextField.textEditor
    override var highlighting = false
    override var onEscape: (() -> Unit)? = null

    // The platform paints a SearchFieldWithExtension's border on the wrapper, not the text editor.
    override fun wrap(toolbar: JComponent): JComponent = SearchFieldWithExtension(toolbar, searchTextField).also {
        wrapper = it
    }

    override fun setPlaceholder(text: String) {
        searchTextField.textEditor.emptyText.text = text
    }

    override fun setTooltip(text: String) {
        searchTextField.toolTipText = text
    }

    override fun onTextChanged(listener: () -> Unit) = searchTextField.textEditor.document.addDocumentListener(
        object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = listener()
        }
    )

    override fun onSubmit(listener: () -> Unit) = searchTextField.textEditor.addActionListener { listener() }

    override fun onActivated(listener: () -> Unit) = Unit

    override fun addCurrentTextToHistory() = searchTextField.addCurrentTextToHistory()

    override fun setError(message: String?, position: Pair<Int, Int>?) {
        wrapper?.putClientProperty("JComponent.outline", if (message != null) "error" else null)
        searchTextField.textEditor.toolTipText = message
    }
}

private class RevsetInput(
    project: Project,
    repos: () -> List<JujutsuRepository>,
    historyKey: String?,
    popupAnchor: () -> Component?
) : SearchInput {
    val revsetField = RevsetTextField(project, repos, historyKey, popupAnchor = popupAnchor).apply {
        highlightingEnabled = false
        sizeToColumns(SEARCH_COLUMNS)
    }
    private val wrapper = JPanel(BorderLayout())

    override var text: String
        get() = revsetField.text
        set(value) {
            revsetField.text = value
        }
    override val focusComponent: JComponent get() = revsetField.focusTarget
    override var highlighting: Boolean
        get() = revsetField.highlightingEnabled
        set(value) {
            revsetField.highlightingEnabled = value
        }
    override var onEscape: (() -> Unit)?
        get() = revsetField.onEscape
        set(value) {
            revsetField.onEscape = value
        }

    override fun wrap(toolbar: JComponent): JComponent = wrapper.apply {
        add(revsetField, BorderLayout.CENTER)
        add(toolbar, BorderLayout.EAST)
    }

    override fun setPlaceholder(text: String) = revsetField.setPlaceholder(text)

    override fun setTooltip(text: String) {
        wrapper.toolTipText = text
    }

    override fun onTextChanged(listener: () -> Unit) = revsetField.onTextChanged(listener)

    override fun onSubmit(listener: () -> Unit) {
        revsetField.onEnter = listener
    }

    override fun onActivated(listener: () -> Unit) {
        revsetField.editorField.addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = listener()
            }
        )
        // Clicking the box while it already has focus (e.g. after Esc closed the popup) pops it out again.
        revsetField.editorField.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = listener()
            }
        )
    }

    override fun addCurrentTextToHistory() = revsetField.addCurrentTextToHistory()

    // The editor's own border is the only decoration: blue while focused, red on error.
    override fun setError(message: String?, position: Pair<Int, Int>?) = revsetField.setError(message, position)
}

/** Preferred width of the log's search box, in characters; it shrinks (and collapses to a chip) below that. */
private const val SEARCH_COLUMNS = 28

/** How long after the popup closes that focus returning to the box doesn't pop it out again. */
private const val POP_OUT_SUPPRESS_NANOS = 400_000_000L

/**
 * Whether activating the search box should pop it out: in either mode (a long text search needs room as much as
 * a revset), only while the box is inline (not already the chip or in the popup), and not right after the popup
 * closed.
 */
internal fun shouldPopOut(
    collapsed: Boolean,
    popupShowing: Boolean,
    showing: Boolean,
    now: Long,
    suppressedUntil: Long
) = !collapsed && !popupShowing && showing && now >= suppressedUntil
