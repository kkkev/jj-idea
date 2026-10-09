# jj behaviour for commit-ID reads and divergent change IDs

Spike jj-idea-29l3, feeding jj-idea-wiz0 (GitHub #130: a remote bookmark at a hidden commit resolves
to the visible commit with the same change ID). Run against **jj 0.37.0** (`JjVersion.MINIMUM`) and
**jj 0.44.0**. The behaviours the fix relies on are pinned by
`src/test/kotlin/in/kkkev/jjidea/contract/CommitIdReadsContractCliTest.kt` (9 tests, pass on both).

To run the tests against the minimum: install it with `scripts/jj-install-version.sh 0.37.0`, put a
directory containing a `jj` symlink to `~/.local/bin/jj-0.37.0` first on `PATH`, then
`./gradlew contractTest --tests '*CommitIdReads*'`.

Fixture: change A (file `f` = `v1`) is pushed to a bare remote as bookmark `cl/X`, commit **a1**. `f` is
then changed to `v2` locally, so the change is amended in place to **a2** and a1 becomes hidden while
`cl/X@origin` still points at it. Output below is verbatim except that IDs differ per run. Unless
stated, 0.37 and 0.44 produced identical output modulo IDs.

## Summary

| | Question | Answer (both versions) | Consequence for wiz0 |
|---|---|---|---|
| a | Bare divergent change ID | Errors, exit 1, in every position tried | Detect via `Change ID \`…\` is divergent` in stderr plus exit 1 |
| b | Hidden full `commit_id` | Resolves for `file show`, `diff -r`, `diff --from/--to`, `log -r`, no flag | Reads by full commit_id fix #130 |
| c | After `op abandon` + `util gc --expire=now` | Still readable | No "commit no longer available" path needed for local gc; keep a generic error message for stores that lose objects |
| d | Short commit-ID prefixes | Hidden commits get shortest prefixes like visible ones; collision behaviour not forced | Always use full 40-char ids for reads |
| e | `--at-op` | Reads the old state; does not snapshot `@` | Usable for consistent multi-read snapshots, not adopted yet |
| f | Template keywords | `hidden`, `divergent`, `change_offset`, `commit_id`, `normal_target.commit_id()/hidden()` all exist at 0.37 | No `JjFeature` gate needed |

One version difference: the `divergent()` **revset function** does not exist at 0.37 (it errors with
`Function \`divergent\` doesn't exist`). The `divergent` **template keyword** exists at both. wiz0 must
not use the revset function. The `change_id/N` offset syntax works at both.

## (a) Bare divergent change ID

Divergence made with two `jj describe -r @ --at-op <same op>` calls.

```
$ jj log -r wlswmvstyklw
Error: Change ID `wlswmvstyklw` is divergent
Hint: Use change offset to select single revision: wlswmvstyklw/0, wlswmvstyklw/1
Hint: Use `change_id(wlswmvstyklw)` to select all revisions
Hint: To abandon unneeded revisions, run `jj abandon <commit_id>`
[exit 1]
```

The same error and exit code came from `file show -r X f`, `diff -r X`, `diff --from X --to root()`,
the multi-revision `log -r "X | root()"`, and the write commands `describe -r X` and `edit X`.
0.37.0 and 0.44.0 have identical text.

`X/0` and `X/1` each resolve to one commit, and `change_id(X)` returns both, at both versions. `X?` is a
syntax error.

**Detection:** exit code 1 and stderr containing ``Change ID `<id>` is divergent``. Match the stable
prefix `is divergent` together with ``Change ID ` ``, and don't match the hint lines.

## (b) Hidden full commit_id

```
$ jj log --no-graph -r d58ba891ce2c… -T 'commit_id.short() ++ " hidden=" ++ hidden'
d58ba891ce2c hidden=true
$ jj file show -r d58ba891ce2c… f
v1
$ jj file show -r lnrsovpopokk… f        # bare change ID: the #130 bug
v2
$ jj diff --from d58ba891ce2c… --to 27c724cc7af2… --git
-v1
+v2
```

`diff -r <hidden>` and `log -r <hidden>` also work with the default revset and no `--hidden`-style flag.
The bare change ID resolves to the visible commit (a2).

## (c) After `op abandon` + `util gc`

A commit made hidden by amending, then `jj op abandon ..<previous op>` and `jj util gc --expire=now`:

```
$ jj op abandon ..767561985bcf
Abandoned 11 operations and reparented 1 descendant operations.
$ jj util gc --expire=now
$ jj file show -r 89811484ebd8… g
hidden-only
$ jj file show -r 283aeb2d7356… f        # a1, still referenced by cl/X@origin
v1
```

Both stayed readable. The local Git backend keeps the objects (they remain in the colocated Git store
until Git's own gc), so this does not prove the commit survives on other backends such as Piper. See the
reporter question below.

## (d) Short commit-ID prefixes

```
$ jj log -r "<a1> | <a2>" -T 'commit_id.shortest() ++ " " ++ commit_id.shortest(1) ++ " hidden=" ++ hidden'
# 0.44.0
2 2 hidden=false
d d hidden=true
# 0.37.0
3 3 hidden=false
63 63 hidden=true
```

Hidden commits are given shortest prefixes like any other. I did not force a collision between a
prefix of a hidden commit and a visible one, so whether a printed prefix can later become ambiguous is
unverified. Decision: reads always use full ids.

## (e) `--at-op`

```
$ jj --at-op <op> file show -r @ f      # after changing f on disk to v3
v2
```

Reads return the state at `<op>`. The op log was unchanged afterwards (checked with
`--ignore-working-copy op log`), so `--at-op` does not snapshot the working copy. It is a candidate for
consistent diff + file show pairs, but a full commit_id already pins the content, so wiz0 does not need it.

## (f) Template keywords at 0.37

```
$ jj log --no-graph -r <a1> -T 'divergent ++ " " ++ change_offset ++ " " ++ hidden'
false 1 true
$ jj bookmark list --all-remotes -T 'name ++ " remote=" ++ remote ++ " normal_target=" ++ normal_target.commit_id().short() ++ " hid=" ++ normal_target.hidden()'
cl/X remote= normal_target=374b7adac8db hid=false
cl/X remote=git normal_target=374b7adac8db hid=false
cl/X remote=origin normal_target=6383f6cbb77c hid=true
```

All keywords exist at 0.37 and 0.44. Note `change_offset` is `1` for a non-divergent hidden commit, so the
plugin's existing "offset only when divergent" rule is why the hidden commit gets a bare change ID.

## Open question for the reporter (draft, not posted)

> Thanks for the detailed report. One thing I can't check locally: does Piper keep superseded commits
> resolvable by their commit ID? My plan is to address reads by full commit ID, which relies on a hidden
> commit staying readable after it's amended away. On a plain Git-backed jj repo it does, even after
> `jj op abandon` and `jj util gc --expire=now`. If Piper can drop them, the plugin needs a clear
> "commit no longer available" message instead of an error.
