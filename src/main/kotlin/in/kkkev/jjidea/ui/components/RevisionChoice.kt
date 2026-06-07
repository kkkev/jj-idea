package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RefItem
import `in`.kkkev.jjidea.jj.Revision

sealed class RevisionChoice(open val displayName: String, open val revision: Revision) {
    data class Change(
        val entry: LogEntry,
        override val displayName: String = "${entry.id.short} ${entry.description.summary}",
        override val revision: Revision = entry.id
    ) : RevisionChoice(displayName, revision) {
        val id: ChangeId get() = entry.id
        val description: Description get() = entry.description
    }

    data class Ref(
        val item: RefItem,
        override val displayName: String = "${item.ref}${item.id?.let { " (${it.short})" } ?: ""}",
        override val revision: Revision = item.ref
    ) : RevisionChoice(displayName, revision)
}
