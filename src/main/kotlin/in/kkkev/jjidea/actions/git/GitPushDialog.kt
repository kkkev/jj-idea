package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.git.GitPushDialog.Companion.loadAllDialogData
import `in`.kkkev.jjidea.actions.git.GitPushDialog.Companion.loadDialogData
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.components.TextCanvas
import `in`.kkkev.jjidea.ui.components.TextListCellRenderer
import `in`.kkkev.jjidea.ui.components.append
import javax.swing.AbstractButton
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Dialog for configuring a `jj git push` operation.
 *
 * Options:
 * - Repository selector (only shown when multiple repos are provided)
 * - Remote selector (populated from preloaded remote list)
 * - Push scope: default tracking bookmarks, specific bookmark, or all bookmarks
 * - Bookmark selector (when "specific bookmark" is selected, filtered by tracked bookmarks for the selected remote;
 *   includes pending-deletion bookmarks so a single deletion can be pushed explicitly)
 *
 * **Important**: Remotes and bookmarks must be loaded off EDT before constructing this dialog.
 * Use [loadDialogData] to load data on a background thread, or [loadAllDialogData] for multiple repos.
 *
 * @param initialBookmark When set (from [in.kkkev.jjidea.actions.bookmark.pushBookmarkAction]),
 *   opens pre-selected to "Specific bookmark" scope with this bookmark chosen. Lets a per-bookmark
 *   push still go through this dialog's review step (mutating a remote is not something to fire
 *   with no confirmation) while skipping the repo/remote/bookmark selection clicks a fresh
 *   dialog would otherwise need.
 * @param initialRemote The remote to preselect alongside [initialBookmark] — the caller already
 *   knows which remote a specific per-bookmark push targets. Falls back to [pickInitialRemote]'s
 *   "whichever remote already tracks this bookmark" heuristic when null.
 * @param changeTargets The revisions to offer for the "create bookmark(s) for change(s)" scope
 *   (GitHub #65, jj-idea-fmzr/jj-idea-ikof) — one entry per selected log commit, or a single `@`/
 *   `@-` fallback when nothing is selected. Resolved by the caller — not this dialog, which stays
 *   free of command execution — via [changeTargetsFor]. Empty hides the scope entirely.
 * @param changeTargetsRepo The single repo [changeTargets] belongs to (null if the caller had no
 *   unambiguous single repo, e.g. a log selection spanning multiple repos). The 4th scope's row
 *   is only shown while [selectedRepo] equals this — see [updateForRepoChange] — since showing a
 *   change target that belongs to a different repo than the one about to be pushed would be
 *   actively misleading.
 * @param defaultScope Which scope the dialog opens on when [initialBookmark] doesn't force
 *   [PushScope.BOOKMARK] — driven by the user's "default push scope" setting.
 */
class GitPushDialog internal constructor(
    project: Project,
    private val allData: Map<JujutsuRepository, DialogData>,
    initialRepo: JujutsuRepository,
    initialBookmark: Bookmark? = null,
    initialRemote: Remote? = null,
    private val changeTargets: List<Revision> = emptyList(),
    private val changeTargetsRepo: JujutsuRepository? = null,
    defaultScope: PushScope = PushScope.DEFAULT
) : DialogWrapper(project) {
    /**
     * Result of the push dialog — the user's chosen parameters including which repo to push.
     */
    data class GitPushSpec(
        val repo: JujutsuRepository,
        val remote: Remote?,
        val bookmark: Bookmark?,
        val allBookmarks: Boolean,
        val changeRevisions: List<Revision> = emptyList()
    )

    /**
     * Preloaded dialog data: remotes and tracked bookmarks per remote.
     */
    data class DialogData(
        val remotes: List<Remote>,
        val trackedByRemote: Map<Remote, List<Bookmark>>,
        val allLocal: List<Bookmark>
    )

    var result: GitPushSpec? = null
        private set

    private var selectedRepo = initialRepo
    private var selectedRemote = initialRemote
        ?: initialBookmark?.let { pickInitialRemote(currentData(), it) }
        ?: currentRemotes().firstOrNull()
    private var pushScope = when {
        initialBookmark != null -> PushScope.BOOKMARK
        defaultScope == PushScope.CHANGE && !changeScopeAvailable() -> PushScope.DEFAULT
        else -> defaultScope
    }
    private var selectedBookmark = initialBookmark?.let { bm -> currentBookmarks().firstOrNull { it.name == bm.name } }
        ?: currentBookmarks().firstOrNull()
    private val remoteModel = DefaultComboBoxModel(currentRemotes().toTypedArray())
    private val bookmarkModel = DefaultComboBoxModel(currentBookmarks().toTypedArray())

    // Exposed for tests (see SplitDialog for the same pattern) so a test can drive the real
    // addActionListener callbacks below rather than reimplementing them.
    internal var repoComboBox: JComboBox<*>? = null
        private set
    internal var remoteComboBox: JComboBox<*>? = null
        private set
    internal val bookmarkComboBox = ComboBox(bookmarkModel).apply { renderer = BookmarkRenderer() }
    internal var specificBookmarkRadioButton: AbstractButton? = null
        private set

    // The 4th scope's row, captured in createCenterPanel() so updateForRepoChange() can toggle
    // its visibility — there's no ComponentPredicate for "the repo combo currently equals X", so
    // this follows the same imperative pattern already used for remoteModel/bookmarkModel above
    // rather than a declarative binding.
    private var changeScopeRow: Row? = null

    // bindScope() only writes the backing property when the *user* clicks a radio button
    // (RadioScopeBinding.kt); it doesn't move the Swing selection when pushScope is changed
    // programmatically. Captured so updateForRepoChange() can re-select Default in the UI (not
    // just the backing property) when it auto-reverts away from a just-hidden Change scope —
    // the enclosing ButtonGroup then deselects Change for us.
    private var defaultScopeRadioButton: AbstractButton? = null

    // Guards against re-entry: repopulating remoteModel below fires the remote combo's own
    // actionListener (removeAllElements()/addAll() both fire contentsChanged), which would
    // otherwise stomp selectedRemote/selectedBookmark with a stale/null value mid-update.
    private var updatingModels = false

    private fun currentData() = allData[selectedRepo] ?: DialogData(emptyList(), emptyMap(), emptyList())

    private fun currentRemotes() = currentData().remotes

    private fun currentBookmarks(): List<Bookmark> {
        val data = currentData()
        val tracked = data.trackedByRemote[selectedRemote] ?: emptyList()
        return mergeBookmarks(tracked, data.allLocal)
    }

    /** Whether the 4th scope has anything to offer for [selectedRepo] right now. */
    private fun changeScopeAvailable() = changeTargets.isNotEmpty() && selectedRepo == changeTargetsRepo

    /** Persisted by name in [in.kkkev.jjidea.settings.JujutsuSettingsState.defaultPushScope]. */
    internal enum class PushScope { DEFAULT, BOOKMARK, ALL, CHANGE }

    private class BookmarkRenderer : TextListCellRenderer<Bookmark>() {
        override fun render(canvas: TextCanvas, value: Bookmark) {
            canvas.append(value)
            when {
                value.deleted -> canvas.grey { canvas.italic { append(" (deleted)") } }
                !value.tracked -> canvas.grey { canvas.italic { append(" (new)") } }
            }
        }
    }

    init {
        title = JujutsuBundle.message("dialog.git.push.title")
        setOKButtonText(JujutsuBundle.message("dialog.git.push.button"))
        init()
    }

    private fun updateForRepoChange() {
        updatingModels = true
        try {
            val remotes = currentRemotes()
            selectedRemote = remotes.firstOrNull()
            remoteModel.replaceContents(remotes, selectedRemote)
        } finally {
            updatingModels = false
        }
        updateBookmarks()

        val available = changeScopeAvailable()
        changeScopeRow?.visible(available)
        if (!available && pushScope == PushScope.CHANGE) {
            defaultScopeRadioButton?.isSelected = true
            pushScope = PushScope.DEFAULT
        }
    }

    private fun updateBookmarks() {
        updatingModels = true
        try {
            val bookmarks = currentBookmarks()
            selectedBookmark = bookmarks.firstOrNull()
            bookmarkModel.replaceContents(bookmarks, selectedBookmark)
        } finally {
            updatingModels = false
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        if (allData.size > 1) {
            row(JujutsuBundle.message("dialog.git.push.repository.label")) {
                comboBox(allData.keys.toList())
                    .applyToComponent {
                        // Replacement (textListCellRenderer) unavailable until 2026.2
                        @Suppress("removal")
                        renderer = SimpleListCellRenderer.create("") { it.displayName }
                        selectedItem = selectedRepo
                        addActionListener {
                            if (updatingModels) return@addActionListener
                            selectedRepo = selectedItem as? JujutsuRepository ?: return@addActionListener
                            updateForRepoChange()
                        }
                        repoComboBox = this
                    }
            }
        }

        if (allData.values.any { it.remotes.size > 1 }) {
            row(JujutsuBundle.message("dialog.git.push.remote.label")) {
                comboBox(remoteModel)
                    // Deliberately not `.toNullableProperty()`: its `!!` NPEs whenever the combo's
                    // selection is transiently null, e.g. mid-repopulate (jj-idea-idm0).
                    // `bindItem(KMutableProperty0<T?>)` handles a null selection safely.
                    .bindItem(::selectedRemote)
                    .applyToComponent {
                        addActionListener {
                            if (updatingModels) return@addActionListener
                            selectedRemote = selectedItem as? Remote
                            updateBookmarks()
                        }
                        remoteComboBox = this
                    }
            }
        }

        buttonsGroup {
            row {
                val rb = radioButton(JujutsuBundle.message("dialog.git.push.scope.default"))
                    .bindScope(::pushScope, PushScope.DEFAULT)
                defaultScopeRadioButton = rb.component
            }
            row {
                val rb = radioButton(JujutsuBundle.message("dialog.git.push.scope.bookmark"))
                    .bindScope(::pushScope, PushScope.BOOKMARK)
                specificBookmarkRadioButton = rb.component
                cell(bookmarkComboBox)
                    .bindItem(::selectedBookmark)
                    .enabledIf(rb.component.selected)
                    .validationOnApply {
                        if (pushScope == PushScope.BOOKMARK && it.selectedItem == null) {
                            error(JujutsuBundle.message("dialog.git.push.bookmark.required"))
                        } else {
                            null
                        }
                    }
            }
            row {
                radioButton(JujutsuBundle.message("dialog.git.push.scope.all"))
                    .bindScope(::pushScope, PushScope.ALL)
            }
            row {
                radioButton(changeScopeLabel())
                    .bindScope(::pushScope, PushScope.CHANGE)
            }.visible(changeScopeAvailable()).also { changeScopeRow = it }
        }
    }

    /**
     * "Create bookmark for change X" for a single target, or a pluralized count for several.
     * The empty case (row always hidden — see [changeScopeAvailable]) is handled defensively
     * rather than left to crash on [List.single].
     */
    private fun changeScopeLabel(): String = when (changeTargets.size) {
        0 -> JujutsuBundle.message("dialog.git.push.scope.change", "")
        1 -> JujutsuBundle.message("dialog.git.push.scope.change", changeTargets.single().short)
        else -> JujutsuBundle.message("dialog.git.push.scope.change.plural", changeTargets.size)
    }

    override fun doOKAction() {
        applyFields()
        result = GitPushSpec(
            repo = selectedRepo,
            remote = selectedRemote,
            bookmark = selectedBookmark.takeIf { pushScope == PushScope.BOOKMARK },
            allBookmarks = pushScope == PushScope.ALL,
            changeRevisions = if (pushScope == PushScope.CHANGE) changeTargets else emptyList()
        )
        super.doOKAction()
    }

    companion object {
        // Outputs name\0present\0 per bookmark (remote-tracking entries produce empty strings, filtered by split)
        private const val LOCAL_BOOKMARK_TEMPLATE =
            """if(remote, "", name ++ "\0" ++ present ++ "\0")"""

        /**
         * Parses [in.kkkev.jjidea.settings.JujutsuSettingsState.defaultPushScope]'s stored name back
         * into a [PushScope], falling back to [PushScope.DEFAULT] for an unrecognised or legacy
         * value (e.g. a name from a future release the current one doesn't know about).
         */
        internal fun parsePushScope(name: String): PushScope =
            runCatching { PushScope.valueOf(name) }.getOrDefault(PushScope.DEFAULT)

        /**
         * Merges the bookmarks tracked against the selected remote with the full local bookmark
         * list, for display in the "Specific bookmark" dropdown. [tracked] entries win; a [Bookmark]
         * appearing in both is deduplicated by [name][BookmarkName], not by full data-class equality
         * — `tracked` differs between the two lists' parse calls (`tracked = true` vs `tracked =
         * false`), so a naive `!in` check never matched and every tracked bookmark appeared twice,
         * the second time mislabelled "(new)" (jj-idea-ehki).
         */
        internal fun mergeBookmarks(tracked: List<Bookmark>, allLocal: List<Bookmark>): List<Bookmark> =
            tracked + allLocal.filterNot { local -> tracked.any { it.name == local.name } }

        /**
         * Picks which remote to preselect when opening the dialog for a specific bookmark
         * ([initialBookmark]): whichever remote already tracks it, or the first available remote
         * for a bookmark that's never been pushed.
         */
        internal fun pickInitialRemote(data: DialogData, bookmark: Bookmark): Remote? =
            data.remotes.firstOrNull { remote ->
                data.trackedByRemote[remote].orEmpty().any { it.name == bookmark.name }
            } ?: data.remotes.firstOrNull()

        internal fun parseBookmarks(stdout: String, tracked: Boolean): List<Bookmark> =
            stdout.split(' ')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .chunked(2)
                .filter { it.size == 2 }
                .map { (name, present) -> Bookmark(name = name, tracked = tracked, deleted = present == "false") }
                .toList()

        /**
         * Load the list of Git remotes for a repository. Call off EDT.
         */
        fun loadRemotes(repo: JujutsuRepository): List<Remote> =
            repo.commandExecutor.gitRemoteList().let { result ->
                if (result.isSuccess) {
                    result.stdout.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { it.substringBefore(' ') }
                        .map { Remote(it) }
                } else {
                    emptyList()
                }
            }

        /** Runs `jj bookmark list --tracked`, scoped to [remote] and optionally [revision]. Call off EDT. */
        private fun loadTrackedBookmarks(repo: JujutsuRepository, remote: Remote, revision: Revision?): List<Bookmark> =
            repo.commandExecutor.bookmarkList(LOCAL_BOOKMARK_TEMPLATE, remote, true, revision).let { result ->
                if (result.isSuccess) parseBookmarks(result.stdout, tracked = true) else emptyList()
            }

        /**
         * Load dialog data (remotes and tracked bookmarks per remote) from a repository. Call off EDT.
         * @param revision When provided, bookmarks are filtered to those on this revision or its ancestors.
         */
        fun loadDialogData(repo: JujutsuRepository, revision: Revision? = null): DialogData {
            val remotes = loadRemotes(repo)
            val trackedByRemote = remotes.associateWith { remote ->
                val scoped = loadTrackedBookmarks(repo, remote, revision)
                // A pending-deletion bookmark has no target, so `-r ::revision` drops it entirely —
                // it isn't "on" any revision. Without this, a deletion staged from the log context
                // menu (which always scopes to a revision) could never be selected for push
                // (jj-idea-ehki). Only queried when scoped, since the unscoped list is already this.
                val deletions = if (revision == null) {
                    emptyList()
                } else {
                    loadTrackedBookmarks(repo, remote, revision = null).filter { it.deleted }
                }
                mergeBookmarks(scoped, deletions)
            }
            val allBookmarks = repo.commandExecutor.bookmarkList(
                template = LOCAL_BOOKMARK_TEMPLATE,
                revision = revision
            ).let { result ->
                if (result.isSuccess) parseBookmarks(result.stdout, tracked = false) else emptyList()
            }
            return DialogData(
                remotes = remotes,
                trackedByRemote = trackedByRemote,
                allLocal = allBookmarks.filter { !it.deleted }
            )
        }

        /**
         * Load dialog data for multiple repositories. Call off EDT.
         * Results are returned in the same order as the input collection.
         */
        fun loadAllDialogData(repos: Collection<JujutsuRepository>): Map<JujutsuRepository, DialogData> =
            repos.associateWith { loadDialogData(it) }
    }
}
