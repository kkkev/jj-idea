# Preview-feature gating and drag-and-drop release sequencing

Companion to [jj-idea-6oeg-drag-and-drop-graph-ops.md](jj-idea-6oeg-drag-and-drop-graph-ops.md).
That document designed the gestures; this one covers how the eleven remaining gesture beads get
built and released without shipping half-finished drag/drop to every Marketplace user, plus an
early look at gating for a possible future freemium model.

**Any session picking up a `dnd-batch-*`-labelled bead should read this document first.**
Find your batch with `bd list --label dnd-batch-N` (N = 0..6, see the table in Part C). Batch
order and content is authoritative here; if a bead's own description conflicts, this doc wins —
update the bead, don't silently diverge.

## Context

`jj-idea-6oeg` (spike, closed) produced the DnD design doc and 13 follow-up beads. The two P1
blockers are done — `jj-idea-6jvh` (DnD core: payload/target model, y-aware hit-test, zones,
indicator, guards) and `jj-idea-v9zp` (undo via `jj op revert` + balloon). `ui/dnd/` exists and
`JujutsuLogTable.installDragAndDrop(parent)` (`ui/log/JujutsuLogTableDnD.kt:41`) is wired but
inert: `DropPerformers.forLogTable(project)` returns `null`, so nothing is droppable yet.

