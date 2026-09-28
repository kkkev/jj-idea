package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Custom revset log filter chip (jj-idea-vqpn, GitHub #116).
 *
 * Client-side, like [JujutsuPathsFilterComponent]: resolves the typed revset to a [ChangeKey] set
 * (one ids-only `jj log` call per repo, ANDed with the repo-level revset — see
 * [resolveRevsetFilter]/[revsetFilterQuery]) and hands that set to [tableModel], which intersects
 * it with every other active chip in [JujutsuLogTableModel]'s existing filter pipeline.
 *
 * Unlike every other chip here, editing this one needs free-text entry rather than a fixed list
 * of choices, so [showPopup] is overridden with a small non-modal popup (a history-backed
 * [SearchTextField], matching [in.kkkev.jjidea.ui.components.LogSearchField]'s component) instead
 * of the base class's [createActionGroup]-built menu — [createActionGroup] itself is unused here
 * and returns an empty group.
 */
class JujutsuRevsetFilterComponent(
    private val repos: () -> Collection<JujutsuRepository>,
    private val baseFor: (JujutsuRepository) -> Revset,
    private val tableModel: JujutsuLogTableModel,
    /** Called from [reresolve] (never from the interactive edit popup, which shows its own
     * inline error) when a background re-resolve fails, so the owning panel can surface it as a
     * status-bar message alongside the chip's own [setErrorText] state. */
    private val onReresolveError: (String) -> Unit = {}
) : JujutsuFilterComponent(JujutsuBundle.message("log.filter.revset")) {
    private var currentRevset: String = ""

    /**
     * The full resolved id set for [currentRevset] — every match, not just the ones currently
     * loaded. Exposed so the panel can report matches that exist but fall outside the loaded log
     * window (jj-idea-vqpn, GitHub #116) instead of a narrow filter over a deep history looking
     * like it silently found nothing.
     */
    private var matchedKeys: Set<ChangeKey>? = null

    // Bumped on every resolve (interactive edit, or a background re-resolve) so a stale
    // in-flight result (e.g. the popup was reopened with a new value, or forceRefresh() fired a
    // re-resolve while an edit was still in flight) can't clobber a newer one.
    private var generation = 0

    override fun getCurrentText(): String = currentRevset

    override fun isValueSelected(): Boolean = currentRevset.isNotEmpty()

    /**
     * The currently-applied revset text, for the panel to persist into
     * [in.kkkev.jjidea.settings.LogWindowConfig.revsetFilter]. Public, unlike the protected
     * [getCurrentText] this mirrors, matching [JujutsuReferenceFilterComponent.getSelectedReferenceName]
     * and [JujutsuAuthorFilterComponent.getSelectedAuthors]'s existing pattern for exposing a
     * chip's persistable value to its owning panel.
     */
    fun getRevsetText(): String = currentRevset

    /** See [matchedKeys]'s doc. `null` when the filter is inactive or its last resolve failed. */
    fun getMatchedKeys(): Set<ChangeKey>? = matchedKeys

    fun initialize() {
        // No-op: unlike the other chips, applying happens from the popup's Enter handler, not a
        // generic addChangeListener callback — kept for symmetry with the other filter
        // components' initialize()/apply wiring at their call sites.
    }

    /**
     * Restores a persisted revset from [in.kkkev.jjidea.settings.LogWindowConfig.revsetFilter]
     * and re-resolves it — without [notifyFilterChanged] (that would immediately re-persist the
     * value that was just read from persistence, and the panel's own restore-from-config path
     * doesn't need it). A blank [revset] leaves the filter inactive with nothing to resolve.
     */
    fun setInitialRevset(revset: String) {
        currentRevset = revset
        refreshPresentation()
        if (revset.isNotEmpty()) reresolve()
    }

    /**
     * Re-resolves the currently-applied revset in the background and re-applies it — used when
     * the loaded set can have changed under the filter (an operation via
     * [in.kkkev.jjidea.jj.JujutsuStateModel.logRefresh], an explicit Refresh, or the tab's repo
     * selection changing), not just when the user edits the text. A resolve failure here (e.g. a
     * persisted revset referencing a bookmark that's since been deleted) sets the chip's error
     * state via [setErrorText] rather than silently clearing or falling back to unfiltered — the
     * last successfully-resolved key set stays applied to [tableModel] so the graph doesn't
     * suddenly appear empty or unfiltered because of a transient resolve failure.
     *
     * No-op while the filter is inactive (nothing persisted to re-resolve).
     */
    fun reresolve() {
        if (currentRevset.isEmpty()) return
        resolve(currentRevset, onResolved = { result ->
            if (result.errors.isEmpty()) {
                matchedKeys = result.keys
                tableModel.setRevsetFilter(result.keys)
                setErrorText(null)
            } else {
                val message = formatErrors(result.errors)
                setErrorText(message)
                onReresolveError(message)
            }
        })
    }

    override fun createActionGroup(): ActionGroup = BackgroundActionGroup()

    override fun doResetFilter() {
        generation++
        currentRevset = ""
        matchedKeys = null
        setErrorText(null)
        tableModel.setRevsetFilter(null)
        notifyFilterChanged()
    }

    override fun showPopup() {
        val panel = EditPanel()
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.preferredFocus)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        panel.popup = popup
        popup.showUnderneathOf(this)
    }

    /** Runs [resolveRevsetFilter] in the background for [revset] and applies [onResolved] on EDT. */
    private fun resolve(revset: String, onResolved: (RevsetFilterResult) -> Unit) {
        val gen = ++generation
        val repoSnapshot = repos()
        runInBackground {
            val result = resolveRevsetFilter(repoSnapshot, revset, baseFor)
            runLater {
                if (gen == generation) onResolved(result)
            }
        }
    }

    private fun formatErrors(errors: Map<JujutsuRepository, String>): String =
        if (errors.size == 1) {
            JujutsuBundle.message("log.filter.revset.error", errors.values.first())
        } else {
            errors.entries.joinToString("; ") { (repo, message) ->
                JujutsuBundle.message("log.filter.revset.error.multiple", repo.displayName, message)
            }
        }

    /** The popup's contents: a history-backed text field, a hint line, and an error line. */
    private inner class EditPanel : JPanel(BorderLayout()) {
        var popup: JBPopup? = null

        private val searchField = SearchTextField(true).apply {
            text = currentRevset
            textEditor.emptyText.text = JujutsuBundle.message("log.filter.revset.placeholder")
            preferredSize = Dimension(JBUI.scale(360), preferredSize.height)
        }
        private val statusLabel = JBLabel(JujutsuBundle.message("log.filter.revset.hint")).apply {
            foreground = UIUtil.getLabelInfoForeground()
        }

        val preferredFocus get() = searchField.textEditor

        init {
            border = JBUI.Borders.empty(6, 8)
            add(searchField, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)

            searchField.textEditor.document.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = setError(null)
                }
            )
            searchField.textEditor.addActionListener { apply() }
            searchField.textEditor.addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) popup?.cancel()
                    }
                }
            )
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
            resolve(text) { result ->
                searchField.isEnabled = true
                if (result.errors.isEmpty()) {
                    searchField.addCurrentTextToHistory()
                    currentRevset = text
                    matchedKeys = result.keys
                    tableModel.setRevsetFilter(result.keys)
                    setErrorText(null)
                    notifyFilterChanged()
                    popup?.cancel()
                } else {
                    setError(formatErrors(result.errors))
                }
            }
        }

        private fun setError(message: String?) {
            statusLabel.text = message ?: JujutsuBundle.message("log.filter.revset.hint")
            statusLabel.foreground =
                if (message != null) NamedColorUtil.getErrorForeground() else UIUtil.getLabelInfoForeground()
            searchField.textEditor.putClientProperty("JComponent.outline", if (message != null) "error" else null)
            searchField.textEditor.repaint()
        }
    }
}
