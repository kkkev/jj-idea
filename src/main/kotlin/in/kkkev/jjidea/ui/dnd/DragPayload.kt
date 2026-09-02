package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag

/**
 * Something being dragged, independent of which surface the gesture started on - a commit is a
 * commit whether it was picked up from the log table's row or (later) some other surface, and a
 * bookmark chip is a bookmark chip whether it came from the log table or the bookmarks panel. This
 * is one half of the payload/target model from
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 1: [resolveDropOperation] is the
 * single place a [DragPayload] and a [DropTarget] combine into an operation - adding a new drag
 * source means adding a bean-provider hit-test that produces one of these, never a new operation.
 *
 * Every variant carries [repo] directly (rather than only via an [entry]) so
 * [in.kkkev.jjidea.ui.dnd.DragContext]'s cross-repository guard is uniform across payload kinds,
 * including [WorkingCopyRef] - the design doc sketches that variant as a bare `object`, but the log
 * is multi-root (`UnifiedJujutsuLogPanel`), so a dragged `@` marker must still say which repo's
 * working copy it is.
 */
sealed interface DragPayload {
    val repo: JujutsuRepository

    data class Commit(val entries: List<LogEntry>) : DragPayload {
        init {
            require(entries.isNotEmpty()) { "Commit payload must carry at least one entry" }
        }
        override val repo get() = entries.first().repo
    }

    data class BookmarkRef(val entry: LogEntry, val bookmark: Bookmark) : DragPayload {
        override val repo get() = entry.repo
    }

    data class TagRef(val entry: LogEntry, val tag: Tag) : DragPayload {
        override val repo get() = entry.repo
    }

    data class WorkingCopyRef(val entry: LogEntry) : DragPayload {
        override val repo get() = entry.repo
    }

    /**
     * Files dragged out of the changes tree, still attached to the [owner] change they belong to
     * today - needed by the split gesture's guard (design section 1: "a split is only meaningful at
     * a gap bordering the files' own change").
     */
    data class Files(val owner: LogEntry, val changes: List<Change>) : DragPayload {
        init {
            require(changes.isNotEmpty()) { "Files payload must carry at least one change" }
        }
        override val repo get() = owner.repo
    }
}
