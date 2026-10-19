package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Key
import `in`.kkkev.jjidea.ui.common.Hunk

/**
 * Installs one gutter checkbox per hunk onto the "after" editor of a [SimpleDiffViewer], for the
 * Split / Squash Into file preview (jj-idea-s5yx, GitHub #101). A ticked box means "this hunk
 * moves" — the wording lives in the preview's state label, not here.
 *
 * Registered plugin-wide (see `plugin.xml`), so like [HunkArrowDiffExtension] it fires for
 * **every** diff viewer and must be an immediate no-op unless the *request* carries
 * [HUNK_CHECKBOX_SESSION]. A request key (not a context key) because the preview renders through
 * a `DiffRequestPanel`, which hides its viewer and context from the caller.
 *
 * Built only on public `diff-api`/`diff-impl` surface; the hunks come from the session (our own
 * line diff), not from the viewer's changes, so hunk identity doesn't depend on the viewer's
 * whitespace policy.
 */
class HunkCheckboxDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val session = request.getUserData(HUNK_CHECKBOX_SESSION) ?: return
        if (viewer !is SimpleDiffViewer) return

        val operations = DiffGutterOperationSet()
        val install = { installCheckboxes(viewer, session, operations) }
        // A tick change only swaps the renderer on the existing highlighters: disposing and
        // recreating them under the mouse made the cursor flash to an I-beam after a click.
        session.addListener { operations.updateAll() }
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() = install()
        })
        install()
        session.attached(viewer)
    }

    private fun installCheckboxes(viewer: SimpleDiffViewer, session: HunkCheckboxSession, set: DiffGutterOperationSet) {
        val editor = viewer.getEditor(Side.RIGHT)
        set.replaceWith(
            session.hunks.map { hunk ->
                clickableGutterOperation(
                    editor,
                    hunk.afterStart,
                    {
                        if (hunk.index in session.checked) {
                            AllIcons.Diff.GutterCheckBoxSelected
                        } else {
                            AllIcons.Diff.GutterCheckBox
                        }
                    },
                    session.tooltip
                ) { session.toggle(hunk.index) }
            }
        )
    }

    companion object {
        /** Presence of this key in a [DiffRequest]'s user data opts its viewer into hunk checkboxes. */
        val HUNK_CHECKBOX_SESSION: Key<HunkCheckboxSession> = Key.create("jjidea.hunkCheckboxSession")
    }
}

/**
 * State of one file's hunk checkboxes: which of [hunks] are ticked. Owned by the preview
 * controller, which updates it in place with [setChecked] when the selection changes elsewhere
 * (a tree tick, Pick Hunks…) so the viewer needn't be rebuilt. A click calls [toggle], which
 * updates the state, repaints the icons and reports the new set via [onChanged].
 */
class HunkCheckboxSession(
    val hunks: List<Hunk>,
    checked: Set<Int>,
    val tooltip: String,
    private val onChanged: (Set<Int>) -> Unit
) {
    var checked: Set<Int> = checked
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** The viewer built for this session's request, if any (test seam). */
    internal var attachedViewer: SimpleDiffViewer? = null
        private set

    internal fun attached(viewer: SimpleDiffViewer) {
        attachedViewer = viewer
    }

    internal fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Replace the ticked set without notifying [onChanged] (the caller already knows). */
    fun setChecked(newChecked: Set<Int>) {
        checked = newChecked
        listeners.forEach { it() }
    }

    fun toggle(index: Int) {
        val next = if (index in checked) checked - index else checked + index
        setChecked(next)
        onChanged(next)
    }
}
