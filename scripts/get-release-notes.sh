#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"

if [[ -z "$version" ]]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

notes_file="release-notes/${version}.md"

if [[ -f "$notes_file" ]]; then
  cat "$notes_file"
  exit 0
fi

if [[ -f "CHANGELOG.md" ]]; then
  awk -v version="$version" '
    BEGIN { found = 0 }
    $0 ~ "^## " version "([[:space:]]|$|-)" { found = 1; next }
    /^## / && found { exit }
    found { print }
  ' CHANGELOG.md
  exit 0
fi

echo "Release ${version}" 
echo
echo "No detailed release notes were generated for this version."
