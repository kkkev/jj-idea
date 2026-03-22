package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Base64
import javax.swing.Icon
import kotlin.math.roundToInt

/**
 * Map of CSS class name to (CSS property, color).
 * e.g., `"primary" to ("stroke" to JBColor(light, dark))`
 *
 * Colors may be [com.intellij.ui.JBColor] instances for theme-aware rendering:
 * colors are resolved lazily via [Color.getRGB] when the icon is painted, so
 * JBColor automatically picks the correct light/dark variant.
 */
typealias ColorMap = Map<String, Pair<String, Color>>

/**
 * An [Icon] backed by SVG resource bytes that supports CSS-class-based color
 * transformation at the SVG level.
 *
 * The SVG's `<style>` block is modified to replace colors for specific CSS
 * classes, then the modified SVG is loaded through [IconLoader] — IntelliJ's
 * public icon API — which handles JSVG parsing, rendering, HiDPI, and caching.
 *
 * Theme-aware [com.intellij.ui.JBColor] values are resolved lazily: a digest
 * of current color RGB values is compared on each paint, and the icon delegate
 * is recreated only when the theme actually changes.
 */
class SvgIcon private constructor(
    private val path: String,
    private val originalBytes: ByteArray,
    private val colors: ColorMap
) : Icon {
    @Volatile private var cachedDigest = Long.MIN_VALUE

    @Volatile private var delegate: Icon? = null

    private fun colorDigest() = colors.entries.fold(0L) { acc, (k, v) ->
        acc * 31 + k.hashCode() + v.second.rgb.toLong()
    }

    private fun delegate(): Icon {
        val digest = colorDigest()
        val icon = delegate
        if (icon != null && digest == cachedDigest) return icon
        val modifiedBytes = if (colors.isNotEmpty()) applyColors(originalBytes, colors) else originalBytes
        val cl = SvgBytesClassLoader(path.removePrefix("/"), modifiedBytes)
        return (IconLoader.findIcon(path, cl) ?: error("Failed to create icon from $path")).also {
            cachedDigest = digest
            delegate = it
        }
    }

    override fun getIconWidth() = delegate().iconWidth
    override fun getIconHeight() = delegate().iconHeight
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = delegate().paintIcon(c, g, x, y)

    fun recolored(newColors: ColorMap) = SvgIcon(path, originalBytes, newColors)

    companion object {
        fun load(path: String, context: Class<*>): SvgIcon {
            val bytes = context.getResourceAsStream(path)?.readBytes()
                ?: error("SVG resource not found: $path")
            return SvgIcon(path, bytes, emptyMap())
        }

        private val CSS_RULE = Regex("""\.([\w-]+)\s*\{[^}]*\}""")

        private fun applyColors(svgBytes: ByteArray, colors: ColorMap): ByteArray {
            var svg = String(svgBytes, Charsets.UTF_8)
            svg = svg.replace(CSS_RULE) { match ->
                val className = match.groupValues[1]
                colors[className]?.let { (property, color) ->
                    ".$className { $property: #${ColorUtil.toHex(color)} }"
                } ?: match.value
            }
            return svg.toByteArray(Charsets.UTF_8)
        }
    }
}

/**
 * A classloader that serves a single SVG resource from an in-memory byte array.
 *
 * Both [getResource] and [getResourceAsStream] are overridden so that
 * [IconLoader.findIcon] can resolve the modified SVG bytes via the standard
 * classloader API — no internal IntelliJ APIs needed.
 */
private class SvgBytesClassLoader(
    private val resourceName: String,
    private val bytes: ByteArray
) : ClassLoader(null) {
    override fun getResource(name: String): URL? {
        if (name != resourceName) return null
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return URL(
            null,
            "data:image/svg+xml;base64,$encoded",
            DataHandler
        )
    }

    override fun getResourceAsStream(name: String): InputStream? =
        if (name == resourceName) ByteArrayInputStream(bytes) else null

    private object DataHandler : URLStreamHandler() {
        override fun openConnection(u: URL) = object : URLConnection(u) {
            override fun connect() {}
            override fun getInputStream(): InputStream {
                val encoded = u.toString().substringAfter("base64,")
                return ByteArrayInputStream(Base64.getDecoder().decode(encoded))
            }
        }
    }
}

/**
 * Creates a copy of this icon with all non-white colors replaced by [color].
 *
 * For [SvgIcon] instances, performs SVG-level transformation by recoloring
 * all known CSS classes. For other icon types, falls back to raster-based
 * recoloring (painting to a [BufferedImage] and replacing pixels).
 *
 * Used for the `#HEXCOLOR` icon suffix in [in.kkkev.jjidea.ui.components.IconResolver].
 */
fun Icon.uniformRecolored(color: Color): Icon = when (this) {
    is SvgIcon -> recolored(
        mapOf(
            "primary" to ("stroke" to color),
            "primary-fill" to ("fill" to color),
            "accent" to ("stroke" to color)
        )
    )
    else -> RasterRecoloredIcon(this, color)
}

private class RasterRecoloredIcon(private val base: Icon, private val color: Color) : Icon {
    override fun getIconWidth() = base.iconWidth
    override fun getIconHeight() = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val w = iconWidth
        val h = iconHeight
        if (w <= 0 || h <= 0) return

        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val ig = image.createGraphics()
        base.paintIcon(c, ig, 0, 0)
        ig.dispose()

        val rgb = color.rgb and 0x00FFFFFF
        for (py in 0 until h) {
            for (px in 0 until w) {
                val pixel = image.getRGB(px, py)
                if (pixel ushr 24 == 0) continue
                val r = (pixel shr 16) and 0xFF
                val gr = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 240 && gr > 240 && b > 240) continue
                image.setRGB(px, py, (pixel and 0xFF000000.toInt()) or rgb)
            }
        }
        g.drawImage(image, x, y, null)
    }
}

/**
 * Scales any [Icon] via Graphics2D coordinate transformation.
 *
 * For SVG-backed icons (both [SvgIcon] and IntelliJ's internal SVG icons),
 * rendering happens at the vector level since JSVG draws paths through the
 * transformed Graphics2D, preserving full quality at any scale.
 */
class ScaledIcon(private val base: Icon, private val scale: Float) : Icon {
    override fun getIconWidth() = (base.iconWidth * scale).roundToInt()
    override fun getIconHeight() = (base.iconHeight * scale).roundToInt()

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.translate(x.toDouble(), y.toDouble())
        g2.scale(scale.toDouble(), scale.toDouble())
        base.paintIcon(c, g2, 0, 0)
        g2.dispose()
    }
}
