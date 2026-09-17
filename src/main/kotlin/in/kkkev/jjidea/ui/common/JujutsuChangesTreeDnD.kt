package `in`.kkkev.jjidea.ui.common

import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesTreeDnDSupport
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.ui.dnd.DragPayload

/**
 * Makes [this] changes tree a [DragPayload.Files] drag source (jj-idea-yvry, -b2oi) - the
 * changes-tree half of the payload/target model
 * (`docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 1): the log table already
 * hit-tests a `Files` payload to a `CommitRow` (squash) or `Gap` (split) target with no change
 * needed (`JujutsuLogTableDnD.dropTargetAt`), because the design deliberately kept payload
 * *sources* and drop *targets* independent of which surface they live on.
 *
 * Source-only: `disableAsTarget()` because this tree is never a drop target itself (only a
 * [in.kkkev.jjidea.ui.log.JujutsuLogTable] row is), and [DnDSupport]'s builder otherwise defaults
 * a component to *both* source and target.
 *
 * [ownerFor] resolves the [LogEntry] the dragged [Change]s belong to today - needed by
 * [in.kkkev.jjidea.ui.dnd.DragGuards.filesRejectionReason]'s "own change" guard - and returning
 * `null` (an ambiguous or cross-repo selection, e.g. a multi-commit details-panel selection with
 * no single owner) means no drag starts at all, mirroring how [dragPayloadAt] in the log table
 * returns `null` for an empty selection.
 *
 * Guarded behind [PreviewFeature.DRAG_AND_DROP] at install, exactly like
 * [in.kkkev.jjidea.ui.log.installDragAndDrop] - no `DnDSupport` is registered at all unless the
 * feature is enabled, so a disabled preview feature costs nothing at drag time.
 */
internal fun JujutsuChangesTree.installFilesDragSource(parent: Disposable, ownerFor: (List<Change>) -> LogEntry?) {
    if (!PreviewEntitlement.getInstance().isEnabled(PreviewFeature.DRAG_AND_DROP)) return

    DnDSupport.createBuilder(this)
        .disableAsTarget()
        .setBeanProvider { _ -> filesDragPayload(ownerFor)?.let { payload -> DnDDragStartBean(payload) } }
        .setImageProvider { _ -> filesDragImage() }
        .setDisposableParent(parent)
        .install()
}

/**
 * The [DragPayload.Files] a drag starting anywhere on [this] tree's current selection would
 * carry, or `null` if there is nothing to drag - an empty selection, or [ownerFor] can't name a
 * single owning change for it (e.g. a multi-commit details-panel selection, or a working-copy
 * selection spanning more than one repository).
 *
 * `internal` (not `private`) so a platform test can exercise it directly without driving a real
 * `DnDEvent`, mirroring [in.kkkev.jjidea.ui.log.dragPayloadAt].
 */
internal fun JujutsuChangesTree.filesDragPayload(ownerFor: (List<Change>) -> LogEntry?): DragPayload.Files? {
    val changes = selectedChanges.toList()
    if (changes.isEmpty()) return null
    val owner = ownerFor(changes) ?: return null
    return DragPayload.Files(owner, changes)
}

/**
 * The same "N files" chip image `ChangesTreeDnDSupport` gives a file drag out of the Project view
 * (`ChangesTreeDnDSupport.createDraggedImage`) - reused directly via its public statics rather
 * than hand-rolled, since [DragPayload.Files] has no natural single-line label the way a
 * [DragPayload.Commit]/[DragPayload.BookmarkRef]/[DragPayload.TagRef] do
 * ([in.kkkev.jjidea.ui.log.dragImage] deliberately returns `null` for `Files` for the same
 * reason).
 */
private fun JujutsuChangesTree.filesDragImage() = ChangesTreeDnDSupport.createDragImage(
    this,
    VcsBundle.message("vcs.dnd.image.text.n.files", ChangesTreeDnDSupport.getSelectionCount(this))
)
