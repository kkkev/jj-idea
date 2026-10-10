package `in`.kkkev.jjidea.preview

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.util.snakeToCamelCase

/**
 * What the access-code machinery ([PreviewCode], [AccessCode], [PreviewEntitlement]) needs to know about
 * a gated feature. [PreviewFeature] is the only production implementation; the interface exists so
 * the machinery stays testable while the enum has no entries (nothing is in preview) - tests pass
 * a test-only implementation as the `catalog`/feature argument.
 */
interface PreviewFeatureSpec {
    val id: String
    val bit: Int
    val displayName: String
}

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
enum class PreviewFeature(override val bit: Int) : PreviewFeatureSpec {
    /** bit 0 retired (was DRAG_AND_DROP, graduated in jj-idea-jxii) - never reuse */
    /** bit 1 retired (was PAGED_LOG_LOAD, graduated in jj-idea-2570.4) - never reuse */
    /** bit 2 retired (was CONFLICT_GUTTER, graduated in jj-idea-n6fz.4) - never reuse */
    ;

    override val id = name.snakeToCamelCase()
    override val displayName get() = JujutsuBundle.message("preview.$id.name")
    val comment get() = JujutsuBundle.message("preview.$id.comment")
}
