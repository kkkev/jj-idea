package `in`.kkkev.jjidea.jj

/**
 * Represents a tag with its name and associated change ID.
 * Used when listing tags from `jj tag list`.
 */
data class TagItem(val tag: Tag, override val id: ChangeId?) : RefItem {
    override val ref: Ref get() = tag
}
