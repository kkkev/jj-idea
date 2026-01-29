package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import java.awt.Color

/**
 * Assigns stable colors to repositories for visual identification.
 *
 * Colors are assigned based on repository index in a stable, distinct palette.
 * The same repository will always get the same color within a session.
 */
object RepositoryColors {
    /**
     * Distinct colors for repository identification.
     * These are muted/earthy tones that are clearly distinct from the bright graph lane colors.
     * Graph lanes use: Blue, Red, Yellow, Green, Orange, Purple, Cyan, Light green (all bright)
     * Repo colors use: Teal, Coral, Slate, Olive, Mauve, Brown, Steel, Sage (all muted)
     */
    private val COLORS = listOf(
        JBColor(Color(0x008080), Color(0x20B2AA)),  // Teal
        JBColor(Color(0xCD5C5C), Color(0xF08080)),  // Indian Red/Light Coral
        JBColor(Color(0x708090), Color(0x778899)),  // Slate Gray
        JBColor(Color(0x6B8E23), Color(0x9ACD32)),  // Olive Drab
        JBColor(Color(0x9370DB), Color(0xBA55D3)),  // Medium Purple/Mauve
        JBColor(Color(0x8B4513), Color(0xD2691E)),  // Saddle Brown/Chocolate
        JBColor(Color(0x4682B4), Color(0x5F9EA0)),  // Steel Blue/Cadet Blue
        JBColor(Color(0x8FBC8F), Color(0x98FB98))   // Dark Sea Green/Pale Green
    )

    /**
     * Cache of repository to color index mapping.
     * Uses repository directory path as stable key.
     */
    private val colorAssignments = mutableMapOf<String, Int>()
    private var nextColorIndex = 0

    /**
     * Get the color for a repository.
     * Colors are assigned stably - the same repository always gets the same color.
     */
    fun getColor(repo: JujutsuRepository): JBColor {
        val key = repo.directory.path
        val colorIndex = colorAssignments.getOrPut(key) {
            val index = nextColorIndex
            nextColorIndex = (nextColorIndex + 1) % COLORS.size
            index
        }
        return COLORS[colorIndex]
    }

    /**
     * Get the color for a repository at a specific index in a list.
     * Useful when you have a stable list of repositories.
     */
    fun getColorForIndex(index: Int): JBColor = COLORS[index % COLORS.size]

    /**
     * Get a lighter opaque version of a color for backgrounds.
     * Uses color blending instead of transparency to avoid hover/selection bleeding through.
     */
    fun getBackgroundColor(color: JBColor): Color {
        // Blend with white (light theme) or dark gray (dark theme) to create muted background
        // Using opaque colors prevents underlying hover/selection from showing through
        return JBColor(
            blendColors(color, Color.WHITE, 0.85f),      // Light theme: 85% white, 15% color
            blendColors(color.darker(), Color(0x2B2D30), 0.75f)  // Dark theme: 75% dark gray, 25% color
        )
    }

    /**
     * Blend two colors together.
     * @param c1 First color
     * @param c2 Second color
     * @param ratio How much of c2 to use (0.0 = all c1, 1.0 = all c2)
     */
    private fun blendColors(c1: Color, c2: Color, ratio: Float): Color {
        val r = (c1.red * (1 - ratio) + c2.red * ratio).toInt().coerceIn(0, 255)
        val g = (c1.green * (1 - ratio) + c2.green * ratio).toInt().coerceIn(0, 255)
        val b = (c1.blue * (1 - ratio) + c2.blue * ratio).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }

    /**
     * Get a gutter strip color (more saturated for narrow strips).
     */
    fun getGutterColor(repo: JujutsuRepository): JBColor = getColor(repo)

    /**
     * Reset color assignments (for testing or when roots change significantly).
     */
    fun reset() {
        colorAssignments.clear()
        nextColorIndex = 0
    }
}
