package `in`.kkkev.jjidea.ui.editor.conflict

import com.intellij.diff.util.DiffGutterOperation
import com.intellij.openapi.Disposable
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.conflict.AcceptChoice
import `in`.kkkev.jjidea.jj.conflict.ConflictBlock
import `in`.kkkev.jjidea.jj.conflict.ConflictRegionScanner
import `in`.kkkev.jjidea.jj.conflict.ConflictSide
import `in`.kkkev.jjidea.jj.conflict.DiffLineKind
import `in`.kkkev.jjidea.jj.conflict.choicesFor
import `in`.kkkev.jjidea.jj.conflict.conflictBlockIndexAt
import `in`.kkkev.jjidea.jj.conflict.diffSectionLines
import `in`.kkkev.jjidea.jj.conflict.sideFor
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.ui.editor.debouncedDocumentScan
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryByAncestry
import java.awt.Font

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
 *
 * **Gated behind [PreviewFeature.CONFLICT_GUTTER]** (jj-idea-n6fz.1): this whole surface - scanner,
 * icons, side tints, hover - is withheld from a gradual release as one unit, so [editorCreated]
 * checks [PreviewEntitlement] before doing anything else observable (subscribing to
 * [in.kkkev.jjidea.jj.JujutsuStateModel.initialisedRepositories] included), leaving the
 * plugin-wide listener a strict no-op for every editor while the feature is off. The S1 banner
 * ([in.kkkev.jjidea.ui.editor.JujutsuConflictEditorNotificationProvider]) is a separate bead
 * (jj-idea-lkrt, already released) and stays ungated.
 */
class JujutsuConflictGutterInstaller : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (!PreviewEntitlement.getInstance().isEnabled(PreviewFeature.CONFLICT_GUTTER)) return
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
 * One editor's worth of gutter icons and side-background highlighting, from first scan through
 * every subsequent edit. [ConflictRegionScanner] keeps [scanner]'s block list up to date
 * synchronously in [DocumentListener.documentChanged] itself - cheap by construction (see that
 * class's doc), so this does *not* defer the model update itself, only the (already O(active
 * blocks in the file), so equally cheap) icon/highlighter reconciliation in [reconcile] -
 * debounced purely to avoid visibly dispose/recreate-flickering them on every keystroke while
 * typing inside a block.
 */
