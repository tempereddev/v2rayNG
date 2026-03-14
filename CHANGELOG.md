# Changelog

All notable changes to `v2rayNG-Pro` are tracked here so the in-app updater, About screen, and GitHub releases can tell the same story.

## 1.0.7 - 2026-03-13

### UI polish
- Simplified About so it only keeps the essential fork and upstream version details.
- Reduced the visual weight of the "Modified by Maziyar" line to make the page cleaner and more professional.
- Replaced the long What's New text block with a smaller, more minimal dialog layout.

## 1.0.6 - 2026-03-13

### Release reliability
- Fixed the missing `Utils` import that broke the Kotlin release build in 1.0.5.
- Stabilized the GitHub Actions release flow so `master` only prepares tags and the actual APK build happens on tagged/manual release runs.
- Prevented half-published release pages by creating or updating releases only during the buildable release path.

## 1.0.5 - 2026-03-13

### Build transparency
- Added a bundled release-notes catalog so the app can explain the installed build even without opening GitHub.
- Added a one-time "What's new" dialog after version changes so users immediately see what changed.
- Expanded the About screen with current-build highlights and a direct link to release history.

### Update experience
- Enhanced the update screen to show release metadata such as publish date, asset count, and release channel.
- Improved release-note fallbacks so empty release bodies are explained instead of appearing broken.

### Release pipeline
- Added a managed changelog and per-version release-notes file for predictable publishing.
- Updated GitHub Actions to create or edit releases with a proper title and notes body before uploading APK assets.

## 1.0.4 - 2026-03-13

### Update detection
- Improved GitHub release detection so the app still reports a new version even if a device-specific APK asset is not ready yet.
- Added fallback behavior to open the release page directly when automatic asset selection is not possible.

## 1.0.3 - 2026-03-13

### Fork identity
- Expanded About to show the fork version, official upstream version, and upstream commit.
- Added upstream-sync automation groundwork to make future merges from the original project safer and more repeatable.
- Reworked README and app metadata so the fork identity is easier to understand and find.

## 1.0.2 - 2026-03-13

### Independent app variant
- Rebranded the fork as `v2rayNG-Pro`.
- Changed the Android package ID so the fork installs alongside the official app instead of replacing it.
- Pointed in-app update checks and release assets to the fork's own GitHub repository.
