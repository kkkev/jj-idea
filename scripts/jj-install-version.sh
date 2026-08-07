#!/usr/bin/env bash
# Installs a specific jj (Jujutsu) release as a version-named binary, for testing
# version-gated plugin features (jj/JjFeature.kt, jj/JjVersion.MINIMUM) against a jj
# older or newer than whatever's on PATH. See contributing.md § "Testing against multiple
# jj versions" and docs/manual-tests.md § Test Tooling.
#
# `jj-install-version.sh 0.39.0` installs `<bin-dir>/jj-0.39.0` plus a `jj-0.39` alias
# (without the patch component, for convenience) — without touching whatever `jj` on PATH
# already points at (manage that separately, e.g. `brew upgrade jj`, for the newest).
#
# Usage: scripts/jj-install-version.sh VERSION [bin-dir]
#   VERSION  e.g. 0.39.0 (a "v" prefix, e.g. v0.39.0, is also accepted)
#   bin-dir  defaults to ~/.local/bin; put it on PATH once and every pinned version and
#            its alias become directly runnable (jj-0.39) from anywhere
set -euo pipefail

version="${1:?Usage: scripts/jj-install-version.sh VERSION [bin-dir] (e.g. 0.39.0)}"
version="${version#v}"
bin_dir="${2:-$HOME/.local/bin}"

arch=$(uname -m)
os=$(uname -s)
case "$os-$arch" in
    Darwin-arm64) target=aarch64-apple-darwin ;;
    Darwin-x86_64) target=x86_64-apple-darwin ;;
    Linux-x86_64) target=x86_64-unknown-linux-musl ;;
    Linux-aarch64) target=aarch64-unknown-linux-musl ;;
    *) echo "jj-install-version.sh: unsupported platform $os-$arch" >&2; exit 1 ;;
esac

mkdir -p "$bin_dir"

asset="jj-v${version}-${target}.tar.gz"
url="https://github.com/jj-vcs/jj/releases/download/v${version}/${asset}"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Downloading $url"
curl -fsSL -o "$tmp/$asset" "$url"
tar -xzf "$tmp/$asset" -C "$tmp"

installed="$bin_dir/jj-${version}"
cp "$tmp/jj" "$installed"
chmod +x "$installed"

# Convenience alias without the patch component, e.g. jj-0.39 -> jj-0.39.0 (last one installed wins).
minor_alias="$bin_dir/jj-${version%.*}"
ln -sf "jj-${version}" "$minor_alias"

echo "Installed $installed"
echo "Aliased    $minor_alias -> jj-${version}"
"$installed" --version
