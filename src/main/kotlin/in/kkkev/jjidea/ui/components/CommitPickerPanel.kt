package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.log.CommitGraphBuilder
import `in`.kkkev.jjidea.ui.log.GraphNode
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
import `in`.kkkev.jjidea.ui.log.LogFilterMatcher
import `in`.kkkev.jjidea.ui.log.conciseRevsetError
import `in`.kkkev.jjidea.ui.log.fetchSearchResults
import `in`.kkkev.jjidea.ui.log.hideAllButGraphColumn
import `in`.kkkev.jjidea.ui.log.revsetErrorPosition
import `in`.kkkev.jjidea.ui.log.setGraphRenderer
import `in`.kkkev.jjidea.ui.log.topologicalSort
import `in`.kkkev.jjidea.util.LatestWinsGate
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * A single-repository commit picker: a [LogSearchField] (regex/match-case/whole-words, jj-idea
 * -lpbv) over a graph-only [JBTable], shared by [in.kkkev.jjidea.ui.rebase.RebaseDialog],
 * [in.kkkev.jjidea.ui.duplicate.DuplicateDialog], and [in.kkkev.jjidea.ui.squash.SquashIntoDialog]
 * so their near-identical search/table/selection-preservation code (jj-idea-tq4b) lives in one
 * place.
 *
 * The caller owns *what* is excluded (immutability, source ids, placement-mode validity) via the
 * [reload] predicate; this panel owns *how* that predicate combines with the search text, and how
 * the resulting rows and selection are (re)displayed. Call [reload] whenever the predicate itself
 * changes (e.g. a placement-mode radio button); a keystroke or toggle change alone re-applies the
 * last predicate automatically.
 *
 * On Enter (jj-idea-lpbv/jj-idea-tq4b), runs a whole-repo `jj log -r` query so a commit outside
 * the loaded [in.kkkev.jjidea.jj.LogCache] window (bounded by
 * [in.kkkev.jjidea.settings.JujutsuSettings.logChangeLimit]) can still be found, merges any hits
 * into [entries], and re-applies the current predicate — so the caller's exclusion rules still
 * apply to newly found commits. See [in.kkkev.jjidea.ui.rebase.RebaseDialog]'s KDoc for the one
 * known limitation this introduces (an off-window commit's ancestry may be incomplete).
 *
 * The search box also offers a revset mode (jj-idea-cl4d): on Enter, runs the revset as one `jj log -r`
 * and narrows the rows to its matches (merging any off-window hits into [entries], as the text search
 * does). Editing the text keeps the last applied result until the next Enter.
 *
 * In a [multiSelect] picker the selection is a persistent set, not whatever rows are visible: a pick
 * hidden by the search stays selected, so one can select A, search for B and add it. Only the caller's
 * predicate (a commit that is no longer a valid destination) drops a pick.
 *
 * [onReloaded] fires after every reload (typed, toggled, predicate change, or whole-repo search)
 * so the caller can refresh anything derived from the picker's rows or selection (a preview panel,
 * placement-mode availability).
 */
