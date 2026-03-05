package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes

/**
 * A [TextCanvas] that records styled fragments instead of rendering them immediately.
 * Extends [StyledTextCanvas] so nested styling works — each fragment captures the accumulated
 * [SimpleTextAttributes] at append time.
 *
 * The [truncate] block marks its appended fragments as truncatable. During layout, the
 * truncatable range can be shortened to fit available space (see [FragmentLayout]).
 */
class FragmentRecordingCanvas(initialFragments: List<Fragment> = emptyList()) : StyledTextCanvas() {
    sealed interface Fragment {
        val truncatable: Boolean

        data class Text(val text: String, val style: SimpleTextAttributes, override val truncatable: Boolean) : Fragment
        data class Icon(val icon: IconSpec, override val truncatable: Boolean) : Fragment
    }

    private val _fragments = initialFragments.toMutableList()
    val fragments: List<Fragment> get() = _fragments

    private var inTruncate = false

    /** Indices of the first and last truncatable fragment, or null if none. */
    val truncateRange: IntRange?
        get() {
            val first = _fragments.indexOfFirst { it.truncatable }
            if (first == -1) return null
            val last = _fragments.indexOfLast { it.truncatable }
            return first..last
        }

    override fun append(text: String) {
        _fragments.add(Fragment.Text(text, style, inTruncate))
    }

    override fun append(icon: IconSpec) {
        _fragments.add(Fragment.Icon(icon, inTruncate))
    }

    override fun truncate(builder: TextCanvas.() -> Unit) {
        val was = inTruncate
        inTruncate = true
        builder()
        inTruncate = was
    }
}
