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
import `in`.kkkev.jjidea.ui.editor.debouncedDocumentScan
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

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
 * Stage 4 adds the actual accept actions to [ConflictBlockGutterIconRenderer]; this stage ships
 * the icon and its tooltip only.
 */
class JujutsuConflictGutterInstaller : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (!shouldInstall(editor)) return
        val controller = ConflictGutterController(editor)
        editor.putUserData(CONTROLLER_KEY, controller)
        controller.installInitial()
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.getUserData(CONTROLLER_KEY)?.let { Disposer.dispose(it) }
        editor.putUserData(CONTROLLER_KEY, null)
    }

    companion object {
        private val CONTROLLER_KEY: Key<ConflictGutterController> = Key.create("jjidea.conflictGutterController")

        /** Cheapest checks first - every one after the first touches something off the hot editor-open path. */
        fun shouldInstall(editor: Editor): Boolean {
            if (editor.editorKind != EditorKind.MAIN_EDITOR) return false
            val project = editor.project ?: return false
            if (project.isDisposed) return false
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
            return project.possibleJujutsuRepositoryFor(file) != null
        }
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
                DiffGutterOperation.RendererBuilder { ConflictBlockGutterIconRenderer(block) }
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
