package `in`.kkkev.jjidea.jj

interface ChangeStatus {
    val isWorkingCopy: Boolean
    val hasConflict: Boolean
    val isEmpty: Boolean
    val immutable: Boolean
}
