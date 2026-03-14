#!/usr/bin/env bash
set -euo pipefail

DEFAULT_BRANCH="${1:-master}"
UPSTREAM_REF="${2:-upstream/master}"
SYNC_BRANCH="${3:-automation/upstream-sync}"
BUILD_FILE="V2rayNG/app/build.gradle.kts"
APP_CONFIG="V2rayNG/app/src/main/java/com/v2ray/ang/AppConfig.kt"

merge_base="$(git merge-base "$DEFAULT_BRANCH" "$UPSTREAM_REF")"
upstream_head="$(git rev-parse "$UPSTREAM_REF")"
base_head="$(git rev-parse "$merge_base")"

if [[ "$upstream_head" == "$base_head" ]]; then
    echo "No new upstream commits to sync."
    exit 0
fi

patch_file="$(mktemp)"
git diff --binary "${merge_base}..${DEFAULT_BRANCH}" > "$patch_file"

git checkout -B "$SYNC_BRANCH" "$UPSTREAM_REF"
git apply --3way "$patch_file"

upstream_sha="$(git rev-parse --short "$UPSTREAM_REF")"
upstream_subject="$(git log -1 --format=%s "$UPSTREAM_REF")"
upstream_version="snapshot"

if [[ "$upstream_subject" =~ ^up[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    upstream_version="${BASH_REMATCH[1]}"
fi

current_version="$(sed -n 's/.*versionName = "\([0-9]\+\.[0-9]\+\.[0-9]\+\)".*/\1/p' "$BUILD_FILE" | head -n1)"
current_code="$(sed -n 's/.*versionCode = \([0-9]\+\).*/\1/p' "$BUILD_FILE" | head -n1)"

IFS='.' read -r major minor patch <<< "$current_version"
next_version="${major}.${minor}.$((patch + 1))"
next_code="$((current_code + 1))"

sed -i "s/versionCode = ${current_code}/versionCode = ${next_code}/" "$BUILD_FILE"
sed -i "s/versionName = \"${current_version}\"/versionName = \"${next_version}\"/" "$BUILD_FILE"
sed -i "s/const val UPSTREAM_VERSION_NAME = \".*\"/const val UPSTREAM_VERSION_NAME = \"${upstream_version}\"/" "$APP_CONFIG"
sed -i "s/const val UPSTREAM_COMMIT = \".*\"/const val UPSTREAM_COMMIT = \"${upstream_sha}\"/" "$APP_CONFIG"

echo "Prepared sync branch ${SYNC_BRANCH} with upstream ${upstream_version} (${upstream_sha}) and fork version ${next_version}."
