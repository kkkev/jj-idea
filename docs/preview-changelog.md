# Preview changelog

Entries for features gated behind a preview access code (see
`docs/design/preview-gating-and-dnd-sequencing.md`). Nothing here is read by the build or the
release workflow — it exists so preview work still satisfies the CI changelog gate honestly,
without leaking to Marketplace change-notes before the feature is generally available.

Write entries in the same user-facing voice as `CHANGELOG.md` — no class names, method names, or
internal architecture terms — so they can be moved into `CHANGELOG.md`'s `[Unreleased]` section
verbatim at GA, condensed into a handful of user-facing bullets.

## Unreleased (preview)

- Added a "Preview features" section to Settings, gated behind an access code, for trying
  unfinished features early.
- Load log in pages: the log now loads a page at a time instead of reloading the whole
  configured limit on every change, so operations like New Change stay fast regardless of how
  much history is loaded, and scrolling loads more history on demand. Off by default.
- Load log in pages: scrolling to load more history no longer jumps the viewport back up to the
  current selection.
- Load log in pages: removed the "Showing N changes — scroll for more" status message — there's
  nothing useful left to say once scrolling always loads more.
