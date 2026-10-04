# Killer Edition Changelog

This file tracks **Killer Edition changes only**. Upstream/project changelogs and release notes are intentionally left untouched.

## v0.10 — 2026-10-04

- Released the accumulated server changes that had been intentionally held instead of cutting a release for every small change.
- Added a dedicated manual release branch trigger so normal commits to `main` do not automatically create release archives.
- Server release workflow remains explicitly controlled to avoid unnecessary ZIP builds.

## v0.9 — 2026-10-03

- Added an explicit **Check Version** control to the server GUI.
- Kept the **Update** button visible in the GUI.
- Cleared stale `.update-stage` data on GUI/server startup so update staging does not grow indefinitely.

## v0.8 — 2026-10-03

- Fixed Windows archive extraction root quoting.
- Added clean release archive extraction testing on Windows.

## v0.7

- Switched runtime distribution to clean runtime-only component archives.
- Added hash-driven component update detection.
- Download only changed runtime components during updates.

## v0.6

- Added compact self-update packaging.
- Added integrated updater GUI with version and patch progress.

## v0.5

- Added self-patcher support to the integrated server GUI.
- Added manifest/asset hashing and unchanged-file skipping.
- Improved GUI launch behavior to avoid a persistent console window.

---
Going forward, new Killer Edition changes should be added here as part of the same commit or release preparation.
