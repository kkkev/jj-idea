# Graduating preview features

Single source of truth for taking a `PreviewFeature` out of preview. Every "Graduate ..." bead
(`jj-idea-jxii` drag-and-drop, `jj-idea-2570.4` paged log, `jj-idea-n6fz.4` conflict gutter)
points here; per-feature specifics stay in the bead. Background on the gating layer itself:
[preview-gating-and-dnd-sequencing.md](preview-gating-and-dnd-sequencing.md).

## Status

All three features (drag-and-drop, paged log, conflict gutter) have graduated; `PreviewFeature` is
empty and `docs/preview-changelog.md` is retired. The access-code machinery is kept for the next
gated feature and is tested through the `PreviewFeatureSpec` seam (`TestPreviewFeature` in test
sources), since the enum has no entries to exercise it with.

## Plan

Graduate **all** preview features in one release and cut it as a **minor** bump:

```bash
gh workflow run "Build and Release" --repo kkkev/jj-idea -f bump=minor
```

## Checklist (per feature)

1. **Remove the gate.** Delete the `PreviewFeature` entry and the guards at its install sites.
   Leave a `/** bit N retired - never reuse */` marker in `PreviewFeature.kt` (reuse would let an
   old, still-valid access code grant a new feature). Remove its bundle keys
   (`preview.<id>.*`) and any `jjidea.preview.<id>` system-property handling.
2. **Tests.** Drop entitlement/property setup and the off-state tests (the state no longer
   exists). `PreviewEntitlementTest`/`PreviewCodeTest` need a surviving fixture feature; if the
   feature being graduated is the last one, introduce a test-only seam
   (`@VisibleForTesting`) rather than leaving them fixture-less.
3. **Settings.** The "Preview features" group must handle having zero features. Whichever
   graduation lands last owns this.
4. **`CHANGELOG.md`.** Add user-facing entries under `[Unreleased]`, condensed from the
   feature's `docs/preview-changelog.md` entries. No class/method/architecture terms, no bead
   ids; link GitHub issues.
5. **`docs/preview-changelog.md`.** Delete the feature's entries. When the last feature
   graduates, retire the file and update what references it: the CI changelog gate in
   `.github/workflows/build.yml` and the mentions in `contributing.md`.
6. **Release summary.** A short prose headline paragraph directly under `## [Unreleased]`,
   above `### Added`. The release workflow moves everything under that heading into the new
   version section verbatim, so it carries through. The first graduation to land creates the
   paragraph; each adds a sentence for its feature. **Verified** (jj-idea-jxii): the extraction reads every line between the `## [Unreleased]`
   heading and the next `## [`, so prose before the first `###` carries through.
7. **`ROADMAP.md` and README.** Remove or rewrite entries that describe the feature as planned
   or in progress.
8. **`docs/manual-tests.md`.** Remove "requires access code / system property" preconditions;
   update the Preview-features section under MT-SETTINGS.
9. **Acceptance.** Full manual pass of the feature's `MT-*` sections with no access code and no
   property set; `./gradlew check` green; grep shows zero references to the removed enum entry.
