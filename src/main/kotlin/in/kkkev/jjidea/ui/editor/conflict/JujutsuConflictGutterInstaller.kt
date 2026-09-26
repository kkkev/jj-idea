package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.diff.util.DiffGutterOperation
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import `in`.kkkev.jjidea.jj.conflict.ConflictRegionScanner
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.editor.debouncedDocumentScan
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryByAncestry

/**
 * Registers per-block gutter icons for jj conflict marker blocks in every eligible main editor
 * (jj-idea-82fo, stage 3/4) - the in-editor half of the epic's critical-path bead. A plugin-wide
 * `com.intellij.editorFactoryListener`, so [editorCreated] fires for *every* editor the IDE
 * opens (consoles, diff viewers, previews included) - [shouldInstall] gates that down to a
 * strict no-op everywhere else, cheapest checks first, mirroring
 * [in.kkkev.jjidea.diffedit.HunkArrowDiffExtension]'s own plugin-wide-EP caveat.
 *
 * **Deliberate divergence from [in.kkkev.jjidea.ui.editor.JujutsuConflictEditorNotificationProvider]
 * (S1's banner):** gutter regions key off marker *presence in the document text*, not
 * `ChangeListManager`'s `MERGED_WITH_CONFLICTS` status - that matches jj's model (the markers
 * *are* the record) and avoids status-timing flicker. The two surfaces can briefly disagree
 * (e.g. right after a manual marker edit, before the next VCS refresh) - that is by design.
 *
 * **Deliberate simplification vs. the design doc's original "one shared `DocumentMarkupModel`
 * highlighter set" plan**: [DiffGutterOperation] (reused from [in.kkkev.jjidea.diffedit.
 * HunkArrowDiffExtension]'s own precedent, rather than a hand-rolled
 * [com.intellij.openapi.editor.markup.RangeHighlighter] pair) adds its highlighter to the
 * *editor's own* markup model, not the document-shared one - so a split view installs one
 * independent [ConflictGutterController] per pane rather than sharing highlighters. Each pane
 * still gets [ConflictRegionScanner]'s cheap incremental-scan cost; only the number of conflict
 * blocks in a file (always small) sets the per-pane icon-reconciliation cost, so duplicating
 * that across a handful of split panes is not a scale concern. Unify onto one shared model
 * later if a cross-pane concern (e.g. shared accept-state) ever needs it.
 *
 * **[editorCreated] cannot just gate once and give up.** [shouldInstall]'s repository check
 * reads [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories]'s *cached* value - which,
 * unlike [in.kkkev.jjidea.ui.editor.JujutsuConflictEditorNotificationProvider]'s banner (an
 * `EditorNotificationProvider`, re-invoked by the platform on its own refresh triggers), this
 * one-shot `editorFactoryListener` callback never gets asked again for an editor that's already
 * open. An editor restored as part of the IDE's own layout can open before that state's
 * background load (kicked off by `JujutsuStartupActivity`) completes - `editorFactoryListener`
 * has no ordering guarantee relative to `ProjectActivity` the way VCS provider activation does
 * (see that state's own KDoc). A permanent gate at that moment would permanently miss the icon
 * even though the banner (asked again later) shows up fine - exactly the discrepancy this
 * class's [ConflictGutterWatcher] exists to close: it retries [shouldInstall] every time
 * [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories] finishes a load, for as long as
 * the editor stays open, until it succeeds once.
 *
 * **[shouldInstall] resolves the repository via [in.kkkev.jjidea.vcs.jujutsuRepositoryByAncestry],
 * not `possibleJujutsuRepositoryFor`** (the banner's own check) - found necessary in manual
 * testing: `possibleJujutsuRepositoryFor`'s `VcsUtil.getVcsRootFor` path returned null for a file
 * under a repo [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories] already listed as
 * initialised, with nothing ever correcting it for the rest of that session (a real gap in a
 * shared utility, but a wider-reaching fix than this one bead should make as a side effect - see
 * that function's own KDoc). [in.kkkev.jjidea.jj.JujutsuStateModel] itself never uses
 * `getVcsRootFor` for this exact reason.
 */