class CommitPickerPanel(
    private val project: Project,
    private val repo: JujutsuRepository,
    searchPlaceholder: String,
    private val multiSelect: Boolean,
    private val onReloaded: () -> Unit = {},
    /**
     * False for a picker whose rows are a small predefined set (e.g.
     * [in.kkkev.jjidea.ui.squash.SquashMode]'s parent-mode `candidates`) rather than the repo's
     * loaded log — searching or re-querying doesn't apply, so the search bar is hidden and the
     * automatic [in.kkkev.jjidea.jj.LogCache] load is skipped; the caller populates rows itself
     * via [setEntries] instead.
     */
    private val autoLoad: Boolean = true,
    /** Fires once, after the very first load completes (the automatic one, or the first [setEntries]). */
    private val onInitialLoad: () -> Unit = {}
) : JPanel(BorderLayout()), Disposable {
    /** The loaded set this panel filters — [repo.logCache.all][JujutsuRepository] plus any commits merged in by a whole-repo search. */
    var entries: List<LogEntry> = emptyList()
        private set

    /**
     * Set by [dispose]. Guards the async callbacks below against touching Swing state after the
     * owning dialog has closed — the caller is expected to `Disposer.register(disposable, this)`.
     */
    private var disposed = false

    override fun dispose() {
        disposed = true
    }

    private var predicateFor: (List<LogEntry>) -> (LogEntry) -> Boolean = { { true } }
    private var graphNodes: Map<ChangeKey, GraphNode> = emptyMap()

    val searchField: LogSearchField = LogSearchField(
        placeholder = searchPlaceholder,
        revsetModeAvailable = true,
        onFilterChanged = { onSearchChanged() },
        onSubmitted = { searchWholeRepo() },
        project = project,
        repos = { listOf(repo) }
    )

    /** Keys matched by the last applied revset (jj-idea-cl4d); null when not in revset mode or nothing applied. */
    private var revsetMatches: Set<ChangeKey>? = null

    /** The revset text [revsetMatches] came from, to tell when the box has been edited since. */
    private var appliedRevset: String? = null

    /**
     * What the user has picked, regardless of what the search currently shows ([multiSelect] only is it
     * authoritative; a single-select picker reads its table). Kept in sync with the table by
     * [syncSelectionFromTable] and pruned to [eligible] on each reload.
     */
    private val selection = LinkedHashSet<ChangeId>()

    /** True while [showRows] rewrites the table selection, so that doesn't read back as a user change. */
    private var restoring = false

    /** The entries passing the caller's predicate, ignoring the search; what [selection] may refer to. */
    private var eligible: List<LogEntry> = emptyList()

    private val tableModel = JujutsuLogTableModel()

    val table: JBTable = JBTable(tableModel).apply {
        setSelectionMode(
            if (multiSelect) ListSelectionModel.MULTIPLE_INTERVAL_SELECTION else ListSelectionModel.SINGLE_SELECTION
        )
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setStriped(JujutsuSettings.getInstance(project).state.stripedLogRows)
    }

    private val statusLabel = JBLabel().apply { isVisible = false }

    init {
        if (autoLoad) add(searchField, BorderLayout.NORTH)
        add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        table.hideAllButGraphColumn()
        table.selectionModel.addListSelectionListener { e -> if (!e.valueIsAdjusting) syncSelectionFromTable() }
        updateRenderer()
        // Renders row tooltips (bookmark/tag chips) via IconAwareHtmlPane instead of a plain
        // Swing tooltip, which paints chip <img> markup as a broken image (jj-idea-2md7).
        installIconAwareTableTooltip(table, project)

        if (autoLoad) loadEntries()
    }

    private fun loadEntries() {
        repo.runRecoverableInBackground(retry = ::loadEntries, modalityState = ModalityState.any()) {
            val loaded = repo.logCache.all
            runLater {
                if (disposed) return@runLater
                entries = loaded
                applyReload(andThen = onInitialLoad)
            }
        }
    }

    /** Applies [predicate] as the picker's exclusion rule and re-renders. Call on any change to it. */
    fun reload(predicate: (LogEntry) -> Boolean) = reloadFor { predicate }

    /**
     * Like [reload], but [predicateFor] is invoked once per reload with the current [entries] and
     * returns the per-row predicate. Do whole-set work (exclusion/descendant sets) in the factory,
     * never in the per-row predicate — that is O(n²) over the loaded log (jj-idea-i3bh). The factory
     * is re-run on every reload so it sees entries merged in by a whole-repo search.
     */
    fun reloadFor(predicateFor: (entries: List<LogEntry>) -> (LogEntry) -> Boolean) {
        this.predicateFor = predicateFor
        applyReload()
    }

    /**
     * Directly sets the loaded row set, bypassing the automatic [in.kkkev.jjidea.jj.LogCache]
     * load. Used only by [autoLoad] = false pickers (a predefined candidate list).
     */
    fun setEntries(newEntries: List<LogEntry>) {
        entries = newEntries
        applyReloadNow()
        onInitialLoad()
    }

    /**
     * The picks: the selected rows plus, in a [multiSelect] picker, earlier picks the search now hides. Reads
     * the table directly rather than [selection] for what is on screen, since a caller's own selection listener
     * can run before [syncSelectionFromTable] does (Swing notifies the last-registered listener first).
     */
    fun selectedEntries(): List<LogEntry> {
        val onTable = table.selectedRows.toList().mapNotNull { tableModel.getEntry(it) }
        if (!multiSelect || selection.isEmpty()) return onTable
        val shown = HashSet<ChangeId>()
        for (i in 0 until rowCount) entryAt(i)?.let { shown += it.id }
        val picked = onTable.mapTo(HashSet()) { it.id }
        return eligible.filter { it.id in picked || (it.id in selection && it.id !in shown) }
    }

    fun selectedIds(): Set<ChangeId> = selectedEntries().mapTo(mutableSetOf()) { it.id }

    private fun syncSelectionFromTable() {
        if (restoring) return
        val picked = table.selectedRows.toList().mapNotNull { tableModel.getEntry(it)?.id }
        if (!multiSelect) {
            selection.clear()
        } else {
            // Rows not on screen keep their pick; only what the user can see is taken from the table.
            for (i in 0 until rowCount) entryAt(i)?.let { selection.remove(it.id) }
        }
        selection.addAll(picked)
    }

    /** Typing or a toggle: drops a revset result that no longer applies, then re-filters. */
    private fun onSearchChanged() {
        if (!searchField.revsetMode) {
            dropRevsetResult()
        } else if (searchField.text.isBlank()) {
            // Typing over selected text empties the box for an instant (delete, then insert), which must not
            // throw the applied result away; only a box that is still empty once the event settles does.
            SwingUtilities.invokeLater {
                if (!disposed && searchField.revsetMode && searchField.text.isBlank() && revsetMatches != null) {
                    dropRevsetResult()
                    applyReload()
                }
            }
        }
        searchField.setError(null)
        applyReload()
    }

    private fun dropRevsetResult() {
        revsetMatches = null
        appliedRevset = null
    }

    fun entryAt(row: Int): LogEntry? = tableModel.getEntry(row)

    val rowCount: Int get() = tableModel.rowCount

    /** Selects the row for which [predicate] is true, if any (e.g. the working-copy row). */
    fun selectFirstMatching(predicate: (LogEntry) -> Boolean) {
        for (i in 0 until rowCount) {
            if (entryAt(i)?.let(predicate) == true) {
                table.setRowSelectionInterval(i, i)
                break
            }
        }
    }

    /** Bumped per [applyReload]; a computation whose token is stale is discarded. */
    private val reloadGate = LatestWinsGate()

    /**
     * Re-filters [entries] and re-renders. The predicate factory, filter and graph layout are
     * O(entries) and ran on the EDT, freezing the dialog on huge repos (jj-idea-v6w9), so they
     * run on a pooled thread and only the latest result is applied on the EDT. [andThen] runs
     * after the rows are applied, and only if this reload was not superseded.
     * [setEntries] (a small predefined set) uses the synchronous [applyReloadNow] instead.
     */
    private fun applyReload(andThen: () -> Unit = {}) {
        // Read on the EDT: the field's text and toggles aren't thread-safe.
        val matcher = searchField.matcher()
        val revset = revsetMatches
        val snapshot = entries
        val factory = predicateFor
        val token = reloadGate.next()
        statusLabel.text = JujutsuBundle.message("log.status.picker.loading")
        statusLabel.isVisible = true
        // ModalityState.any(): this runs from a modal dialog, and the default modality would
        // defer the runLater callback until the dialog closes.
        inFlight.incrementAndGet()
        runInBackground(ModalityState.any()) {
            val eligibleNow: List<LogEntry>
            val filtered: List<LogEntry>
            val nodes: Map<ChangeKey, GraphNode>
            try {
                eligibleNow = snapshot.filter(factory(snapshot))
                filtered = narrowBySearch(eligibleNow, matcher, revset)
                nodes = CommitGraphBuilder().buildGraph(filtered)
            } catch (t: Throwable) {
                runLater { inFlight.decrementAndGet() }
                throw t
            }
            runLater {
                inFlight.decrementAndGet()
                if (!disposed && reloadGate.isCurrent(token)) {
                    statusLabel.isVisible = false
                    showRows(eligibleNow, filtered, nodes)
                    andThen()
                }
            }
        }
    }

    private val inFlight = AtomicInteger()

    /** True while an async reload has been started but its result not yet delivered to the EDT. */
    @TestOnly
    internal fun isReloading() = inFlight.get() > 0

    private fun applyReloadNow() {
        reloadGate.next() // supersede any in-flight async reload
        val eligibleNow = entries.filter(predicateFor(entries))
        val filtered = narrowBySearch(eligibleNow, searchField.matcher(), revsetMatches)
        showRows(eligibleNow, filtered, CommitGraphBuilder().buildGraph(filtered))
    }

    private fun narrowBySearch(
        source: List<LogEntry>,
        matcher: LogFilterMatcher?,
        revset: Set<ChangeKey>?
    ): List<LogEntry> = source.filter { (matcher?.matches(it) ?: true) && (revset?.contains(it.key) ?: true) }

    /**
     * EDT-only: installs already-computed rows. [eligibleNow] is everything the caller's predicate allows,
     * [visible] the part of it the search shows. The selection is pruned to the former and re-applied to the
     * latter, so a pick the search hides survives (jj-idea-cl4d) but one the predicate rejects does not.
     */
    private fun showRows(eligibleNow: List<LogEntry>, visible: List<LogEntry>, nodes: Map<ChangeKey, GraphNode>) {
        // A selection made while the reload was in flight is already in [selection] via the table listener.
        eligible = eligibleNow
        val eligibleIds = eligibleNow.mapTo(HashSet()) { it.id }
        selection.retainAll(eligibleIds)
        graphNodes = nodes
        restoring = true
        try {
            tableModel.setEntries(visible)
            updateRenderer()

            // Cleared unconditionally (rather than only for multi-select, as the dialogs this
            // replaces used to do): a plain row-data-changed event can otherwise leave a stale row
            // index selected when the filtered set shrinks, in both single- and multi-select tables.
            table.clearSelection()
            for (i in 0 until rowCount) {
                val entry = entryAt(i) ?: continue
                if (entry.id !in selection) continue
                table.addRowSelectionInterval(i, i)
                if (!multiSelect) break
            }
        } finally {
            restoring = false
        }
        showStatusHints(shownIds = visible.mapTo(HashSet()) { it.id })
        onReloaded()
    }

    /** Hints under the box: an unapplied revset edit, and picks the search is currently hiding. */
    private fun showStatusHints(shownIds: Set<ChangeId>) {
        val hidden = if (multiSelect) selection.count { it !in shownIds } else 0
        val pending = searchField.revsetMode &&
            searchField.text.isNotBlank() &&
            searchField.text.trim() != appliedRevset
        val hints = listOfNotNull(
            JujutsuBundle.message("log.status.picker.revset.pending").takeIf { pending },
            JujutsuBundle.message("log.status.picker.selection.hidden", selection.size, hidden).takeIf { hidden > 0 }
        )
        statusLabel.isVisible = hints.isNotEmpty()
        if (hints.isNotEmpty()) statusLabel.text = hints.joinToString(" · ")
    }

    private fun updateRenderer() = table.setGraphRenderer(graphNodes)

    /**
     * Whole-repo search (jj-idea-lpbv/jj-idea-tq4b): runs [in.kkkev.jjidea.jj.logSearchRevset]
     * against [repo], stores any hits into [in.kkkev.jjidea.jj.LogCache] and [entries], and
     * re-applies the predicate so the caller's exclusion rules still cover the new commits.
     * [ModalityState.any] is required — this runs from a modal dialog, and the default modality
     * would defer the `runLater` callback until the dialog closes.
     */
    private fun searchWholeRepo() {
        if (searchField.revsetMode) {
            applyRevset()
            return
        }
        // Read on the EDT, before going to the background: the field's text and toggles aren't thread-safe.
        val withIds = searchField.revset() ?: return
        val textOnly = searchField.revset(matchIds = false)
        val settings = JujutsuSettings.getInstance(project)
        runInBackground(ModalityState.any()) {
            val found =
                fetchSearchResults(listOf(repo), { matchIds ->
                    if (matchIds) withIds else textOnly
                }) { settings.logChangeLimit(it) }[repo]
                    ?: emptyList()
            if (found.isNotEmpty()) repo.logCache.store(found)
            runLater {
                if (disposed) return@runLater
                val existingKeys = entries.mapTo(HashSet()) { it.key }
                val newCount = found.count { it.key !in existingKeys }
                entries = topologicalSort((entries + found).distinctBy { it.key })
                val searchText = searchField.text
                applyReload {
                    statusLabel.text = if (newCount > 0) {
                        JujutsuBundle.message("log.status.search.found", newCount, searchText)
                    } else {
                        JujutsuBundle.message("log.status.search.none", searchText)
                    }
                    statusLabel.isVisible = true
                }
            }
        }
    }

    /**
     * Revset mode's Enter (jj-idea-cl4d): one `jj log -r <revset> --limit logChangeLimit`, however many rows
     * are loaded. Hits outside the loaded window are merged into [entries] like a text search's; the rows then
     * narrow to the revset's matches. A revset jj rejects is shown on the field and leaves the rows as they were.
     */
    private fun applyRevset() {
        // Read on the EDT, before going to the background: the field's text isn't thread-safe.
        val text = searchField.text.trim().takeIf { it.isNotEmpty() } ?: return
        val limit = JujutsuSettings.getInstance(project).logChangeLimit(repo)
        runInBackground(ModalityState.any()) {
            val result = repo.logService.getLog(revset = Expression(text), limit = limit, quiet = true)
            result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { repo.logCache.store(it) }
            runLater {
                if (disposed) return@runLater
                val found = result.getOrNull()
                if (found == null) {
                    val message = result.exceptionOrNull()?.message.orEmpty()
                    searchField.setError(conciseRevsetError(message), revsetErrorPosition(message))
                    statusLabel.text = JujutsuBundle.message("log.status.revset.error", conciseRevsetError(message))
                    statusLabel.isVisible = true
                    return@runLater
                }
                searchField.setError(null)
                entries = topologicalSort((entries + found).distinctBy { it.key })
                revsetMatches = found.mapTo(HashSet()) { it.key }
                appliedRevset = text
                applyReload {
                    statusLabel.text = JujutsuBundle.message("log.status.picker.revset.matched", found.size)
                    statusLabel.isVisible = true
                }
            }
        }
    }
}
