package `in`.kkkev.jjidea.jj

interface ChangeStatus {
    val isWorkingCopy: Boolean
    val hasConflict: Boolean
    val isEmpty: Boolean
    val immutable: Boolean

    object Default : ChangeStatus {
        override val isWorkingCopy = false
        override val hasConflict = false
        override val isEmpty = false
        override val immutable = false
    }
}
