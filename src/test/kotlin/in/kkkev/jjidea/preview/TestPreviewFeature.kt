package `in`.kkkev.jjidea.preview

/**
 * Test-only [PreviewFeatureSpec]s. Nothing is in preview in production ([PreviewFeature] has no
 * entries once the last gated feature graduates), so the access-code machinery is exercised with
 * these instead, passed as the `catalog` / feature argument.
 */
enum class TestPreviewFeature(override val bit: Int, override val displayName: String) : PreviewFeatureSpec {
    SAMPLE(0, "Sample Feature"),
    OTHER(3, "Other Feature");

    override val id = name.lowercase()
}
