package `in`.kkkev.jjidea.preview

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.util.snakeToCamelCase

/**
 * A plugin feature that is gated behind [PreviewEntitlement] because it isn't finished enough
 * to show every Marketplace user - see `docs/design/preview-gating-and-dnd-sequencing.md`.
 *
 * Deliberately parallel to `jj.JjFeature`: an enum plus a bundle-backed [displayName], so the
 * settings panel can list preview features the same way it lists version-gated ones.
 *
 * [bit] is this feature's position in the [PreviewCode] features bitmap (bit 7 is reserved for
 * the ALL wildcard - see [PreviewCode]). At most 7 features may be gated concurrently. A bit is
 * never reused once assigned: if a feature graduates out of preview and is removed from this
 * enum, its old bit stays retired until every code that could have granted it (see each code's
 * expiry, tracked in the private access-code registry) has expired - reusing it sooner would let
 * a still-valid old code silently grant whatever new feature claims the bit.
 */
enum class PreviewFeature(val bit: Int) {
    /** bit 0 retired (was DRAG_AND_DROP, graduated in jj-idea-jxii) - never reuse */

    /**
     * jj-idea-2c8k (GitHub #69), early access: loads the log in pages (500 rows each,
     * [in.kkkev.jjidea.ui.log.PagedLogWindow.PAGE_ROWS]; "Changes to show" no longer sets the page
     * size) instead of reloading the whole configured limit on every write. Off by default —
     * see docs/design/jj-idea-2c8k-paged-log-loading.md for the mechanism and its validated
     * (and not-yet-validated) boundaries.
     */
    PAGED_LOG_LOAD(1),

    /**
     * In-editor jj conflict marker regions: per-side gutter icons with accept actions
     * (jj-idea-82fo), per-side background tints, and hover feedback (jj-idea-sr42), all gated as
     * one unit under jj-idea-n6fz.1 - a large, already-built surface withheld from a gradual
     * release. Gates [in.kkkev.jjidea.ui.editor.conflict.JujutsuConflictGutterInstaller] alone;
     * the S1 editor banner (jj-idea-lkrt, `JujutsuConflictEditorNotificationProvider`) is
     * unaffected and keeps shipping ungated.
     */
    CONFLICT_GUTTER(2);

    val id = name.snakeToCamelCase()
    val displayName get() = JujutsuBundle.message("preview.$id.name")
    val comment get() = JujutsuBundle.message("preview.$id.comment")
}
