package `in`.kkkev.jjidea.preview

import `in`.kkkev.jjidea.JujutsuBundle

/**
 * A plugin feature that is gated behind [PreviewEntitlement] because it isn't finished enough
 * to show every Marketplace user - see `docs/design/preview-gating-and-dnd-sequencing.md`.
 *
 * Deliberately parallel to `jj.JjFeature`: an enum plus a bundle-backed [displayName], so the
 * settings panel can list preview features the same way it lists version-gated ones.
 */
enum class PreviewFeature(val id: String, private val displayNameKey: String) {
    /** Drag-and-drop graph operations in the log table and related panels (jj-idea-6oeg). */
    DRAG_AND_DROP("dragAndDrop", "preview.dragAndDrop.name");

    /** User-facing name for this feature, for the Preview features settings group. */
    val displayName: String get() = JujutsuBundle.message(displayNameKey)
}