Drag-and-drop is the top-requested competitive gap (GitHub #93, #97; three distinct requesters),
but it will ship rough and incomplete across several releases. Shipping it visible to every
Marketplace user would produce a barrage of feedback on half-finished gestures. Separately,
there is an early idea to gate features for a possible freemium model later — this doc's gating
layer is deliberately shaped to extend to that later without a rewrite.

## Part A — The preview-feature gating layer

Mirror the existing version-gate pattern in `jj/JjFeature.kt` — an enum plus free functions,
with the "is it on" question answered by one service.

### New files

**`src/main/kotlin/in/kkkev/jjidea/preview/PreviewFeature.kt`**

```kotlin
enum class PreviewFeature(val id: String, private val displayNameKey: String) {
    DRAG_AND_DROP("dragAndDrop", "preview.dragAndDrop.name");
    val displayName: String get() = JujutsuBundle.message(displayNameKey)
}
```

Deliberately parallel to `JjFeature` (`jj/JjFeature.kt:15`), including the `displayNameKey` →
`JujutsuBundle` indirection, so the settings panel can list preview features the same way
`JujutsuConfigurable.featureLine()` (`settings/JujutsuConfigurable.kt:733`) lists version-gated
ones.

**`src/main/kotlin/in/kkkev/jjidea/preview/PreviewEntitlement.kt`** — an app-level `@Service`
answering `isEnabled(feature: PreviewFeature): Boolean`, resolved through ordered providers:

1. **System property / registry escape hatch** — `jjidea.preview.<id>=true`. For dev, CI, and
   platform tests. Not documented to users.
2. **Access code** — the code the user entered validates, *and* the per-feature opt-in toggle is
   on. This is the tester path.
3. *(future)* **Licence** — `LicensingFacade` from the Marketplace freemium API. A new provider
   in this same list; nothing else changes.

Default when no provider says yes: **off**.

**`src/main/kotlin/in/kkkev/jjidea/preview/AccessCode.kt`** — offline validation. SHA-256 of
`normalise(code) + salt`, compared against hashes loaded from a plugin resource
(`resources/preview/access-codes.txt`, one hash per line, `#` comments allowed). A resource
rather than a Kotlin constant so rotating or adding a code is a one-line, non-code edit.

Be clear-eyed about the strength: a determined user can decompile the jar or share the code with
a friend. That is fine — the goal is to keep casual Marketplace users from stumbling into an
unfinished gesture and filing issues, not to protect revenue. The freemium provider added later
will do real signature verification via the platform's licensing API; this provider is not the
thing that will guard paid features.

### Changed files

- **`settings/JujutsuApplicationSettings.kt`** — add to `JujutsuApplicationSettingsState`:
  `previewAccessCode: String = ""` and `enabledPreviewFeatures: String = ""` (comma-separated
  ids; a string keeps `XmlSerializerUtil.copyBean` happy and matches the existing
  `featureNudgeShownKey` style). App-level and `RoamingType.DISABLED`, consistent with the rest
  of that state class.

- **`settings/JujutsuConfigurable.kt`** — a new `group("Preview features")`, placed after the
  existing `settings.group.general` group. It renders in two states:
  - **No valid code**: a single text field ("Access code") plus a one-line explanation. No
    feature names, no hints about what is behind it — the group is the only visible surface.
  - **Valid code**: the field (showing it validated) plus one checkbox per `PreviewFeature`,
    each labelled with its `displayName` and a short "this is unfinished, here's where to send
    feedback" line linking to a dedicated GitHub issue template.

  Note the panel's width budget constraint that `jj-idea-bwdk` and `jj-idea-ckml` both hit —
  keep the access-code field narrow and the explanatory text wrapped.

- **`ui/log/JujutsuLogTableDnD.kt:41`** — guard the whole body of `installDragAndDrop`:
  return early unless `PreviewFeature.DRAG_AND_DROP` is enabled. This is the one seam for the log
  table; the bookmarks-panel, details-panel, and changes-tree beads (batches 3–4 below) each add
  one more analogous install site, and each gets the same guard. Guard at **install**, not
  per-drop, so a disabled preview feature costs nothing at drag time and cannot half-work.

- **`JujutsuBundle` properties** — `preview.dragAndDrop.name`, group title, field labels.

### Tests

- `AccessCode` validation: correct code, wrong code, whitespace/case normalisation, empty,
  rotated hash list.
- `PreviewEntitlement` provider precedence: system property beats everything; code-without-toggle
  is off; toggle-without-code is off; default off.
- A platform test asserting `installDragAndDrop` installs no `DnDSupport` when the feature is
  off (the existing DnD tests set the system property to turn it on).

No filesystem traversal or per-file/per-commit loop here, so no scale analysis is owed.

## Part B — Changelog and release handling

The three consumers of `CHANGELOG.md` are `extractChangelogNotes()` (`build.gradle.kts:10`,
feeds Marketplace `changeNotes`), the release-notes awk (`.github/workflows/build.yml:270`), and
the changelog-rewrite awk (`build.yml:304`). All three read `[Unreleased]`, so anything landing
there ships publicly at the next release.

**Hold preview entries in `docs/preview-changelog.md`**, a plain file no automation reads. Same
user-facing voice as `CHANGELOG.md` (no class names, no internal terms), so entries transplant
verbatim at GA.

One supporting change: the CI gate at `build.yml:64` fails a source-changing commit that did not
touch `CHANGELOG.md`. Widen its `git diff --name-only` path filter to
`'CHANGELOG.md' 'docs/preview-changelog.md'` so preview work satisfies the gate honestly instead
of using `[skip changelog]` on every commit.

**At GA**: move the accumulated entries into `[Unreleased]`, condensing per-bead entries into a
handful of user-facing ones; delete the access-code group from settings (or leave it, now empty
of features); flip `PreviewFeature.DRAG_AND_DROP`'s default to on, or remove the enum entry and
its guards.

**Distribution stays as-is** — one stable stream to Marketplace (plugin 30576) plus
`updatePlugins.xml`. No beta channel: the gate is the code, not the build. Testers get the same
build as everyone else, which means preview code is exercised by the full compat matrix and
never drifts.

## Part C — Session plan

Each session = one bead batch = one PR. Batching is by **surface**, because beads sharing a
surface touch the same install site and renderers — the same reason the bookmark suite had to
ship as one PR. Every bead below carries a `dnd-batch-N` label; find your batch's beads with
`bd list --label dnd-batch-N`.

| Batch | Beads | Surface / files |
|---|---|---|
| **0** | `jj-idea-vpvz` (gating layer), `jj-idea-fhyo` (changelog holding file) | `preview/`, `settings/`, guard in `JujutsuLogTableDnD.kt` |
| **1** | `jj-idea-8fxs` | Log table commit→commit rebase; supplies the first `DropPerformers.forLogTable` |
| **2** | `jj-idea-ibth`, `jj-idea-vdwh`, `jj-idea-p6nb` | Chip payloads + copy-modifier — all extend `DragPayload`/`DropTarget` and the same log-table hit-test |
| **3** | `jj-idea-yvry`, `jj-idea-b2oi` | Changes tree as payload source (`ui/common/JujutsuChangesTree.kt`); `b2oi` depends on `yvry` |
| **4** | `jj-idea-0rdm`, `jj-idea-3xab`, `jj-idea-4ji7` | Bookmarks panel + details panel as sources/targets; `3xab` depends on `0rdm` |
| **5** | `jj-idea-pk2c`, `jj-idea-j8ij` | Working-copy `@` drag; rebase source-mode differentiation |
| **6** | `jj-idea-3uhu`, `jj-idea-9d7r` | Live preview and adaptive zones — polish, both gated on `8fxs` shipping |

All batch-1..6 beads `depends on` `jj-idea-vpvz` (the gating bead) in beads, so `bd ready` will
not surface them until batch 0 lands. Batches 2–6 can run in any order once their own
dependencies (noted above and in each bead's `DEPENDS ON`) are closed; only the intra-batch order
in this table is hard.

Per-session discipline:

- `bd show <lead-bead-of-your-batch>` first — full acceptance criteria and current dependency
  state live there, this doc only gives the batching rationale.
- Claim with `bd update <id> --claim`; close each with `bd close` as you finish it; close the
  whole batch before ending the session.
- Changelog entry goes in `docs/preview-changelog.md`, **not** `CHANGELOG.md`, until GA.
- Every gesture is behind `PreviewFeature.DRAG_AND_DROP` — verify by toggling the code off and
  confirming no drag initiates.
- Report a **Manual regression scope** per contributing.md; add a `MT-DND-*` section to
  `docs/manual-tests.md` in batch 0 and extend it per gesture batch.

## Part D — Considered: a separate "premium" plugin that extends the base one

**Verdict: technically feasible, but not now — and drag/drop is the worst possible first
candidate for it.**

### How it would work

A second plugin declares `<depends>in.kkkev.jj-idea</depends>`, which puts the base plugin's
classloader on its own, so it can call base classes and contribute to extension points the base
declares. The base would grow an `<extensionPoints>` block for each seam the premium plugin
plugs into — e.g. a `dropPerformer` EP so `DropPerformers.forLogTable` (currently returning
`null`, `ui/dnd/DropPerformers.kt`) resolves through contributions. `jj-idea-1y1j` already tracks
the related context-menu/toolbar extensibility work (GH #68).

Marketplace has no problem with a paid plugin depending on a free one. But a paid plugin is
**not** what makes freemium possible: Marketplace's Freemium model lets a *single* free-to-install
plugin gate individual features on a paid licence, checked via `LicensingFacade.confirmationStamps`
(`platform/platform-impl/src/com/intellij/ui/LicensingFacade.java:30`) keyed by the plugin's
Marketplace product code, with the stamp signature verified offline against JetBrains' public
key. Either shape requires the same thing from you: a verified vendor account with the paid-plugin
agreement. The split buys no licensing capability that a single plugin lacks.

### What the split actually costs

- **Every seam becomes published API.** `installDragAndDrop` is `internal` today
  (`ui/log/JujutsuLogTableDnD.kt:41`); `DragPayload`/`DropTarget`/`DropZone` are internal shapes
  still churning bead-to-bead. Extraction freezes them, and you own base↔premium version
  compatibility on top of the existing three-platform compat matrix.
- **Dynamic unload risk doubles.** `jj-idea-8awj` is already open on plugin-classloader retention
  across dynamic unload; a cross-plugin dependency edge is exactly where that gets harder.
- **Second of everything** — Gradle module, `sinceBuild` floor, CI leg, changelog, release cut.
- **Worse UX for the upsell.** A missing premium plugin means the feature does not exist; a
  single plugin can show the action disabled with "requires Pro", which is both discoverable and
  honest.
- **Drag/drop is cross-cutting, not self-contained.** It needs the log table's hit-testing,
  renderers, and layout internals, the changes tree, the bookmarks panel, and the details panel.
  Extracting it means publishing most of the plugin's UI internals as API. Self-contained
  features — forge/PR integration, the operation-log UI (`jj-idea-aii0`) — extract cleanly; this
  one does not.

One genuine advantage, worth naming: for the *beta* specifically, a separate plugin gates
perfectly, because you hand testers a zip privately rather than shipping the code to everyone.
The price is side-loading and manual updates for testers, and preview code that never runs
through the compat matrix. The access code (Part A) buys the same selectivity without either.

### What to do instead

Keep the single-plugin entitlement layer in Part A, and keep the door open cheaply:

- Keep `preview/` free of dependencies on UI internals, so it can later become the shared API
  surface a premium plugin talks to.
- Add the freemium `LicensingFacade` check as a fourth provider when there is a real paid tier —
  no restructuring needed.
- Revisit the split only when a premium feature is genuinely self-contained *and* there is
  revenue justifying the second release pipeline.

## Verification (batch 0)

1. `./gradlew check` — unit tests for `AccessCode`, `PreviewEntitlement`, settings round-trip.
2. `./gradlew runIde`, no access code set: open the log, attempt to drag a commit row — nothing
   should initiate, no indicator, no cursor change.
3. Enter a valid code in Settings → Version Control → Jujutsu → Preview features; tick
   Drag and Drop; Apply. Confirm whether the log table needs re-creating to pick up the guard
   change (the guard is at install time) — if a restart is needed, say so in the settings copy
   rather than pretending otherwise.
4. Enter an invalid code: field shows it as unrecognised, no feature list appears.
5. `-Djjidea.preview.dragAndDrop=true` with no code: drag works (CI path).
6. Confirm a preview-only commit passes the CI changelog gate.
7. Confirm `./gradlew buildPlugin` and inspect the generated `changeNotes` — no preview text.
