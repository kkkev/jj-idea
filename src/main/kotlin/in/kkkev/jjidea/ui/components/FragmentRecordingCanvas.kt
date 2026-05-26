package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import java.net.URI

/**
 * A [TextCanvas] that records styled fragments instead of rendering them immediately.
 * Extends [StyledTextCanvas] so nested styling works — each fragment captures the accumulated
 * [SimpleTextAttributes] at append time.
 *
 * The [truncate] block marks its appended fragments as truncatable. During layout, the
 * truncatable range can be shortened to fit available space (see [FragmentLayout]).
 *
 * Fragments inside a [linked] block carry that URI as their [Fragment.linkTarget], enabling
 * hit-testing in interactive table renderers.
 */
class FragmentRecordingCanvas(initialFragments: List<Fragment> = emptyList()) : StyledTextCanvas() {
    sealed interface Fragment {
        val truncatable: Boolean
        val linkTarget: Any?

        data class Text(
            val text: String,
            val style: SimpleTextAttributes,
            override val truncatable: Boolean,
            override val linkTarget: Any? = null
        ) : Fragment

        data class Icon(
            val icon: IconSpec,
            override val truncatable: Boolean,
            val style: SimpleTextAttributes,
            override val linkTarget: Any? = null
        ) : Fragment
    }

    private val _fragments = initialFragments.toMutableList()
    val fragments: List<Fragment> get() = _fragments

    private var inTruncate = false
    private var currentLinkTarget: Any? = null

    /** Indices of the first and last truncatable fragment, or null if none. */
    val truncateRange: IntRange?
        get() {
            val first = _fragments.indexOfFirst { it.truncatable }
            if (first == -1) return null
            val last = _fragments.indexOfLast { it.truncatable }
            return first..last
        }

    override fun append(text: String) {
        _fragments.add(Fragment.Text(text, style, inTruncate, currentLinkTarget))
    }

    override fun append(icon: IconSpec) {
        _fragments.add(Fragment.Icon(applyCurrentColor(icon), inTruncate, style, currentLinkTarget))
    }

    override fun truncate(builder: TextCanvas.() -> Unit) {
        val was = inTruncate
        inTruncate = true
        builder()
        inTruncate = was
    }

    override fun linked(target: URI, builder: TextCanvas.() -> Unit) {
        val old = currentLinkTarget
        currentLinkTarget = target
        builder()
        currentLinkTarget = old
    }
}
