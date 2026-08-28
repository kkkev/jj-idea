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
 * construction, and the "Pick Hunks…" button's enabled state — while each dialog keeps the parts
 * that genuinely differ in polarity: which content a ticked/unticked file *means*
 * ([resolveContent]), and what happens when the hunk picker returns a result (each dialog still
 * owns its own override map and tick/untick logic — see [in.kkkev.jjidea.ui.split.SplitDialog.
 * applyPickedContent] / the Squash equivalent — since Split's "ticked ⇒ null content" and Squash's
 * "ticked ⇒ full content" are opposite defaults, not worth forcing through one shared branch).
 *
 * @param loadContents   Off-EDT loader for a file's before/after content and file type.
 * @param resolveContent The content to preview for [FilePath] given its tick state and loaded
 *   [FileContents] — e.g. Split's `firstCommitOverrides[fp] ?: computePreviewLeftContent(...)`,
 *   Squash's equivalent with `computePreviewAfterContent`.
 * @param previewTitles  Diff-pane titles for the preview, given the resolved content — e.g.
 *   Split's `describeSplitState`, Squash's `describeSquashState`.
 * @param isIncluded     Whether [FilePath] is currently ticked.
 */
class HunkPickPreviewController(
    private val project: Project,
    private val disposable: Disposable,
    private val loadContents: (Change) -> FileContents?,
    private val resolveContent: (fp: FilePath, included: Boolean, contents: FileContents) -> String,
    private val previewTitles: (content: String, contents: FileContents) -> Pair<String, String>,
    private val isIncluded: (FilePath) -> Boolean
) {
    val preview: FileDiffPreviewPanel = FileDiffPreviewPanel(project, disposable)

    /** Disabled by default; the host dialog wires its own click listener and visibility. */
    val pickHunksButton = JButton(JujutsuBundle.message("dialog.hunks.pickButton")).apply {
        isEnabled = false
    }

    private val diffContentFactory = DiffContentFactory.getInstance()
    private val cache: MutableMap<FilePath, FileContents> = LinkedHashMap()

    /** The file currently shown in the preview, or null if nothing is selected. */
    var currentFile: FilePath? = null
        private set

    private var loadGeneration = 0

    /** Cached before/after content for [fp], if it's been loaded (e.g. for OK-time backfill). */
    fun cachedContents(fp: FilePath): FileContents? = cache[fp]

    /** Show the diff preview for [change], loading its content lazily. */
    fun showFor(change: Change) {
        val fp = change.filePath
        currentFile = fp

        val cached = cache[fp]
        if (cached != null) {
            render(fp, cached)
            return
        }

        preview.show(fp.name)
        pickHunksButton.isEnabled = false

        val gen = ++loadGeneration
        runInBackground(ModalityState.any()) {
            val contents = loadContents(change)
            runLater {
                if (Disposer.isDisposed(disposable) || gen != loadGeneration || currentFile != fp) return@runLater
                if (contents != null) {
                    cache[fp] = contents
                    render(fp, contents)
                } else {
                    preview.show(fp.name)
                    pickHunksButton.isEnabled = false
                }
            }
        }
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
        preview.showPlaceholder()
    }

    private fun render(fp: FilePath, contents: FileContents) {
        val content = resolveContent(fp, isIncluded(fp), contents)
        val (leftTitle, rightTitle) = previewTitles(content, contents)

        val request = SimpleDiffRequest(
            fp.name,
            makeReadOnlyContent(contents.before, contents.fileType),
            makeReadOnlyContent(content, contents.fileType),
            leftTitle,
            rightTitle
        )
        preview.show(fp.name, request)

        pickHunksButton.isEnabled = contents.before != contents.after
    }

    private fun makeReadOnlyContent(text: String, fileType: FileType): DiffContent =
        diffContentFactory.create(project, text, fileType).apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }
}
