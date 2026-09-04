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
- Drag and Drop: dragging a commit onto another commit now rebases it there — drop on the middle
  of a row to rebase onto it, or near the top/bottom edge to insert it just after/before that
  commit. Applies immediately, with an Undo option in the notification that appears.
- Drag and Drop: dragging onto a row you can't actually drop on (a different repository, a commit
  that would create a cycle, an immutable commit) now reliably shows a "can't drop here" indicator
  on that row, instead of an inconsistent or missing cursor change.
