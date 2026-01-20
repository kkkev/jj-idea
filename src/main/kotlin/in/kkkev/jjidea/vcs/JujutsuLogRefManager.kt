package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.util.Comparing
import com.intellij.ui.JBColor
import com.intellij.vcs.log.*
import org.jetbrains.annotations.Unmodifiable
import java.awt.Color
import java.io.DataInput
import java.io.DataOutput

/**
 * Manages Jujutsu bookmarks (refs) in the VCS log
 */
class JujutsuLogRefManager : VcsLogRefManager {
    companion object {
        // Theme-aware colors with fallbacks to standard VCS log colors
        private val BOOKMARK_COLOR =
            JBColor.namedColor(
                "VersionControl.JujutsuLog.bookmarkIconColor",
                VcsLogStandardColors.Refs.BRANCH
            )

        private val WORKING_COPY_COLOR = JBColor.namedColor(
            "VersionControl.JujutsuLog.workingCopyIconColor",
            VcsLogStandardColors.Refs.TIP
        )

        // Jujutsu ref types (bookmarks are like Git branches)
        val BOOKMARK = object : VcsRefType {
            override fun isBranch() = true

            override fun getBackgroundColor() = BOOKMARK_COLOR
        }

        val WORKING_COPY = object : VcsRefType {
            override fun isBranch() = false

            override fun getBackgroundColor() = WORKING_COPY_COLOR
        }
    }

    override fun groupForTable(
        references: Collection<VcsRef>,
        compact: Boolean,
        showTagNames: Boolean
    ): List<RefGroup> {
        // Create individual groups for each ref so they display as separate tags
        val result = mutableListOf<RefGroup>()

        val bookmarks = references.filter { it.type == BOOKMARK }
        val workingCopy = references.filter { it.type == WORKING_COPY }

        // Working copy refs - each gets its own group with color
        workingCopy.forEach { ref ->
            result.add(RefGroupImpl(true, ref.name, listOf(ref), listOf(ref.type.backgroundColor)))
        }

        // Bookmarks - each gets its own group for individual display with color
        bookmarks.forEach { ref ->
            result.add(RefGroupImpl(false, ref.name, listOf(ref), listOf(ref.type.backgroundColor)))
        }

        return result
    }

    override fun serialize(output: DataOutput, type: VcsRefType) {
        // Serialize ref type - for now just use a simple int
        when (type) {
            WORKING_COPY -> output.writeInt(0)
            BOOKMARK -> output.writeInt(1)
            else -> output.writeInt(2)
        }
    }

    /**
     * Comparator for sorting Jujutsu refs
     */
    object JujutsuRefComparator : Comparator<VcsRef> {
        override fun compare(ref1: VcsRef, ref2: VcsRef): Int {
            val key1 = getRefSortKey(ref1)
            val key2 = getRefSortKey(ref2)
            return key1.compareTo(key2)
        }

        fun getRefSortKey(ref: VcsRef): RefSortKey {
            val type = ref.type
            val name = ref.name

            // Working copy (@) comes first
            if (type == WORKING_COPY) {
                return RefSortKey(0, name)
            }

            // Then bookmarks
            if (type == BOOKMARK) {
                return RefSortKey(1, name)
            }

            // Everything else
            return RefSortKey(2, name)
        }

        data class RefSortKey(val priority: Int, val name: String) : Comparable<RefSortKey> {
            override fun compareTo(other: RefSortKey): Int {
                val priorityCompare = priority.compareTo(other.priority)
                if (priorityCompare != 0) return priorityCompare
                return Comparing.compare(name, other.name)
            }
        }
    }

    override fun getBranchLayoutComparator(): Comparator<VcsRef?> = Comparator { ref1, ref2 ->
        when {
            ref1 == null && ref2 == null -> 0
            ref1 == null -> 1
            ref2 == null -> -1
            else -> JujutsuRefComparator.compare(ref1, ref2)
        }
    }

    override fun getLabelsOrderComparator(): Comparator<VcsRef?> = Comparator { ref1, ref2 ->
        when {
            ref1 == null && ref2 == null -> 0
            ref1 == null -> 1
            ref2 == null -> -1
            else -> JujutsuRefComparator.compare(ref1, ref2)
        }
    }

    override fun groupForBranchFilter(refs: Collection<VcsRef?>): @Unmodifiable List<RefGroup?> {
        // Simple grouping - bookmarks together
        val nonNullRefs = refs.filterNotNull()
        if (nonNullRefs.isEmpty()) return emptyList()

        val bookmarks = nonNullRefs.filter { it.type == BOOKMARK }
        return if (bookmarks.isEmpty()) emptyList() else listOf(RefGroupImpl(true, "Bookmarks", bookmarks))
    }

    override fun deserialize(input: DataInput): VcsRefType = when (input.readInt()) {
        0 -> WORKING_COPY
        1 -> BOOKMARK
        else -> BOOKMARK
    }

    override fun isFavorite(ref: VcsRef): Boolean {
        // No favorites support for now
        return false
    }

    override fun setFavorite(ref: VcsRef, favorite: Boolean) {
        // No favorites support for now
    }
}

// TODO Do we still need this or can we import from framework?
data class RefGroupImpl(
    private val expanded: Boolean,
    private val name: String,
    private val refs: List<VcsRef>,
    private val colors: List<Color> = emptyList()
) : RefGroup {
    override fun isExpanded() = expanded

    override fun getName() = name

    override fun getRefs() = refs

    override fun getColors() = colors
}
