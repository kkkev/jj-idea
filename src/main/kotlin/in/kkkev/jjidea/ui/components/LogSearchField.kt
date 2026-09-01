package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.SearchFieldWithExtension
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.logSearchRevset
import `in`.kkkev.jjidea.ui.log.LogFilterMatcher
import java.awt.BorderLayout
import java.awt.Dimension
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
 * [onFilterChanged] fires on every keystroke and toggle change. [onSubmitted] fires on Enter with
 * the trimmed, non-blank text — callers that want jj-idea-lpbv's whole-repo search wire it there;
 * callers that don't (e.g. a dialog willing to search only the already-loaded set) simply omit it.
 */
class LogSearchField(
    placeholder: String,
    tooltip: String? = null,
    withHistory: Boolean = false,
    private val onFilterChanged: () -> Unit = {},
    private val onSubmitted: (String) -> Unit = {}
) : JPanel(BorderLayout()) {
    private val searchTextField = SearchTextField(withHistory).apply {
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

    /** Current search text. Setting it fires [onFilterChanged] the same as a keystroke would. */
    var text: String
        get() = searchTextField.text.orEmpty()
        set(value) {
            searchTextField.text = value
        }

    /** For focus requests and key listeners (e.g. up/down navigation in a list-based picker). */
    val textEditor: JTextField get() = searchTextField.textEditor

    init {
        val filterActionsGroup = BackgroundActionGroup(
            FilterToggleAction("regex", AllIcons.Actions.RegexHovered, LogSearchField::useRegex),
            FilterToggleAction("matchcase", AllIcons.Actions.MatchCase, LogSearchField::matchCase),
            FilterToggleAction("words", AllIcons.Actions.Words, LogSearchField::matchWholeWords)
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "JujutsuLogSearchFilter",
            filterActionsGroup,
            true
        )
        toolbar.targetComponent = searchTextField.textEditor

        val field = SearchFieldWithExtension(toolbar.component, searchTextField).apply {
            // Allow the search field to shrink under width pressure, but not below a
            // sensible floor (matches IntelliJ's own VCS Log text filter field), so it
            // degrades gracefully instead of forcing the action toolbar off-screen.
            minimumSize = Dimension(JBUI.scale(150), preferredSize.height)
        }
        add(field, BorderLayout.CENTER)
    }

    /** Builds a [LogFilterMatcher] from the current text and toggles, or null for a blank query. */
    fun matcher(): LogFilterMatcher? = LogFilterMatcher.create(text, useRegex, matchCase, matchWholeWords)

    /** Builds the whole-repo search revset (jj-idea-lpbv) from the current text and toggles. */
    fun revset(): Expression? = logSearchRevset(text, useRegex, matchCase, matchWholeWords)

    private inner class FilterToggleAction(
        messageKeySuffix: String,
        icon: javax.swing.Icon,
        private val property: KMutableProperty1<LogSearchField, Boolean>
    ) : ToggleAction(
            JujutsuBundle.message("log.filter.$messageKeySuffix"),
            JujutsuBundle.message("log.filter.$messageKeySuffix.tooltip"),
            icon
        ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent) = property.get(this@LogSearchField)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            property.set(this@LogSearchField, state)
        }
    }
}
