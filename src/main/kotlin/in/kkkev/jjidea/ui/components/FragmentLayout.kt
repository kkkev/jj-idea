package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import java.awt.Font
import java.awt.font.FontRenderContext
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Static utility for measuring, truncating, and rendering [Fragment] lists.
 * Text fragments render to [SimpleColoredComponent]; icon fragments render to [JLabel].
 * No custom `paintComponent` — standard Swing components only.
 */
object FragmentLayout {
    private const val ELLIPSIS = "..."

    /** Measure total pixel width of the fragment list using the given base font. */
    fun measureWidth(fragments: List<Fragment>, baseFont: Font, frc: FontRenderContext): Double =
        fragments.sumOf { fragmentWidth(it, baseFont, frc) }

    /**
     * Returns a new fragment list with the truncatable region shortened to fit [availableWidth].
     *
     * Non-truncatable fragments keep their full width. The truncatable fragments are measured
     * left-to-right: those that fit entirely are kept; the first one that overflows is truncated
     * (with "..." appended in that fragment's style); subsequent truncatable fragments are dropped.
     *
     * If [truncateRange] is null (nothing marked truncatable), returns [fragments] unchanged.
     */
    fun truncateToFit(
        fragments: List<Fragment>,
        truncateRange: IntRange?,
        availableWidth: Double,
        baseFont: Font,
        frc: FontRenderContext
    ): List<Fragment> {
        if (truncateRange == null) return fragments

        // Measure non-truncatable width
        val nonTruncatableWidth = fragments.filterIndexed { i, _ -> i !in truncateRange }
            .sumOf { fragmentWidth(it, baseFont, frc) }

        val widthForTruncatable = availableWidth - nonTruncatableWidth
        if (widthForTruncatable <= 0) {
            // No space for truncatable content at all — drop it
            return fragments.filterIndexed { i, _ -> i !in truncateRange }
        }

        // Check if everything fits
        val truncatableWidth = fragments.filterIndexed { i, _ -> i in truncateRange }
            .sumOf { fragmentWidth(it, baseFont, frc) }
        if (truncatableWidth <= widthForTruncatable) return fragments

        // Need to truncate. Walk truncatable fragments left-to-right.
        val result = mutableListOf<Fragment>()
        var usedWidth = 0.0
        var truncated = false

        for ((i, fragment) in fragments.withIndex()) {
            if (i !in truncateRange) {
                result.add(fragment)
                continue
            }
            if (truncated) continue // drop remaining truncatable fragments

            val fragWidth = fragmentWidth(fragment, baseFont, frc)
            if (usedWidth + fragWidth <= widthForTruncatable) {
                result.add(fragment)
                usedWidth += fragWidth
            } else {
                // This fragment overflows — truncate it
                truncated = true
                if (fragment is Fragment.Text) {
                    val truncatedText = truncateTextToFit(
                        fragment.text,
                        fragment.style,
                        baseFont,
                        frc,
                        widthForTruncatable - usedWidth
                    )
                    if (truncatedText.isNotEmpty()) {
                        result.add(Fragment.Text(truncatedText, fragment.style, fragment.truncatable))
                    }
                }
                // Icon fragments that overflow are just dropped
            }
        }
        return result
    }

    /**
     * Render fragments into a [JPanel] using [BoxLayout]. Text fragments are appended to
     * [SimpleColoredComponent]s; icon fragments become [JLabel]s. Adjacent text fragments
     * share the same SCC to avoid unnecessary component boundaries.
     */
    fun renderToPanel(fragments: List<Fragment>, panel: JPanel) {
        panel.removeAll()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        var currentScc: SimpleColoredComponent? = null

        for (fragment in fragments) {
            when (fragment) {
                is Fragment.Text -> {
                    if (currentScc == null) {
                        currentScc = SimpleColoredComponent().also {
                            it.isOpaque = false
                            panel.add(it)
                        }
                    }
                    currentScc.append(fragment.text, fragment.style)
                }
                is Fragment.Icon -> {
                    currentScc = null
                    IconResolver.resolveIcon(fragment.icon.qualified)?.let { icon ->
                        panel.add(JLabel(icon).also { it.isOpaque = false })
                    }
                }
            }
        }
    }

    private fun fragmentWidth(fragment: Fragment, baseFont: Font, frc: FontRenderContext) = when (fragment) {
        is Fragment.Text -> textWidth(fragment.text, baseFont.deriveFont(fragment.style), frc)
        is Fragment.Icon -> IconResolver.resolveIcon(fragment.icon.qualified)?.iconWidth?.toDouble() ?: 0.0
    }

    private fun textWidth(text: String, font: Font, frc: FontRenderContext) =
        font.getStringBounds(text, frc).width

    /**
     * Binary search for the longest prefix of [text] that, with "..." appended, fits in [availableWidth].
     */
    private fun truncateTextToFit(
        text: String,
        style: SimpleTextAttributes,
        baseFont: Font,
        frc: FontRenderContext,
        availableWidth: Double
    ): String {
        val font = baseFont.deriveFont(style)
        val ellipsisWidth = textWidth(ELLIPSIS, font, frc)
        if (ellipsisWidth > availableWidth) return ""

        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val candidateWidth = textWidth(text.substring(0, mid), font, frc) + ellipsisWidth
            if (candidateWidth <= availableWidth) lo = mid else hi = mid - 1
        }
        return if (lo == 0) "" else text.substring(0, lo) + ELLIPSIS
    }
}

private fun Font.deriveFont(style: SimpleTextAttributes): Font {
    val fontStyle = style.fontStyle
    return if (style.isSmaller) {
        deriveFont(this.style or fontStyle, size2D * 0.85f)
    } else {
        deriveFont(this.style or fontStyle)
    }
}