private class ConflictGutterController(private val editor: Editor) : Disposable {
    private val project = requireNotNull(editor.project) { "editor.project checked non-null by shouldInstall" }
    private val scanner = ConflictRegionScanner()
    private var operations: List<DiffGutterOperation> = emptyList()
    private var highlighters: List<RangeHighlighter> = emptyList()
    private var folds: List<CustomFoldRegion> = emptyList()
    private var foldedExceptBlock = -1
    private val alarm = Alarm(this)
    private val debounced = debouncedDocumentScan(alarm) { reconcile() }

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
        editor.caretModel.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    // Fast path: caret still in the same block (or still outside all) - no fold churn.
                    if (conflictBlockIndexAt(scanner.blocks, editor.caretModel.offset) != foldedExceptBlock) {
                        reconcileFolds()
                    }
                }
            },
            this
        )
        ConflictSideHover(editor) { scanner.blocks }.also { Disposer.register(this, it) }.install()
        reconcile()
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

    private fun reconcile() {
        operations.forEach { it.dispose() }
        operations = scanner.blocks.flatMap { block ->
            choicesFor(block).filter { it != AcceptChoice.BOTH }.map { choice ->
                DiffGutterOperation.Simple(
                    editor,
                    offsetFor(block, choice),
                    DiffGutterOperation.RendererBuilder {
                        ConflictBlockGutterIconRenderer(project, editor.document, block, choice)
                    }
                )
            }
        }

        highlighters.forEach { it.dispose() }
        highlighters = scanner.blocks.flatMap { block ->
            listOfNotNull(
                sideHighlighter(block.sideFor(AcceptChoice.SIDE1), DiffColors.DIFF_DELETED),
                sideHighlighter(block.sideFor(AcceptChoice.SIDE2), DiffColors.DIFF_INSERTED),
                sideHighlighter(block.sideFor(AcceptChoice.BASE), DiffColors.DIFF_MODIFIED)
            ) + diffPrefixHighlighters(block)
        }
        reconcileFolds()
    }

    /**
     * Replaces every marker-line run with a [ConflictMarkerDivider] fold (jj-idea-6ja9), except
     * in the block the caret is inside - a custom fold can't be expanded, so that block shows its
     * raw markers for hand-editing until the caret leaves (see the caret listener). O(marker runs).
     */
    private fun reconcileFolds() {
        val caretBlock = conflictBlockIndexAt(scanner.blocks, editor.caretModel.offset)
        foldedExceptBlock = caretBlock
        editor.foldingModel.runBatchFoldingOperation {
            folds.forEach { if (it.isValid) editor.foldingModel.removeFoldRegion(it) }
            folds = scanner.blocks.withIndex().filter { it.index != caretBlock }.flatMap { (_, block) ->
                block.markerRuns.mapNotNull { run ->
                    val key = run.next?.let { conflictSideKey(it) }
                    editor.foldingModel.addCustomLinesFolding(
                        run.startLine,
                        run.endLine,
                        ConflictMarkerDivider(editor, key, run.label)
                    )
                }
            }
        }
    }

    /**
     * jj-idea-8u0g: a DIFF-style `%%%%%%%` section's raw `-`/`+` prefixes are noise under the
     * side tint, so dim each prefix character and dim + strike through `-` (base-only) lines.
     * Display only - the document text, and so every accept action, is untouched.
     */
    private fun diffPrefixHighlighters(block: ConflictBlock): List<RangeHighlighter> {
        val side = listOf(block.side1, block.side2).firstOrNull { it.isDiffSection } ?: return emptyList()
        val start = side.contentStartOffset ?: return emptyList()
        val end = side.contentEndOffset ?: return emptyList()
        val dim = UIUtil.getInactiveTextColor()
        val prefix = TextAttributes(dim, null, null, null, Font.PLAIN)
        val removed = TextAttributes(dim, null, dim, EffectType.STRIKEOUT, Font.PLAIN)
        return diffSectionLines(editor.document.immutableCharSequence, start, end).flatMap { line ->
            when (line.kind) {
                DiffLineKind.CONTEXT -> emptyList()
                DiffLineKind.ADDED -> listOf(diffHighlighter(line.prefixOffset, line.prefixOffset + 1, prefix))
                DiffLineKind.REMOVED -> listOf(diffHighlighter(line.prefixOffset, line.lineEnd, removed))
            }
        }
    }

    private fun diffHighlighter(start: Int, end: Int, attributes: TextAttributes): RangeHighlighter =
        editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.SELECTION - 2,
            attributes,
            HighlighterTargetArea.EXACT_RANGE
        )

    /**
     * Anchor offset for [choice]'s gutter icon - that side's own first content line, falling
     * back to the block's own opening `<<<<<<<` line for the rare shape (`UNRECOGNISED`) that
     * has no per-side range at all.
     */
    private fun offsetFor(block: ConflictBlock, choice: AcceptChoice): Int = when (choice) {
        AcceptChoice.SIDE1 -> block.side1.contentStartOffset ?: block.startOffset
        AcceptChoice.SIDE2 -> block.side2.contentStartOffset ?: block.startOffset
        AcceptChoice.BASE -> block.base?.contentStartOffset ?: block.startOffset
        AcceptChoice.BOTH -> block.startOffset // unreachable - filtered out of reconcile()'s icon pass
    }

    private fun sideHighlighter(side: ConflictSide?, key: TextAttributesKey): RangeHighlighter? {
        val start = side?.contentStartOffset ?: return null
        val end = side.contentEndOffset ?: return null
        if (start == end) return null // an explicit but empty side/base - nothing to tint
        return editor.markupModel.addRangeHighlighter(
            key,
            start,
            end,
            HighlighterLayer.SELECTION - 2, // below ConflictSideHover's own hover-tint layer (SELECTION - 1)
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    override fun dispose() {
        operations.forEach { it.dispose() }
        operations = emptyList()
        highlighters.forEach { it.dispose() }
        highlighters = emptyList()
        editor.foldingModel.runBatchFoldingOperation {
            folds.forEach { if (it.isValid) editor.foldingModel.removeFoldRegion(it) }
        }
        folds = emptyList()
    }
}

private fun countNewlines(seq: CharSequence): Int {
    var count = 0
    for (c in seq) if (c == '\n') count++
    return count
}
