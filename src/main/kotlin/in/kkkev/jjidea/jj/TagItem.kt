package `in`.kkkev.jjidea.jj

/**
 * Represents a tag with its name and associated change ID(s).
 * Used when listing tags from `jj tag list`.
 *
 * See [BookmarkItem]'s doc for [targets]/the secondary constructor - the same conflicted/divergent
 * shape applies to tags (jj-idea-bico).
 */
data class TagItem(
    val tag: Tag,
    override val targets: List<ChangeId>,
    override val immutables: List<Boolean> = List(targets.size) { false }
) : RefItem {
    constructor(tag: Tag, id: ChangeId?, immutable: Boolean = false) :
        this(tag, listOfNotNull(id), if (id == null) emptyList() else listOf(immutable))

    override val ref: Ref get() = tag
}
