package `in`.kkkev.jjidea.ui.common

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkCheckboxDiffExtension
import `in`.kkkev.jjidea.diffedit.HunkCheckboxSession
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import javax.swing.JButton

/**
 * Both sides of a file's own change, plus the file type for syntax highlighting. Shared shape
 * for [in.kkkev.jjidea.ui.split.SplitDialog] (`before`/`after` = the revision's own parent/self)
 * and [in.kkkev.jjidea.ui.squash.SquashIntoDialog] (`before`/`after` = the source's own
 * parent/self) — both dialogs' previews are diffs of one commit's own change, never the true
 * destination.
 */
data class FileContents(val before: String, val after: String, val fileType: FileType)

/**
 * Shared per-file diff preview cache and renderer for [in.kkkev.jjidea.ui.split.SplitDialog] and
 * [in.kkkev.jjidea.ui.squash.SquashIntoDialog]. Owns the mechanical parts common to both — the
 * content cache, lazy off-EDT loading with a generation guard, `FORCE_READ_ONLY` diff-request
 * construction, the "Pick Hunks…" button's enabled state, and the inline per-hunk gutter
 * checkboxes (jj-idea-s5yx) — while each dialog keeps the parts that genuinely differ in polarity:
 * which content a ticked/unticked file *means* ([resolveContent]) and what happens when a hunk is
 * toggled ([onHunksChanged]; each dialog still owns its own override map and tick/untick logic —
 * see [in.kkkev.jjidea.ui.split.SplitDialog.applyPickedContent] / the Squash equivalent — since
 * Split's "ticked ⇒ null content" and Squash's "ticked ⇒ full content" are opposite defaults, not
 * worth forcing through one shared branch).
 *
 * The preview always diffs the file's own change, before → after; each hunk's checkbox means "this
 * hunk moves" (to the new commit / into the destination). The resolved content only decides which
 * boxes are ticked, so toggling updates the icons in place with no viewer rebuild.
 *
 * @param loadContents   Off-EDT loader for a file's before/after content and file type.
 * @param resolveContent The content that a ticked/unticked/partial file *means* for [FilePath],
 *   given its tick state and loaded [FileContents] — e.g. Split's `firstCommitOverrides[fp] ?:
 *   computePreviewLeftContent(...)`, Squash's equivalent with `computePreviewAfterContent`. Also
 *   feeds the hunk picker's initial state.
 * @param isIncluded     Whether [FilePath] is currently ticked.
 * @param movesToLabel   Where ticked hunks go (e.g. "new commit", "Destination"), for titles/state.
 * @param invertApplied  True when [resolveContent] holds the *unmoved* side (Split's default "stays"
 *   commit), so a hunk moves exactly when it is **not** applied in that content; false when it
 *   holds the moved side (Squash's destination, Split `-B`'s new commit).
 * @param onHunksChanged Called with the new [resolveContent]-polarity content after a checkbox click.
 */
