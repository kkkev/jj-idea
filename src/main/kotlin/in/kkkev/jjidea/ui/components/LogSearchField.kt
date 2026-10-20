package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
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
import `in`.kkkev.jjidea.jj.logSearchRevset
import `in`.kkkev.jjidea.ui.log.LogFilterMatcher
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val onSaveRevsetAlias: ((String) -> Unit)? = null
) : JPanel(BorderLayout()) {
    private val searchTextField = (
        if (historyKey !=
            null
        ) {
            persistentHistorySearchField(historyKey) { popupContent }
        } else {
            SearchTextField(false)
        }
    ).apply {
        textEditor.emptyText.text = placeholder
        tooltip?.let { toolTipText = it }

        textEditor.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = onFilterChanged()
            }
        )

        textEditor.addActionListener {
            val trimmed = text?.trim()
            if (!trimmed.isNullOrEmpty()) {
                addCurrentTextToHistory()
                onSubmitted(trimmed)
                popup?.cancel()
            }
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
            searchTextField.textEditor.emptyText.text =
                placeholderText()
            updateChip()
            onFilterChanged()
        }

    /**
     * Shows [message] (a revset the search can't use) as a red outline plus tooltip; null clears it. The
     * platform paints a [SearchFieldWithExtension]'s border on the wrapper, not the text editor, so the
     * outline goes on [field].
     */
    fun setError(message: String?) {
        error = message
        field.putClientProperty("JComponent.outline", if (message != null) "error" else null)
        searchTextField.textEditor.toolTipText = message
        field.repaint()
        updateChip()
    }

    private var error: String? = null

    /** Current search text. Setting it fires [onFilterChanged] the same as a keystroke would. */
    var text: String
        get() = searchTextField.text.orEmpty()
        set(value) {
            searchTextField.text = value
        }

    /** For focus requests and key listeners (e.g. up/down navigation in a list-based picker). */
    val textEditor: JTextField get() = searchTextField.textEditor

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
    ).apply { targetComponent = searchTextField.textEditor }

    /** The text field with its toggles; lives in [cards] unless the popup is showing it. */
    private val field = SearchFieldWithExtension(toolbar.component, searchTextField).apply {
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

    /** True while the field is showing as the compact chip (see [collapsible]). */
    internal val isCollapsed get() = collapsed

    init {
        cards.add(field, INLINE)
        cards.add(chip, CHIP)
        add(cards, BorderLayout.CENTER)
        if (collapsible) {
            searchTextField.textEditor.document.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = updateChip()
                }
            )
            addComponentListener(
                object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) = updateCollapsed()
                }
            )
            updateChip()
        }
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
        cards.remove(field)
        // CardLayout hid the field when it showed the chip, and removing it does not undo that.
        field.isVisible = true
        content.add(field, BorderLayout.CENTER)
        toolbar.updateActionsAsync()
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchTextField.textEditor)
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
