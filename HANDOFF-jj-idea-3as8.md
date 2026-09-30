# TEMPORARY handoff: jj-idea-3as8 + jj-idea-5zio

> **Remove this file before anything reaches `master`.** It lives in its own commit on the
> `fix/jj-idea-3as8-html-icon-zoom` branch; abandon that commit (or delete the file) as part of
> finishing - see "Finishing" below.

## What was done (commit "Render HTML icons and chips like the log table at any zoom; copy chip text")

- **jj-idea-3as8 (icons/chips misaligned at non-100% zoom).** Root causes:
  1. `ScaleCorrectedIcon` (HtmlIcons.kt) divided every HTML icon's size by the IDE zoom - a leftover
     from JBHtmlPane's `JBIconView`, which scaled icons itself but was since replaced by
     `IconLeafView`. At 200% an icon reserved 16px but painted 32px (overlap + 16px too low); at
     85% the reverse. Removed; `IconLeafView` now uses the icon's real size, centred on the text
     line (matching the log table, whose `JLabel`s are centred by `BoxLayout`).
  2. Chip text didn't follow zoom: `AtomicHtmlView` rendered chips through a private nested
     `HTMLEditorKit` document seeded by a CSS `font-size: Npt` round trip.
- **Option A (chosen with the user):** `appendUnbreakable` now records the same text/icon runs as the
  log table's `FragmentRecordingCanvas` (`ChipContent`/`ChipRun` in `UnbreakableContent.kt`, a
  line/field wire format inside the `<img src='unbreakable:...'>` payload). `AtomicHtmlView` lays out
  and paints the runs itself: fonts from the surrounding text's own view attributes plus each run's
  extra style (`smaller` = `SMALLER_SCALE`), icons via `IconResolver` at real size, text on the outer
  baseline, icons centred. The nested document, `displayPropertiesToCss` and `AtomicHtmlViewFontTest`
  are gone. `LEADING_GAP` (2px, zoom-scaled) was kept - re-judge it visually.
- **jj-idea-5zio (chip text not copyable).** `IconAwareHtmlPaneCopy.kt`: a copy-only
  `TransferHandler` on `IconAwareHtmlPane` that substitutes each chip's `ChipContent.plainText` for
  its placeholder character; standalone icons copy as nothing. Plain text only (no HTML flavour).
  Deliberate gaps (`TextCanvas.space()`) still copy as U+00A0 between chips - unchanged, out of scope.

## Verified in the cloud session

- `./gradlew check` green (ktlint, unit tests, platform tests).
- New/changed tests: `UnbreakableContentTest`, `HtmlTextCanvasTest` (chip runs, inner issue link),
  `CopyableTextTest`, `IconZoomRenderingTest` (platform; zoom via `JBUIScale.setUserScaleFactorForTest`
  + scaled `Label.font` + `pane.font`, renders PNGs to `build/reports/icon-zoom/`).
- The two icon tests in `IconZoomRenderingTest` were confirmed to FAIL with the old divide-by-zoom
  sizing re-inserted.

## NOT verified - needs the manual pass

- Headless tests can't render our `SvgIcon`s (1x1 placeholder via its private class loader) and
  headless icons don't grow with the test's scale. So icon *pixels* at real zoom levels, and vertical
  centring of chip icons, are only checked manually.
- Whether chip text now follows the real "Zoom IDE" (the test emulates it; the real mechanism may
  differ).
- Observed but pre-existing (also on the base commit 57f6408d): running only a *subset* of platform
  tests ends with "Found a leaked instance of ProjectImpl" via `AllVcses$MyExtensionPointListener`.
  The full `./gradlew check` does not hit it.

## Manual test script (docs/manual-tests.md, MT-LOG-DETAILS)

New sections: "Icons and chips at non-default zoom (jj-idea-3as8)" and "Copying chip text
(jj-idea-5zio)". Manual regression scope: **MT-LOG-DETAILS, MT-LOG-TABLE, MT-BOOKMARK, MT-DND**.

## Bead updates to make (bd was unavailable in the cloud session)

- jj-idea-3as8: add a note summarising the root causes/fix above; close once the zoom manual pass
  succeeds.
- jj-idea-5zio: close once the copy manual pass succeeds (note: plain text only; nbsp gaps between
  chips unchanged).
- Consider filing (task, P3): "Headless platform tests can't render SvgIcon (1x1 placeholder), so
  icon pixels at zoom are untestable" and (bug, P3/P4) "Platform test subset runs end with an
  AllVcses ProjectImpl leak".
- If any manual check fails, keep the bead open, note the failing step, and fix before finishing.

## Finishing (per CLAUDE.md "finish")

1. Remove this file: abandon its commit (`jj abandon <change>` - the commit described
   "TEMPORARY: handoff notes ...") and confirm `HANDOFF-jj-idea-3as8.md` no longer exists in `@`.
2. `./gradlew check`.
3. CHANGELOG `[Unreleased]` already has two Fixed entries for this work - adjust if the manual pass
   changed anything.
4. `jj describe` as needed, `bd export -o .beads/issues.jsonl`, `jj bookmark set master`.
5. Ask the user before any `jj git push`; also delete the remote `fix/jj-idea-3as8-html-icon-zoom`
   branch afterwards if the user wants.