class HunkPickPreviewController(
    private val project: Project,
    private val disposable: Disposable,
    private val loadContents: (Change) -> FileContents?,
    private val resolveContent: (fp: FilePath, included: Boolean, contents: FileContents) -> String,
    private val isIncluded: (FilePath) -> Boolean,
    private val movesToLabel: () -> String,
    private val invertApplied: Boolean,
    private val onHunksChanged: (fp: FilePath, content: String, contents: FileContents) -> Unit,
    private val differ: LineDiffer = platformLineDiffer
) {
    val preview: FileDiffPreviewPanel = FileDiffPreviewPanel(project, disposable)

    /** Disabled by default; the host dialog wires its own click listener and visibility. */
    val pickHunksButton = JButton(JujutsuBundle.message("dialog.hunks.pickButton")).apply {
        isEnabled = false
    }

    private val diffContentFactory = DiffContentFactory.getInstance()
    private class Entry(val contents: FileContents, val toggles: HunkToggles?)

    private val cache: MutableMap<FilePath, Entry> = LinkedHashMap()

    private var activeSession: HunkCheckboxSession? = null
    private var activeEntry: Entry? = null

    /** The file currently shown in the preview, or null if nothing is selected. */
    var currentFile: FilePath? = null
        private set

    private var loadGeneration = 0

    /** Cached before/after content for [fp], if it's been loaded (e.g. for OK-time backfill). */
    fun cachedContents(fp: FilePath): FileContents? = cache[fp]?.contents

    /** Show the diff preview for [change], loading its content lazily. */
    fun showFor(change: Change) {
        val fp = change.filePath
        currentFile = fp

        val cached = cache[fp]
        if (cached != null) {
            render(fp, cached)
            return
        }

        showLoading(fp)

        val gen = ++loadGeneration
        runInBackground(ModalityState.any()) {
            val contents = loadContents(change)
            val entry = contents?.let { Entry(it, HunkToggles.of(it.before, it.after, differ)) }
            runLater {
                if (Disposer.isDisposed(disposable) || gen != loadGeneration || currentFile != fp) return@runLater
                if (entry != null) {
                    cache[fp] = entry
                    render(fp, entry)
                } else {
                    showLoading(fp)
                }
            }
        }
    }

    private fun showLoading(fp: FilePath) {
        activeSession = null
        activeEntry = null
        preview.show(fp.name)
        preview.setStateText(null)
        pickHunksButton.isEnabled = false
    }

    /** Re-render the preview for [fp] from cached content, if any (e.g. after a tick change). */
    fun refresh(fp: FilePath) {
        cache[fp]?.let { render(fp, it) }
    }

    /**
     * Clear the cache and current selection and show the placeholder. Used when the underlying
     * file list is about to be replaced wholesale (e.g. Squash's pick-sources mode changing which
     * source is selected).
     */
    fun reset() {
        cache.clear()
        currentFile = null
        loadGeneration++
        activeSession = null
        activeEntry = null
        preview.showPlaceholder()
        preview.setStateText(null)
    }

    private fun render(fp: FilePath, entry: Entry) {
        val contents = entry.contents
        val toggles = entry.toggles
        val content = resolveContent(fp, isIncluded(fp), contents)
        val checked = toggles?.let { checkedFor(it, content) }
        preview.setStateText(stateText(toggles, checked))
        pickHunksButton.isEnabled = contents.before != contents.after

        val session = activeSession
        if (session != null && activeEntry === entry && checked != null) {
            // Same file, same viewer: just move the ticks — keeps scroll position and caret.
            session.setChecked(checked)
            return
        }

        val newSession = if (toggles != null && checked != null) {
            HunkCheckboxSession(
                hunks = toggles.hunks,
                checked = checked,
                tooltip = JujutsuBundle.message("dialog.preview.hunks.tooltip", movesToLabel())
            ) { newChecked ->
                val applied = if (invertApplied) allIndices(toggles) - newChecked else newChecked
                onHunksChanged(fp, toggles.compose(applied), contents)
                refresh(fp)
            }
        } else {
            null
        }
        activeSession = newSession
        activeEntry = entry

        val request = SimpleDiffRequest(
            fp.name,
            makeReadOnlyContent(contents.before, contents.fileType),
            makeReadOnlyContent(contents.after, contents.fileType),
            JujutsuBundle.message("dialog.preview.pane.before"),
            JujutsuBundle.message("dialog.preview.pane.after", movesToLabel())
        )
        if (newSession != null) request.putUserData(HunkCheckboxDiffExtension.HUNK_CHECKBOX_SESSION, newSession)
        preview.show(fp.name, request)
    }

    @org.jetbrains.annotations.TestOnly
    internal fun sessionForTest(): HunkCheckboxSession? = activeSession

    private fun allIndices(toggles: HunkToggles): Set<Int> = toggles.hunks.mapTo(LinkedHashSet()) { it.index }

    /** Ticked (= moving) hunk indices for [content], or null when it isn't a whole-hunk subset. */
    private fun checkedFor(toggles: HunkToggles, content: String): Set<Int>? {
        val applied = toggles.appliedIn(content) ?: return null
        return if (invertApplied) allIndices(toggles) - applied else applied
    }

    private fun stateText(toggles: HunkToggles?, checked: Set<Int>?): String? {
        toggles ?: return null
        val label = movesToLabel()
        return when {
            checked == null -> JujutsuBundle.message("dialog.preview.state.custom", label)
            checked.isEmpty() -> JujutsuBundle.message("dialog.preview.state.none", label)
            checked.size == toggles.hunks.size -> JujutsuBundle.message("dialog.preview.state.all", label)
            else -> JujutsuBundle.message("dialog.preview.state.partial", checked.size, toggles.hunks.size, label)
        }
    }

    private fun makeReadOnlyContent(text: String, fileType: FileType): DiffContent =
        diffContentFactory.create(project, text, fileType).apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }
}
