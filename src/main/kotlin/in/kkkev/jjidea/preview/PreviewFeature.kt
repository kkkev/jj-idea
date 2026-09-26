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
    /** Drag-and-drop graph operations in the log table and related panels (jj-idea-6oeg). */
    DRAG_AND_DROP(0),

    /**
     * jj-idea-2c8k (GitHub #69), early access: loads the log in pages (each "changes to show"
     * wide) instead of reloading the whole configured limit on every write. Off by default —
     * see docs/design/jj-idea-2c8k-paged-log-loading.md for the mechanism and its validated
     * (and not-yet-validated) boundaries.
     */
    PAGED_LOG_LOAD(1);

    val id = name.snakeToCamelCase()
    val displayName get() = JujutsuBundle.message("preview.$id.name")
    val comment get() = JujutsuBundle.message("preview.$id.comment")
}
