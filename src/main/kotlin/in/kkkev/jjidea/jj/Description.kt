package `in`.kkkev.jjidea.jj

class Description(val actual: String) {
    val empty get() = actual.isEmpty()
    val summary get() = display.lineSequence().first()
    val display get() = if (empty) "(no description)" else actual

    companion object {
        val EMPTY = Description("")
    }
}
