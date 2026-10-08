#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
wiki_dir="$(mktemp -d "${TMPDIR:-/tmp}/device-link-wiki.XXXXXX")"
git clone https://git.oryxlabs.internal/ivan-antsimonau/device-link.wiki.git "$wiki_dir"
cp "$repo_root"/docs/wiki/*.md "$wiki_dir/"
git -C "$wiki_dir" add '*.md'
if ! git -C "$wiki_dir" diff --cached --quiet; then
  git -C "$wiki_dir" commit -m 'docs: synchronize DeviceLink workflow and architecture'
  git -C "$wiki_dir" push
fi
printf 'Wiki checkout retained at %s\n' "$wiki_dir"
