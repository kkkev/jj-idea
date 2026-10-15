# jj-idea-ehwm: revset-based log filtering — spike findings (ehwm.1) and compiler (ehwm.2)

Epic: GitHub #116 follow-up. All log filters compile to one revset per repo
(`compileLogFilterRevset`, `ui/log/LogFilterRevset.kt`); this note records what the spike
established so ehwm.3 (id-driven filtering) can be built without re-measuring.
Measured with jj 0.44.0 on this repo (986 commits). **jj 0.37 (the minimum supported version) was
not available locally; the contract test `LogFilterRevsetContractCliTest` runs under whichever jj
CI provides, so confirm it on 0.37 before relying on the syntax below there.**

## (d) Term syntax — pinned by `LogFilterRevsetContractCliTest`

| Chip | Term | Notes |
|---|---|---|
| Reference (bookmark) | `::(bookmarks(exact:"n") \| remote_bookmarks(exact:"n"))` | Covers local and remote-only bookmarks by bare name, as the old client filter did. |
| Reference (tag) | `::(tags(exact:"n"))` | |
| Reference (`@`) | `::(@)` | |
| Author | `author_email(exact:"e")`, OR-ed | Case-sensitive, like the old exact-email match. |
| Date | `author_date(after:"<kotlinx Instant.toString()>")` | ISO-8601 `…Z`; offsets and date-only also parse. |
| Paths | `files(root:"p")`, OR-ed | `root:` matches a file *or* a directory subtree, and not a partial component (`dir` does not match `dir one`). `root-file:` does not match directories. |
| Text search | existing `logSearchRevset` | |

Gotchas found:
- `::"foo@git"` **fails**: a quoted `name@remote` is a single symbol. A remote ref is `"foo"@"git"`.
  The compiler avoids the issue by using `remote_bookmarks(exact:…)`.
- Quote every value with `quoteRevsetString` (now `internal`); it handles `"` and `\`.
- `jj describe --reset-author` does not exist in 0.44; use `jj metaedit --update-author`.

## (a) Paging the matching set
- `--limit N` returns exactly the first N rows of the full result in log order (verified as a prefix).
- Resuming is already solved: `PagedLogWindow(baseRevset)` (docs/design/jj-idea-2c8k-paged-log-loading.md)
  pages **any** revset with a frontier cursor, and its `recordPage` termination rule exists
  precisely for filters that aren't closed under ancestry (sparse matches). The exclusion-list
  cursor was already measured and rejected there.
- **Recommendation for ehwm.3:** page the compiled revset with a per-tab `PagedLogWindow` over full
  `LogEntry` rows instead of building a separate ids-only cursor. This is a deviation from epic
  decision 4 ("ids, then fetch missing"): the page query returns the rows directly, so there is no
  separate id query and no fetch-missing step for matches. Store the rows in `logCache` and keep a
  per-repo bucket, as `searchWholeRepo` does. Order is stable across refreshes as far as log order
  is, and a refresh re-seeds the window.

## (b) Fetching missing rows by id
- One batched call works: 380 `present(<id>)` terms (19 KB of arguments) → 380 rows in ~30 ms.
- **Fetch by commit id, not change id.** A bare divergent change id makes the whole call fail
  (`Change ID … is divergent`); this repo has divergent changes. A bare id that no longer exists
  also fails, hence `present()`.
- Keep batches at page size (≤500); the argument-length ceiling documented for paging (~15k ids) is
  far away.

## (c) Connecting deep matches to their nearest visible ancestor
- `connected(S)` (the elided intermediates, i.e. what hlu3's remap would need) is proportional to
  **history span, not |S|**: 39 matches of `build.gradle.kts` → 893 of 986 commits; 388 `fix`
  matches → 945. An ids+parents query over all of it cost 25–37 ms here, but that scales with repo
  size per page and would defeat paging on large repos. Per-commit `heads(::x- & S)` calls remain
  ruled out (O(visible) processes).
- **Recommendation:** accept the `NOT_LOADED` stub for edges whose target isn't loaded, because
  xi58/sc8m already make it navigable (click loads the target). hlu3's remap then covers only
  *loaded* hidden ancestors, which is its designed scope. A bounded `connected()` fetch, used
  only when the loaded span is small, is a possible later optimisation and is not required.

## Shipped in ehwm.3: `FilterPageCursor`, not `PagedLogWindow`

Section (a) recommended paging the compiled revset with `PagedLogWindow`. Implementing it showed that
is **wrong for a sparse filter**: `PagedLogWindow` resumes from the *parents* of the rows shown. A
match's parent is usually not a match, and the ancestors of that parent include matches that were
already shown, so each page re-fetches the first matches and never advances (it only ever terminates
on an empty page). It is correct only for a revset closed under ancestry, i.e. the base log.

`FilterPageCursor` (`ui/log/FilterPageCursor.kt`) resumes from the **heads of the matches not yet
shown** instead. What is left is exactly `R & ::frontier`, because log order lists children before
parents, so a page leaves behind an ancestry-closed remainder:

- seed: `heads(R)`
- page: `(R) & ::(frontier)` with `--limit N`
- next frontier: `heads(((R) & ::(frontier)) ~ (present(<page commit ids>)))`; a page shorter than
  `N` is the last one and needs no remainder query.

Cost per page: two `jj log` calls, with arguments bounded by branch count plus `N` (not by how much has
been shown). `FilterPageCursorContractTest` walks sparse filters on linear and many-branch real
histories and checks the result equals jj's single-query result, with nothing repeated or skipped.
Too wide a frontier (`PagedLogWindow.FRONTIER_CAP`) falls back to one unpaged, capped fetch.

Everything else in (a)-(c) stands: rows are fetched directly (no id query), kept in a per-repo bucket
beside `logCache`, deep matches get `NOT_LOADED` stubs, the Root filter stays client-side. While a
filter change is being resolved the table holds its last result (no client-side approximation), with
a "Filtering…" hint.

## Impact on follow-ups
- ehwm.3: use `FilterPageCursor` + `compileLogFilterRevset` (see above); fetch by commit id; no
  separate id cursor. Root filter stays client-side (selects which repos to query).
- hlu3: unchanged scope; ehwm's deep matches show navigable stubs.
