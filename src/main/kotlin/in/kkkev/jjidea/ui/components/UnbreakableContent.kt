package `in`.kkkev.jjidea.ui.components

import java.awt.Color
import java.net.URLDecoder
import java.net.URLEncoder

/** Marker prefix on an `<img>` element's `src`, recognized by [AtomicHtmlExtension]: an unbreakable unit. */
internal const val UNBREAKABLE_PREFIX = "unbreakable:"

/**
 * The content of one [TextCanvas.appendUnbreakable] unit, as the same text/icon runs the log table's
 * [FragmentRecordingCanvas] records, so [AtomicHtmlView] can lay it out and paint it the way the log
 * table does (jj-idea-3as8) rather than through a private nested HTML document.
 *
 * [outerStyle] is the canvas's `SimpleTextAttributes` style at the point the unit was opened: the
 * surrounding HTML already applies it to the unit's ambient font (e.g. a chip inside `smaller {}`),
 * so only the bits each run adds on top of it change that run's font.
 */
internal data class ChipContent(val outerStyle: Int, val runs: List<ChipRun>) {
    /** The unit's text as a user would copy it (jj-idea-5zio) - icons carry no text. */
    val plainText: String get() = runs.filterIsInstance<ChipRun.Text>().joinToString("") { it.text }
}

internal sealed interface ChipRun {
    /** The full `SimpleTextAttributes` style flags in effect for this run. */
    val style: Int

    /** A link opened *inside* the unit (e.g. an issue reference in a bookmark name), not the unit's own link. */
    val link: String?

    data class Text(val text: String, override val style: Int, val color: Color?, override val link: String?) :
        ChipRun

    /** [key] is an [IconSpec.qualified] key, resolved by [IconResolver]. */
    data class Icon(val key: String, override val style: Int, override val link: String?) : ChipRun
}

/**
 * Wire format for [ChipContent], carried URL-encoded in an `<img src>` attribute value: a genuine HTML
 * void element is the only thing that survives JBHtmlPane's Jsoup transpile *and* Swing's parser as one
 * atomic leaf Element (jj-idea-vll4/jj-idea-m2wr). Swing's parser never creates a branch `Element` for an
 * inline tag, so the unit's content can't be nested markup either.
 *
 * Decoded, the payload is one line per run (the first line is [ChipContent.outerStyle]), with fields
 * separated by [FIELD]. Text is escaped only for the separators themselves, so a run's text still reads
 * verbatim in the decoded payload.
 */
internal object UnbreakableContent {
    private const val FIELD = '\u001F'
    private const val RUN = '\n'

    fun encode(content: ChipContent): String = URLEncoder.encode(serialize(content), "UTF-8")

    fun decode(payload: String): ChipContent =
        runCatching { parse(URLDecoder.decode(payload, "UTF-8")) }.getOrElse { ChipContent(0, emptyList()) }

    private fun serialize(content: ChipContent) = buildString {
        append(content.outerStyle)
        content.runs.forEach { run ->
            append(RUN)
            val fields = when (run) {
                is ChipRun.Text ->
                    listOf("T", run.style.toString(), run.color?.toHex().orEmpty(), run.link.orEmpty(), run.text)
                is ChipRun.Icon -> listOf("I", run.style.toString(), "", run.link.orEmpty(), run.key)
            }
            fields.joinTo(this, FIELD.toString()) { escape(it) }
        }
    }

    private fun parse(text: String): ChipContent {
        val lines = text.split(RUN)
        val runs = lines.drop(1).map { line ->
            val fields = line.split(FIELD).map(::unescape)
            val style = fields[1].toInt()
            val link = fields[3].ifEmpty { null }
            when (fields[0]) {
                "T" -> ChipRun.Text(fields[4], style, fields[2].ifEmpty { null }?.let { Color(it.toInt(16)) }, link)
                "I" -> ChipRun.Icon(fields[4], style, link)
                else -> error("Unknown run kind: ${fields[0]}")
            }
        }
        return ChipContent(lines[0].toInt(), runs)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(FIELD.toString(), "\\f")

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(
                    when (s[i + 1]) {
                        'n' -> '\n'
                        'f' -> FIELD
                        else -> s[i + 1]
                    }
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun Color.toHex() = String.format("%06x", rgb and 0xffffff)
}
