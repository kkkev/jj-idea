package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project

/**
 * The seam where a resolved [DropOperation] would actually be carried out - `jj rebase`,
 * `jj bookmark set`, opening a pre-filled dialog, and so on, per
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 3.
 *
 * jj-idea-6jvh (this bead) intentionally ships no performer: [forLogTable] returns `null`, which
 * makes [in.kkkev.jjidea.ui.log.installDragAndDrop]'s target checker reject every drop with no
 * indicator ever painted, so nothing looks droppable when nothing actually is. Most operations are
 * additionally gated on undo landing first (jj-idea-v9zp blocks every immediate-apply gesture bead
 * per the design doc's dependency table) - a real performer belongs to each gesture bead
 * (jj-idea-8fxs, -ibth, -yvry, ...), not to the shared infrastructure.
 */
object DropPerformers {
    fun forLogTable(project: Project): ((DropOperation) -> Boolean)? = null
}
