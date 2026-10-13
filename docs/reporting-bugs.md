# Reporting bugs

Please [open an issue](https://github.com/kkkev/jj-idea/issues/new/choose). Useful things to include:

- Plugin version, IDE and build (Help → About), and `jj --version`
- What you did, what you expected, and what happened
- Logs: **Help → Show Log in Finder/Explorer** (`idea.log`), or the stack trace from the
  exception dialog. All `jj` invocations are logged there.

## Repository size (for slow or freezing reports)

For performance problems, it is really useful to capture the size of your repository. Nothing is collected
automatically, so if you're happy to share, open a console at the repository root, run this, and paste the output:

```sh
echo "files:     $(jj file list --ignore-working-copy | wc -l)"
echo "commits:   $(jj log --ignore-working-copy --no-graph -r 'all()' -T '"x\n"' | wc -l)"
echo "bookmarks: $(jj bookmark list --all --ignore-working-copy 2>/dev/null | wc -l)"
echo "tags:      $(jj tag list --ignore-working-copy | wc -l)"
[ -e .git ] && echo "colocated: yes" || echo "colocated: no"
jj --version
```

Note that this is read-only (`--ignore-working-copy` avoids snapshotting) and reports counts only.

On very large repos, the commit count can take a while to run, and it is additionally useful to capture how long that
takes, too (prefix the command with `time`).

The bug report form also asks whether you use the plugin for personal, open-source or commercial
work. This is optional, and only used to decide how much effort to put into large-repo support.
