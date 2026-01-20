package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.JujutsuBundle

class Description(
    val actual: String
) {
    val empty get() = actual.isEmpty()
    val summary get() = display.lineSequence().first()
    val display get() = if (empty) JujutsuBundle.message("description.empty") else actual

    override fun toString() = actual

    override fun equals(other: Any?) = (other is Description) && actual.equals(other.actual)

    override fun hashCode() = actual.hashCode()

    companion object {
        val EMPTY = Description("")
    }
}