class JujutsuConflictGutterInstaller : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val project = editor.project ?: return
        if (project.isDisposed) return
        if (FileDocumentManager.getInstance().getFile(editor.document) == null) return

        val watcher = ConflictGutterWatcher(editor)
        editor.putUserData(WATCHER_KEY, watcher)
        watcher.start()
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.getUserData(WATCHER_KEY)?.let { Disposer.dispose(it) }
        editor.putUserData(WATCHER_KEY, null)
    }

    companion object {
        private val WATCHER_KEY: Key<ConflictGutterWatcher> = Key.create("jjidea.conflictGutterWatcher")

        /**
         * Whether [editor] should have gutter icons *right now*, given the repository state's
         * current cached value - cheapest checks first. Called both by [editorCreated] (via
         * [ConflictGutterWatcher]) and again every time that state reloads, since the answer can
         * flip from false to true after the checks above this class's KDoc explains.
         */
        fun shouldInstall(editor: Editor): Boolean {
            if (editor.editorKind != EditorKind.MAIN_EDITOR) return false
            val project = editor.project ?: return false
            if (project.isDisposed) return false
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
            return project.jujutsuRepositoryByAncestry(file) != null
        }
    }
}

/**
 * Retries [JujutsuConflictGutterInstaller.shouldInstall] every time
 * [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories] finishes a (re)load, until it
 * succeeds once and installs a [ConflictGutterController] - see the installer's own KDoc for
 * why a single check at `editorCreated` time isn't enough. [connectAndFireSync] both fires
 * immediately with whatever's cached right now (covers the common case: already loaded) and
 * guarantees a real background load happens if it hasn't (covers the race) - unlike plain
 * `connect`, which only replays an *already-loaded* value and would otherwise depend on some
 * unrelated caller invalidating the state on our behalf.
 */
private class ConflictGutterWatcher(private val editor: Editor) : Disposable {
    private var controller: ConflictGutterController? = null

    fun start() {
        val project = requireNotNull(editor.project) {
            "checked non-null by JujutsuConflictGutterInstaller.editorCreated"
        }
        project.stateModel.initialisedRepositories.connectAndFireSync(this) { tryInstall() }
    }

    private fun tryInstall() {
        if (controller != null) return
        if (!JujutsuConflictGutterInstaller.shouldInstall(editor)) return
        controller = ConflictGutterController(editor).also { it.installInitial() }
    }

    override fun dispose() {
        controller?.let { Disposer.dispose(it) }
        controller = null
    }
}

/**
 * One editor's worth of gutter icons, from first scan through every subsequent edit.
 * [ConflictRegionScanner] keeps [scanner]'s block list up to date synchronously in
 * [DocumentListener.documentChanged] itself - cheap by construction (see that class's doc), so
 * this does *not* defer the model update itself, only the (already O(active blocks in the
 * file), so equally cheap) icon reconciliation - debounced purely to avoid visibly
 * dispose/recreate-flickering the icons on every keystroke while typing inside a block.
 */
private class ConflictGutterController(private val editor: Editor) : Disposable {
    private val project = requireNotNull(editor.project) { "editor.project checked non-null by shouldInstall" }
    private val scanner = ConflictRegionScanner()
    private var operations: List<DiffGutterOperation> = emptyList()
    private val alarm = Alarm(this)
    private val debounced = debouncedDocumentScan(alarm) { reconcileIcons() }

    fun installInitial() {
        scanner.fullScan(editor.document.immutableCharSequence)
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    applyToScanner(event)
                    debounced.onDocumentChanged()
                }
            },
            this
        )
        reconcileIcons()
    }

    private fun applyToScanner(event: DocumentEvent) {
        scanner.applyEdit(
            text = event.document.immutableCharSequence,
            changeStart = event.offset,
            changeEndNew = event.offset + event.newLength,
            offsetDelta = event.newLength - event.oldLength,
            lineDelta = countNewlines(event.newFragment) - countNewlines(event.oldFragment)
        )
    }

    private fun reconcileIcons() {
        operations.forEach { it.dispose() }
        operations = scanner.blocks.map { block ->
            DiffGutterOperation.Simple(
                editor,
                block.startOffset,
                DiffGutterOperation.RendererBuilder { ConflictBlockGutterIconRenderer(project, editor.document, block) }
            )
        }
    }

    override fun dispose() {
        operations.forEach { it.dispose() }
        operations = emptyList()
    }
}

private fun countNewlines(seq: CharSequence): Int {
    var count = 0
    for (c in seq) if (c == '\n') count++
    return count
}
