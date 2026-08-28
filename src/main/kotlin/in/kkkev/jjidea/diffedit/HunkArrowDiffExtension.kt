package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleThreesideDiffChange
import com.intellij.diff.tools.simple.SimpleThreesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.DiffGutterOperation
import com.intellij.diff.util.DiffGutterRenderer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.diff.util.ThreeSide
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Installs per-hunk gutter arrows onto a [SimpleThreesideDiffViewer], for [HunkPickerDialog]'s
 * Before / eventual-Parent (live) / Child picker.
 *
 * Registered as a plugin-wide `diff.DiffExtension` (see `plugin.xml`) — fires for **every** diff
 * viewer the platform creates, so [onViewerCreated] must be an immediate no-op unless
 * [HUNK_PICKER_SESSION] is present in [DiffContext]. It is only ever set by [HunkPickerDialog].
 *
 * All three panes are marked `FORCE_READ_ONLY` (see [HunkPickerDialog]), which blocks direct
 * typing at the editor level only — [SimpleThreesideDiffViewer.replaceChange] still writes the
 * middle (`BASE`) document directly, bypassing the platform's own built-in arrow installer (which
 * gates on `viewer.isEditable(...)` and would stay silent once every pane is read-only). This is
 * a deliberate design choice: interaction is arrow-clicks only, no manual editing of the live
 * "eventual parent" pane.
 *
 * Built entirely on public, unannotated `diff-api`/`diff-impl` surface: [DiffExtension],
 * [DiffViewerListener], [SimpleThreesideDiffViewer.getChanges], [SimpleThreesideDiffViewer.
 * replaceChange], [DiffGutterOperation]. See jj-idea-xuob for why internal APIs (the merge tool,
 * or the platform's own accept-arrow wiring) are avoided.
 */
class HunkArrowDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val session = context.getUserData(HUNK_PICKER_SESSION) ?: return
        if (viewer !is SimpleThreesideDiffViewer) return

        session.viewer = viewer
        val installer = ArrowInstaller(viewer, session.project, session.labels)
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() = installer.install()
        })
    }

    companion object {
        /** Presence of this key in a [DiffContext] opts a viewer into hunk arrows. */
        val HUNK_PICKER_SESSION: Key<HunkArrowSession> = Key.create("jjidea.hunkArrowSession")
    }
}

/**
 * One [HunkPickerDialog] session. Holds no selection state of its own — the live middle
 * document *is* the state — only a back-reference to the constructed viewer, for the test seam
 * (see [HunkPickerDialog.viewerForTest]).
 */
class HunkArrowSession(val project: Project, val labels: HunkPickerLabels) {
    internal var viewer: SimpleThreesideDiffViewer? = null
}

/**
 * Installs one gutter arrow per hunk-divider on [viewer], reflecting and toggling via
 * [SimpleThreesideDiffViewer.replaceChange]. Re-run on every `onAfterRediff` — a click schedules
 * a rediff, which re-fires this, so arrows always reflect the current state with no separate
 * bookkeeping.
 */
private class ArrowInstaller(
    private val viewer: SimpleThreesideDiffViewer,
    private val project: Project,
    private val labels: HunkPickerLabels
) {
    private var installedOperations: List<DiffGutterOperation> = emptyList()

    fun install() {
        installedOperations.forEach { it.dispose() }

        val operations = mutableListOf<DiffGutterOperation>()
        for (change in viewer.getChanges()) {
            if (change.isChange(Side.LEFT)) {
                operations += installArrow(
                    editor = ThreeSide.BASE,
                    line = change.getStartLine(ThreeSide.BASE),
                    icon = AllIcons.Diff.ArrowRight,
                    tooltip = labels.middleArrowTooltip,
                    sourceSide = ThreeSide.LEFT,
                    change = change
                )
            }
            if (change.isChange(Side.RIGHT)) {
                operations += installArrow(
                    editor = ThreeSide.RIGHT,
                    line = change.getStartLine(ThreeSide.RIGHT),
                    icon = AllIcons.Diff.Arrow,
                    tooltip = labels.rightArrowTooltip,
                    sourceSide = ThreeSide.RIGHT,
                    change = change
                )
            }
        }
        installedOperations = operations
    }

    private fun installArrow(
        editor: ThreeSide,
        line: Int,
        icon: javax.swing.Icon,
        tooltip: String,
        sourceSide: ThreeSide,
        change: SimpleThreesideDiffChange
    ): DiffGutterOperation {
        val editorEx = viewer.getEditor(editor)
        val offset = DiffGutterOperation.lineToOffset(editorEx, line)
        return DiffGutterOperation.Simple(
            editorEx,
            offset,
            DiffGutterOperation.RendererBuilder {
                ArrowGutterRenderer(icon, tooltip) { onArrowClicked(change, sourceSide) }
            }
        )
    }

    private fun onArrowClicked(change: SimpleThreesideDiffChange, sourceSide: ThreeSide) {
        val document = viewer.getEditor(ThreeSide.BASE).document
        DiffUtil.executeWriteCommand(document, project, JujutsuBundle.message("dialog.hunks.command")) {
            viewer.replaceChange(change, sourceSide, ThreeSide.BASE)
        }
    }
}

private class ArrowGutterRenderer(
    icon: javax.swing.Icon,
    tooltip: String,
    private val onClicked: () -> Unit
) : DiffGutterRenderer(icon, tooltip) {
    override fun handleMouseClick() = onClicked()
}
