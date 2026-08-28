package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleThreesideDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.ThreeSide
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import `in`.kkkev.jjidea.JujutsuBundle
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Lets the user interactively pick which hunks of a file's change move from [HunkPickerLabels]'s
 * left (fixed) side to its right (fixed) side, via a native 3-pane diff — Left | live middle |
 * Right — with a directional arrow at each hunk's divider instead of the earlier 3-way *merge*
 * widget (`HunkDiffPicker`, replaced by jj-idea-xuob) or the intermediate 2-pane checkbox picker.
 *
 * The mechanics are polarity-agnostic — used by both Split ("first commit" content moving to a
 * new child) and Squash ("destination" content receiving hunks from the source) — see
 * [HunkPickerLabels] for how the two calling conventions differ only in wording, never in
 * mechanism.
 *
 * Clicking the right arrow at a Left|middle divider moves that hunk to the right side (pulls
 * Left's content into the live middle pane); clicking the left arrow at a middle|Right divider
 * moves it back (pulls Right's content back in) — reversible, either direction, any number of
 * times, per hunk. No "resolved" concept, so no merge-conflict confirmation dialogs.
 *
 * The middle pane is genuinely editable (required for [SimpleThreesideDiffViewer.replaceChange]
 * to have a document to write into) but marked `FORCE_READ_ONLY` like the other two, so direct
 * typing is blocked at the editor level — see [HunkArrowDiffExtension] for why this still allows
 * arrow clicks to work (`FORCE_READ_ONLY` only ever calls `editor.setViewer(true)`, never
 * `Document.setReadOnly`).
 */
object HunkPicker {
    /**
     * Open the picker and return the resulting middle-pane content, or null if cancelled
     * (caller keeps prior state).
     *
     * Must be called on the EDT (modal dialog).
     *
     * @param project        The current project.
     * @param fileName       Display name of the file (e.g. "Auth.kt") — used for the dialog title.
     * @param fileType       File type for syntax highlighting. Use [fileTypeFor] to resolve.
     * @param baseContent    The fixed left pane's content — the middle pane's "nothing moved"
     *                       default.
     * @param afterContent   The fixed right pane's content — the middle pane's "everything moved"
     *                       default.
     * @param initialContent Starting content for the live middle pane: pass any existing partial
     *                       override to resume it, or a tick-derived default (== [baseContent] or
     *                       [afterContent]) otherwise. Resuming needs no reconstruction — the
     *                       string itself is the exact resume state.
     * @param labels         Pane titles and arrow tooltips — see [HunkPickerLabels.forSplit] /
     *                       [HunkPickerLabels.forSquash].
     */
    fun pickRemainderContent(
        project: Project,
        fileName: String,
        fileType: FileType,
        baseContent: String,
        afterContent: String,
        initialContent: String,
        labels: HunkPickerLabels
    ): String? {
        val dialog = HunkPickerDialog(
            project,
            fileName,
            fileType,
            baseContent,
            initialContent,
            afterContent,
            labels
        )
        val accepted = dialogRunnerForTest?.invoke(dialog) ?: dialog.showAndGet()
        return if (accepted) dialog.resultContent() else null
    }

    /** Resolve the [FileType] to use for syntax highlighting from a file name. */
    fun fileTypeFor(fileName: String): FileType =
        FileTypeManager.getInstance().getFileTypeByFileName(fileName)
            .takeIf { it != UnknownFileType.INSTANCE }
            ?: PlainTextFileType.INSTANCE

    /**
     * Test seam: replaces `dialog.showAndGet()` with a caller-supplied function, so platform
     * tests can drive [HunkPickerDialog.viewerForTest] and call [HunkPickerDialog.
     * performOKForTest] / [HunkPickerDialog.performCancelForTest] instead of showing a real modal
     * window.
     */
    @org.jetbrains.annotations.TestOnly
    internal var dialogRunnerForTest: ((HunkPickerDialog) -> Boolean)? = null
}

/**
 * Internal (not private) so platform tests can construct and drive it without showing a real
 * modal — see [performOKForTest]/[performCancelForTest]/[viewerForTest]/[resultContent].
 *
 * The diff request (and the editors it causes the platform to create) is built lazily in
 * [createCenterPanel] rather than eagerly in `init`, matching [in.kkkev.jjidea.ui.split.
 * SplitDialog]'s own pattern of only building real diff content in response to a UI event.
 */
internal class HunkPickerDialog(
    private val project: Project,
    private val fileName: String,
    private val fileType: FileType,
    private val baseContent: String,
    private val initialContent: String,
    private val afterContent: String,
    private val labels: HunkPickerLabels
) : DialogWrapper(project) {
    private val session = HunkArrowSession(project, labels)

    @org.jetbrains.annotations.TestOnly
    internal fun performOKForTest() = doOKAction()

    @org.jetbrains.annotations.TestOnly
    internal fun performCancelForTest() = doCancelAction()

    /** The constructed 3-way viewer, once [createCenterPanel] has run (always true after `init()`). */
    @org.jetbrains.annotations.TestOnly
    internal fun viewerForTest(): SimpleThreesideDiffViewer? = session.viewer

    /** The live middle ("eventual parent") pane's current text. */
    internal fun resultContent(): String {
        val viewer = checkNotNull(session.viewer) { "createCenterPanel must run before resultContent is read" }
        return viewer.getEditor(ThreeSide.BASE).document.text
    }

    init {
        title = JujutsuBundle.message("dialog.hunks.title", fileName)
        setOKButtonText(JujutsuBundle.message("dialog.hunks.action.apply"))
        setCancelButtonText(JujutsuBundle.message("dialog.hunks.action.cancel"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)

        // Building the request synchronously constructs real editors — see WriteIntentReadAction
        // note in the (now-removed) 2-pane picker's history: safe on a real EDT dispatch (which
        // holds an implicit write-intent lock), but @RunInEdt test harnesses don't provide that
        // automatically without this explicit wrapper.
        WriteIntentReadAction.run {
            val factory = DiffContentFactory.getInstance()
            val leftContent = factory.create(project, baseContent, fileType).apply {
                putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            }
            val middleContent = factory.createEditable(project, initialContent, fileType).apply {
                putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            }
            val rightContent = factory.create(project, afterContent, fileType).apply {
                putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            }

            val request = SimpleDiffRequest(
                JujutsuBundle.message("dialog.hunks.title", fileName),
                leftContent,
                middleContent,
                rightContent,
                labels.leftTitle,
                labels.middleTitle,
                labels.rightTitle
            )
            request.putUserData(
                DiffUserDataKeys.THREESIDE_DIFF_COLORS_MODE,
                DiffUserDataKeys.ThreeSideDiffColors.LEFT_TO_RIGHT
            )

            diffPanel.putContextHints(HunkArrowDiffExtension.HUNK_PICKER_SESSION, session)
            diffPanel.setRequest(request)
        }

        diffPanel.component.preferredSize = Dimension(1200, 600)
        return diffPanel.component
    }
}
