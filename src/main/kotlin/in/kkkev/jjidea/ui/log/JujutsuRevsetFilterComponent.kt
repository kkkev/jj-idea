package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.ui.components.RevsetTextField
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * The log tab's Scope chip (jj-idea-vqpn, GitHub #116; jj-idea-ehwm.4): the tab's base revset,
 * which the search box and the other filters narrow within. Shown ahead of the search box, outside
 * the overflow-collapsing filter strip, and kept by "clear filters".
 *
 * Validates the typed revset against jj (one `--limit 1` call per repo, see [validateRevsetFilter])
 * and hands it to [tableModel] as the tab's scope. The table's owner then has jj resolve it together
 * with every other filter ([UnifiedJujutsuLogDataLoader.applyFilter]), so matches anywhere in
 * history show up, not only those in the loaded window.
 *
 * Unlike every other chip here, editing this one needs free-text entry rather than a fixed list
 * of choices, so [showPopup] is overridden with a small non-modal popup (a history-backed
 * [SearchTextField], matching [in.kkkev.jjidea.ui.components.LogSearchField]'s component) instead
 * of the base class's [createActionGroup]-built menu — [createActionGroup] itself is unused here
 * and returns an empty group.
 */
class JujutsuRevsetFilterComponent(
    private val project: Project,
    private val repos: () -> Collection<JujutsuRepository>,
    private val baseFor: (JujutsuRepository) -> Revset,
    private val tableModel: JujutsuLogTableModel
) : JujutsuFilterComponent(JujutsuBundle.message("log.filter.revset")) {
    private var currentRevset: String = ""

    override val codeStyledValue = true

    override val leadingIcon: Icon = AllIcons.Ide.LocalScope

    override val hintToolTipText: String = JujutsuBundle.message("log.filter.revset.tooltip")

    // Bumped on every validation (and reset) so a stale in-flight result (e.g. the popup was
    // reopened with a new value, or the filter was reset while an edit was in flight) can't clobber a newer one.
    private var generation = 0

    override fun getCurrentText(): String = currentRevset.ifEmpty { "all()" }

    override fun isValueSelected(): Boolean = currentRevset.isNotEmpty()

    /**
     * The currently-applied revset text, for the panel to persist into
     * [in.kkkev.jjidea.settings.LogWindowConfig.revsetFilter]. Public, unlike the protected
     * [getCurrentText] this mirrors, matching [JujutsuReferenceFilterComponent.getSelectedReferenceName]
     * and [JujutsuAuthorFilterComponent.getSelectedAuthors]'s existing pattern for exposing a
     * chip's persistable value to its owning panel.
     */
    fun getRevsetText(): String = currentRevset

    fun initialize() {
        // No-op: unlike the other chips, applying happens from the popup's Enter handler, not a
        // generic addChangeListener callback — kept for symmetry with the other filter
        // components' initialize()/apply wiring at their call sites.
    }

    /**
     * Restores a persisted revset from [in.kkkev.jjidea.settings.LogWindowConfig.revsetFilter] as
     * the table's scope — without [notifyFilterChanged] (that would immediately re-persist the value
     * that was just read from persistence). The panel applies the restored filters once after the
     * first load. A blank [revset] leaves the filter inactive.
     */
    fun setInitialRevset(revset: String) {
        currentRevset = revset
        refreshPresentation()
        tableModel.setScope(revset)
    }

    override fun createActionGroup(): ActionGroup = BackgroundActionGroup()

    override fun doResetFilter() {
        generation++
        currentRevset = ""
        setErrorText(null)
        tableModel.setScope("")
        notifyFilterChanged()
    }

    override fun showPopup() {
        val panel = EditPanel()
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.preferredFocus)
            .setTitle(JujutsuBundle.message("log.filter.revset.title"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        panel.popup = popup
        panel.installValidator(popup)
        popup.showUnderneathOf(this)
    }

    /** Runs [validateRevsetFilter] in the background for [revset] and applies [onValidated] on EDT. */
    private fun validate(revset: String, onValidated: (Map<JujutsuRepository, String>) -> Unit) {
        val gen = ++generation
        val repoSnapshot = repos()
        runInBackground {
            val errors = validateRevsetFilter(repoSnapshot, revset, baseFor)
            runLater {
                if (gen == generation) onValidated(errors)
            }
        }
    }

    private fun formatErrors(errors: Map<JujutsuRepository, String>): String =
        if (errors.size == 1) {
            JujutsuBundle.message("log.filter.revset.error", conciseRevsetError(errors.values.first()))
        } else {
            errors.entries.joinToString("\n") { (repo, message) ->
                JujutsuBundle.message("log.filter.revset.error.multiple", repo.displayName, conciseRevsetError(message))
            }
        }

    /** The popup's contents: a history-backed text field, a hint line, and an error line. */
    private inner class EditPanel : JPanel(BorderLayout()) {
        var popup: JBPopup? = null

        private val searchField = RevsetTextField(
            project,
            { repos().toList() },
            "jujutsu.log.scope.history",
            placeholder = JujutsuBundle.message("log.filter.revset.placeholder"),
            initialText = currentRevset,
            popupAnchor = { this }
        ).apply {
            sizeToColumns(SCOPE_FIELD_COLUMNS)
        }
        private val statusLabel = JBLabel(JujutsuBundle.message("log.filter.revset.hint")).apply {
            foreground = UIUtil.getLabelInfoForeground()
        }
        private val docsLink = HyperlinkLabel(JujutsuBundle.message("log.filter.revset.docs")).apply {
            setHyperlinkTarget(REVSET_DOCS)
        }
        private val aliasLink = HyperlinkLabel(JujutsuBundle.message("log.filter.revset.alias.action")).apply {
            toolTipText = JujutsuBundle.message("log.filter.revset.alias.tooltip")
            addHyperlinkListener { saveAsAlias() }
        }

        private var validator: ComponentValidator? = null

        /** Errors show as the platform's validation balloon (as in the alias dialog), so long ones wrap rather than truncate. */
        fun installValidator(parent: JBPopup) {
            validator = ComponentValidator(parent).installOn(searchField.editorField)
        }

        val preferredFocus get() = searchField.focusTarget

        init {
            border = JBUI.Borders.empty(6, 8)
            // The hint/error line goes above the field so the field is the popup's bottom edge: its history
            // drop-down then opens directly beneath it instead of overlapping (and hiding behind) this popup.
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.emptyBottom(6)
                    add(statusLabel, BorderLayout.CENTER)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
                            isOpaque = false
                            add(aliasLink)
                            add(docsLink)
                        },
                        BorderLayout.EAST
                    )
                },
                BorderLayout.NORTH
            )
            add(searchField, BorderLayout.SOUTH)

            searchField.onTextChanged { setError(null) }
            searchField.onEnter = { apply() }
            searchField.onEscape = { popup?.cancel() }
        }

        private fun apply() {
            val text = searchField.text.trim()
            if (text.isEmpty()) {
                doResetFilter()
                popup?.cancel()
                return
            }
            statusLabel.text = JujutsuBundle.message("log.filter.revset.resolving")
            statusLabel.foreground = UIUtil.getLabelInfoForeground()
            searchField.isEnabled = false
            validate(text) { errors ->
                searchField.isEnabled = true
                if (errors.isEmpty()) {
                    searchField.addCurrentTextToHistory()
                    currentRevset = text
                    tableModel.setScope(text)
                    setErrorText(null)
                    notifyFilterChanged()
                    popup?.cancel()
                } else {
                    setError(formatErrors(errors))
                    locateError(text, errors)
                }
            }
        }

        /**
         * [validate] checks the scope ANDed with the repo revset, so jj's column is about that compiled text.
         * Re-checks the scope alone (one more `jj log` per repo, only on failure) to get a column that maps
         * onto the field; if the scope passes alone the fault is the repo revset and nothing is underlined.
         */
        private fun locateError(text: String, errors: Map<JujutsuRepository, String>) {
            val gen = generation
            val repoSnapshot = repos()
            runInBackground {
                val own = validateRevsetFilter(repoSnapshot, text) { Revset.Default }
                runLater {
                    val position = own.values.firstNotNullOfOrNull(::revsetErrorPosition)
                    if (gen == generation && position != null && searchField.text.trim() == text) {
                        setError(formatErrors(errors), position)
                    }
                }
            }
        }

        /** jj-idea-ehwm.6: names the typed revset as a jj alias, then makes the alias this tab's scope. */
        private fun saveAsAlias() {
            val text = searchField.text.trim()
            val targetRepos = repos()
            if (text.isEmpty() || targetRepos.isEmpty()) return
            popup?.cancel()
            SaveRevsetAliasDialog(targetRepos, text, baseFor) { name ->
                generation++
                currentRevset = name
                tableModel.setScope(name)
                setErrorText(null)
                notifyFilterChanged()
            }.show()
        }

        private fun setError(message: String?, position: Pair<Int, Int>? = null) {
            statusLabel.text = JujutsuBundle.message("log.filter.revset.hint")
            statusLabel.foreground = UIUtil.getLabelInfoForeground()
            // The validator draws the outline and the balloon; the field adds the squiggle at jj's column.
            validator?.updateInfo(message?.let { ValidationInfo(it, searchField.editorField) })
            searchField.setError(message, position, showOutline = false)
        }
    }

    private companion object {
        const val SCOPE_FIELD_COLUMNS = 70
        const val REVSET_DOCS = "https://docs.jj-vcs.dev/latest/revsets/"
    }
}
