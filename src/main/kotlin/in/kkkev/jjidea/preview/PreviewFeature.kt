package `in`.kkkev.jjidea.preview

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.util.snakeToCamelCase

/**
 * A plugin feature that is gated behind [PreviewEntitlement] because it isn't finished enough
 * to show every Marketplace user - see `docs/design/preview-gating-and-dnd-sequencing.md`.
 *
 * Deliberately parallel to `jj.JjFeature`: an enum plus a bundle-backed [displayName], so the
 * settings panel can list preview features the same way it lists version-gated ones.
 */
enum class PreviewFeature {
    /** Drag-and-drop graph operations in the log table and related panels (jj-idea-6oeg). */
    DRAG_AND_DROP,

    /**
     * jj-idea-2c8k (GitHub #69), early access: loads the log in pages (each "changes to show"
     * wide) instead of reloading the whole configured limit on every write. Off by default —
     * see docs/design/jj-idea-2c8k-paged-log-loading.md for the mechanism and its validated
     * (and not-yet-validated) boundaries.
     */
    PAGED_LOG_LOAD;

    val id = name.snakeToCamelCase()
    val displayName get() = JujutsuBundle.message("preview.$id.name")
    val comment get() = JujutsuBundle.message("preview.$id.comment")
}
