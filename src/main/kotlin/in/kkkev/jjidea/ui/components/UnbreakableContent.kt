package `in`.kkkev.jjidea.ui.components

import java.net.URLDecoder
import java.net.URLEncoder

/** Marker prefix on an `<img>` element's `src`, recognized by [AtomicHtmlExtension], identifying an unbreakable HTML fragment. */
internal const val UNBREAKABLE_PREFIX = "unbreakable:"

/**
 * Wire format for [TextCanvas.appendUnbreakable]'s content: a single URL-encoded HTML fragment,
 * decoded back by [AtomicHtmlExtension] into an [AtomicHtmlView]. The payload is arbitrary HTML
 * built from the caller's own [TextCanvas] calls (icons, links, colored spans, in any
 * order/combination) rather than a fixed positional schema, so there's nothing for the encoder and
 * decoder to drift on. Carried as an encoded `<img src>` attribute value rather than literal nested
 * markup because Swing's HTML parser never creates a branch `Element` for an inline tag (it flattens
 * character-level formatting onto leaf elements' `AttributeSet`s instead) - there's no way to give an
 * `<unbreakable>`-shaped tag real DOM children and have Swing treat it as one atomic inline unit.
 */
internal object UnbreakableContent {
    fun encode(html: String): String = URLEncoder.encode(html, "UTF-8")
    fun decode(payload: String): String = URLDecoder.decode(payload, "UTF-8")
}
