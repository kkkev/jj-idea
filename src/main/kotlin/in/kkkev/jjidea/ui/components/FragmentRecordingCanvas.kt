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
 *
 * [linkifier] is injected once here rather than threaded through every append call (jj-idea-91qf,
 * jj-idea-vrmv) - see [TextCanvas.linkifier]. Hover-underline is applied afterwards, as a pure
 * transform on the recorded [fragments] (see [underlining]) rather than baked in at append time.
 */
class FragmentRecordingCanvas(
    initialFragments: List<Fragment> = emptyList(),
    override val linkifier: Linkifier = Linkifier.None
) : StyledTextCanvas() {
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
        // super.linked applies the link-color style (StyledTextCanvas.linked); a bare `builder()`
        // here would only track currentLinkTarget for hit-testing and silently skip coloring -
        // any caller not already wrapped in its own colored() (e.g. a description's issue-tracker
        // link, which has no such wrapper) would render in the surrounding plain-text color.
        super.linked(target, builder)
        currentLinkTarget = old
    }
}

/**
 * Underlines the fragment(s) whose [FragmentRecordingCanvas.Fragment.linkTarget] equals [target], e.g.
 * while the pointer is over that link (jj-idea-91qf) - matching the "colored always, underlined on
 * hover" convention used elsewhere (jj-idea-iesq). A pure post-build transform rather than baked in at
 * append time, since the hovered target is only known after layout, letting a canvas be built once and
 * reused across repaints regardless of what's currently hovered.
 */
fun List<FragmentRecordingCanvas.Fragment>.underlining(target: URI?): List<FragmentRecordingCanvas.Fragment> {
    if (target == null) return this
    return map { fragment ->
        if (fragment.linkTarget != target || fragment !is FragmentRecordingCanvas.Fragment.Text) return@map fragment
        fragment.copy(
            style = fragment.style.let {
                it.derive(it.style or SimpleTextAttributes.STYLE_UNDERLINE, null, null, null)
            }
        )
    }
}
