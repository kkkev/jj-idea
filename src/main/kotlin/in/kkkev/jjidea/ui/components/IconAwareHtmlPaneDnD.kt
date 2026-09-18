package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.preview.PreviewEntitlement
import `in`.kkkev.jjidea.preview.PreviewFeature
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.chipDragImage
import `in`.kkkev.jjidea.ui.log.BookmarkClick
import `in`.kkkev.jjidea.ui.log.LogClickTarget
import `in`.kkkev.jjidea.ui.log.TagClick
import java.awt.Point

/**
 * Makes [this] pane a [DragPayload.BookmarkRef]/[DragPayload.TagRef] drag source (jj-idea-4ji7,
 * batch 4) for a bookmark/tag chip rendered inside it - the commit details panel's metadata pane.
 * Source-only (`disableAsTarget()`): this pane is never a drop target itself, mirroring
 * [in.kkkev.jjidea.ui.common.installFilesDragSource]'s shape for the changes tree.
 *
 * The operation logic needs no new code beyond this hit-test - [DragPayload.BookmarkRef]/
 * [DragPayload.TagRef] and every gesture they can resolve to (jj-idea-ibth, -vdwh) already exist,
 * exactly as that bead's own description anticipated. What actually blocked this bead was never a
 * `TransferHandler` to suppress - `JBHtmlPane` sets `isEditable = false` and never enables
 * `dragEnabled` (which defaults to `false` on a plain [javax.swing.text.JTextComponent]), so there
 * is no export path to fight. The real interference is [IconAwareHtmlPane]'s inherited caret
 * drag-select, suppressed separately in [IconAwareHtmlPane.processMouseMotionEvent] - see its doc.
 *
 * [entries] supplies the currently-displayed commits to resolve a chip's `jjref://` href against
 * ([LogClickTarget.resolve] needs one to know which entry's bookmark/tag a name matched) - the
 * same list [in.kkkev.jjidea.ui.log.JujutsuCommitDetailsPanel]'s own right-click handler already
 * resolves against.
 *
 * Guarded behind [PreviewFeature.DRAG_AND_DROP] (jj-idea-vpvz), exactly like the other two install
 * sites - no `DnDSupport` is registered at all unless the feature is enabled.
 */
fun IconAwareHtmlPane.installRefDragSource(parent: Disposable, project: Project, entries: () -> List<LogEntry>) {
    if (!PreviewEntitlement.getInstance().isEnabled(PreviewFeature.DRAG_AND_DROP)) return

    DnDSupport.createBuilder(this)
        .disableAsTarget()
        .setBeanProvider { info -> refDragPayload(info.point, project, entries())?.let { DnDDragStartBean(it) } }
        .setImageProvider { info ->
            refDragPayload(info.point, project, entries())?.let { chipDragImage(foreground, background, font, it) }
        }
        .setDisposableParent(parent)
        .install()
}

/**
 * The [DragPayload.BookmarkRef]/[DragPayload.TagRef] a drag starting at [point] would carry, or
 * `null` if [point] isn't over a chip ([IconAwareHtmlPane.refUriAt]), or the chip's href doesn't
 * resolve against [entries] (e.g. a stale bean-provider call after the displayed commits changed
 * mid-drag). Reuses [LogClickTarget.resolve] - the same resolution this pane's own right-click
 * handler already does for the same href - rather than a second `jjref://` parser.
 *
 * `internal` (not `private`) so a platform test can exercise it directly.
 */
internal fun IconAwareHtmlPane.refDragPayload(point: Point, project: Project, entries: List<LogEntry>): DragPayload? =
    when (val target = refUriAt(point)?.let { LogClickTarget.resolve(it, project, entries) }) {
        is BookmarkClick -> DragPayload.BookmarkRef(target.repo, target.entry.id, target.bookmark)
        is TagClick -> DragPayload.TagRef(target.repo, target.entry.id, target.tag)
        else -> null
    }
